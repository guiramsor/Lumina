package com.lumina.audiolibros.sync

import android.content.Context
import com.lumina.audiolibros.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Cliente de sincronización contra Supabase.
 *
 * Implementa el contrato de docs/SYNC.md hablando REST directamente, sin SDK:
 * son cuatro llamadas y así la app no arrastra dependencias ni versiones
 * ajenas. Solo viaja la posición de escucha; los audios nunca salen del móvil.
 */
object SupabaseSync {

    private const val PREFS = "lumina_sync"
    private const val CLAVE_ACCESO = "access_token"
    private const val CLAVE_REFRESCO = "refresh_token"
    private const val CLAVE_CADUCA = "expira_en"
    private const val CLAVE_CORREO = "correo"

    private val url = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY

    /** Progreso tal y como viaja entre dispositivos. */
    data class Progreso(
        val bookId: String,
        val trackId: String?,
        val posicionSegundos: Double,
        val posicionGlobalSegundos: Double,
        val duracionSegundos: Double?,
        val terminado: Boolean,
        val actualizadoEn: Long,
        val dispositivo: String?,
    )

    fun configurado(): Boolean = url.isNotEmpty() && anonKey.isNotEmpty()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun correoSesion(context: Context): String? = prefs(context).getString(CLAVE_CORREO, null)

    fun haySesion(context: Context): Boolean = prefs(context).getString(CLAVE_ACCESO, null) != null

    fun cerrarSesion(context: Context) {
        prefs(context).edit().clear().apply()
        sesionCaducada = false
    }

    /* ---------------- Sesión ---------------- */

    suspend fun iniciarSesion(context: Context, correo: String, contrasena: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cuerpo = JSONObject()
                    .put("email", correo)
                    .put("password", contrasena)
                val respuesta = peticion(
                    metodo = "POST",
                    ruta = "/auth/v1/token?grant_type=password",
                    cuerpo = cuerpo.toString(),
                    token = anonKey,
                )
                guardarSesion(context, JSONObject(respuesta))
                correoSesion(context) ?: correo
            }.recoverCatching { error ->
                throw Exception(traducir(error.message.orEmpty()))
            }
        }

    private fun guardarSesion(context: Context, json: JSONObject) {
        val correo = json.optJSONObject("user")?.optString("email").orEmpty()
        prefs(context).edit()
            .putString(CLAVE_ACCESO, json.getString("access_token"))
            .putString(CLAVE_REFRESCO, json.optString("refresh_token"))
            // expires_in llega en segundos desde ahora.
            .putLong(CLAVE_CADUCA, System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000)
            .putString(CLAVE_CORREO, correo)
            .apply()
    }

    /**
     * La sesión ha caducado de verdad y hay que volver a entrar.
     *
     * Se distingue de «no hay sesión» porque el usuario sí entró alguna vez y
     * merece que se lo digan, en vez de dejarle creyendo que sincroniza.
     */
    @Volatile var sesionCaducada: Boolean = false
        private set

    /**
     * ¿El servidor ha **rechazado** el refresco, o solo no se ha podido
     * preguntar?
     *
     * La diferencia decide si se conserva la sesión o se tira. Un corte de red
     * es pasajero y tirar la sesión por él obligaría a escribir la contraseña
     * cada vez que se pierde cobertura. Un refresco rechazado, en cambio, ya no
     * se arregla solo: el token viejo seguirá dando «JWT expired» para siempre.
     */
    private fun sesionRechazada(error: Throwable): Boolean {
        if (error is java.io.IOException) return false
        val m = error.message.orEmpty()
        return m.contains("invalid_grant", true) ||
            m.contains("refresh_token", true) ||
            m.contains("Refresh Token", true) ||
            m.contains("HTTP 400") ||
            m.contains("HTTP 401")
    }

    /**
     * Devuelve un token válido, renovándolo si está a punto de caducar.
     *
     * El refresco va sincronizado porque lo piden a la vez el reloj del
     * servicio y la pantalla, y los refresh_token de Supabase son de un solo
     * uso: dos refrescos simultáneos se invalidan entre ellos y tumban una
     * sesión que estaba sana.
     */
    @Synchronized
    private fun token(context: Context): String? {
        val p = prefs(context)
        val acceso = p.getString(CLAVE_ACCESO, null) ?: return null
        val caduca = p.getLong(CLAVE_CADUCA, 0)
        if (System.currentTimeMillis() < caduca - 60_000) {
            sesionCaducada = false
            return acceso
        }

        val refresco = p.getString(CLAVE_REFRESCO, null)?.takeIf { it.isNotEmpty() && it != "null" }
        if (refresco == null) {
            // Un token caducado sin con qué renovarlo no vale para nada: lo
            // único que hace es dar «JWT expired» en cada peticion.
            marcarCaducada(context)
            return null
        }
        return runCatching {
            val respuesta = peticion(
                metodo = "POST",
                ruta = "/auth/v1/token?grant_type=refresh_token",
                cuerpo = JSONObject().put("refresh_token", refresco).toString(),
                token = anonKey,
            )
            guardarSesion(context, JSONObject(respuesta))
            sesionCaducada = false
            p.getString(CLAVE_ACCESO, null)
        }.getOrElse { error ->
            if (sesionRechazada(error)) {
                // Insistir con el token viejo dejaba la app «con sesión» pero
                // incapaz de sincronizar, sin más señal que un aviso genérico
                // de falta de conexión. Se tira y se pide entrar otra vez.
                android.util.Log.w("LuminaSync", "Sesion caducada, hay que volver a entrar", error)
                marcarCaducada(context)
                null
            } else {
                // Solo un corte de red: se sigue con el token viejo, que puede
                // que aún valga, y escuchar nunca depende de esto.
                acceso
            }
        }
    }

    private fun marcarCaducada(context: Context) {
        cerrarSesion(context)
        sesionCaducada = true
    }

    /* ---------------- Progreso ---------------- */

    /**
     * Posición guardada en la nube.
     *
     * Devuelve `success(null)` cuando el libro aún no tiene fila, y `failure`
     * cuando no se ha podido leer. La diferencia es crítica: nunca se debe
     * sobrescribir una posición que no hemos llegado a leer, o una lectura
     * fallida borraría el avance hecho en el otro dispositivo.
     */
    /** Dónde vive este libro en la nube, y su posición guardada. */
    data class Reconciliacion(val syncId: String, val progreso: Progreso?)

    /**
     * Averigua en qué fila de la nube vive este libro, y deja solo una.
     *
     * La huella identifica el archivo, distinto en cada dispositivo si las
     * copias no son idénticas. El `syncId` identifica la fila compartida: en
     * cuanto un dispositivo reconoce el libro del otro adopta su
     * identificador, y los dos escriben en el mismo sitio. Sin esto cada uno
     * seguiría en su propia fila y no volverían a encontrarse.
     */
    suspend fun reconciliar(
        context: Context,
        fingerprint: String,
        syncId: String? = null,
        duracionSegundos: Double = 0.0,
        titulo: String? = null,
        autor: String? = null,
    ): Result<Reconciliacion> = withContext(Dispatchers.IO) {
        val porDefecto = Reconciliacion(syncId ?: fingerprint, null)
        if (!configurado()) return@withContext Result.success(porDefecto)
        val acceso = token(context)
            ?: return@withContext Result.failure(Exception("Sin sesión"))

        runCatching {
            // Camino rápido: el libro ya sabe dónde vive.
            if (syncId != null) {
                val filtro = URLEncoder.encode("eq.$syncId", "UTF-8")
                val filas = JSONArray(
                    peticion("GET", "/rest/v1/progress?book_id=$filtro&select=*", null, acceso)
                )
                if (filas.length() > 0) {
                    return@runCatching Reconciliacion(syncId, leerFila(filas.getJSONObject(0)))
                }
            }

            if (duracionSegundos <= 0) return@runCatching porDefecto

            val margen = EmparejarLibros.toleranciaDuracion(duracionSegundos)
            val desde = URLEncoder.encode("gte.${duracionSegundos - margen}", "UTF-8")
            val hasta = URLEncoder.encode("lte.${duracionSegundos + margen}", "UTF-8")
            val porDuracion = JSONArray(
                peticion("GET", "/rest/v1/progress?duration=$desde&duration=$hasta&select=*", null, acceso)
            )
            val candidatas = (0 until porDuracion.length()).map { porDuracion.getJSONObject(it) }

            val grupo = EmparejarLibros.agruparMismoLibro(
                filas = candidatas,
                idsPropios = listOfNotNull(fingerprint, syncId),
                duracion = duracionSegundos,
                titulo = titulo,
                autor = autor,
                idDe = { it.getString("book_id") },
                duracionDe = { it.optDouble("duration").takeIf { d -> !d.isNaN() } },
                tituloDe = { textoDe(it, "title") },
                autorDe = { textoDe(it, "author") },
            )
            if (grupo.isEmpty()) return@runCatching porDefecto

            val canonica = EmparejarLibros.elegirCanonica(grupo) { it.getString("book_id") }!!
            val idCanonico = canonica.getString("book_id")
            val avanzada = grupo.maxByOrNull { posicionDe(it) }!!
            val sobrantes = grupo.map { it.getString("book_id") }.filter { it != idCanonico }

            // Varias filas del mismo libro: se conserva la posición más
            // avanzada en la canónica y se retiran las demás, que solo
            // servirían para volver a dividir la sincronización.
            if (sobrantes.isNotEmpty()) {
                if (avanzada.getString("book_id") != idCanonico) {
                    val fusion = JSONObject(avanzada.toString())
                        .put("book_id", idCanonico)
                        .put("updated_at", iso8601(System.currentTimeMillis()))
                    peticion(
                        "POST", "/rest/v1/progress", JSONArray().put(fusion).toString(), acceso,
                        mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"),
                    )
                }
                val lista = URLEncoder.encode("in.(${sobrantes.joinToString(",")})", "UTF-8")
                peticion("DELETE", "/rest/v1/progress?book_id=$lista", null, acceso)
                android.util.Log.i("LuminaSync", "Fusionadas ${sobrantes.size + 1} filas en $idCanonico")
            }

            Reconciliacion(idCanonico, leerFila(avanzada).copy(bookId = idCanonico))
        }.onFailure { android.util.Log.w("LuminaSync", "No se pudo reconciliar el libro", it) }
    }

    private fun posicionDe(fila: JSONObject): Double = EmparejarLibros.posicionAbsoluta(
        fila.optDouble("global_position", 0.0),
        fila.optDouble("position", 0.0),
    )

    /**
     * Texto de una columna que puede venir nula.
     *
     * `optString` del org.json de Android devuelve la cadena literal `"null"`
     * cuando el valor es un null de JSON, no la cadena vacía. Colarlo en la
     * clave de desempate haría que dos libros sin título se consideraran el
     * mismo.
     */
    internal fun textoDe(fila: JSONObject, columna: String): String? =
        fila.optString(columna).takeIf { it.isNotEmpty() && it != "null" }

    private fun leerFila(fila: JSONObject) = Progreso(
        bookId = fila.getString("book_id"),
        trackId = textoDe(fila, "track_id"),
        posicionSegundos = fila.optDouble("position", 0.0),
        posicionGlobalSegundos = fila.optDouble("global_position", 0.0),
        duracionSegundos = fila.optDouble("duration").takeIf { !it.isNaN() },
        terminado = fila.optBoolean("finished", false),
        actualizadoEn = instanteDe(fila.optString("updated_at")),
        dispositivo = textoDe(fila, "device"),
    )

    /**
     * Sube la posición actual. Nunca lanza: un fallo de red no debe molestar.
     *
     * El `autor` no es decorativo aunque solo se use para depurar a simple
     * vista: junto al título forma la clave con la que se desempata cuando dos
     * libros tienen duraciones parecidas. Sin él, las filas escritas por el
     * móvil daban la clave `titulo|` y nunca coincidían con las del ordenador,
     * así que el desempate no desempataba nunca y el libro se quedaba sin
     * sincronizar, en silencio y sin error.
     */
    suspend fun subir(
        context: Context,
        progreso: Progreso,
        titulo: String?,
        autor: String? = null,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (!configurado()) return@withContext false
            val acceso = token(context) ?: return@withContext false
            runCatching {
                val fila = JSONObject()
                    .put("book_id", progreso.bookId)
                    .put("track_id", progreso.trackId ?: JSONObject.NULL)
                    .put("position", progreso.posicionSegundos)
                    .put("global_position", progreso.posicionGlobalSegundos)
                    .put("duration", progreso.duracionSegundos ?: JSONObject.NULL)
                    .put("finished", progreso.terminado)
                    .put("title", titulo ?: JSONObject.NULL)
                    .put("author", autor?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                    .put("device", "Móvil (Android)")
                    .put("updated_at", iso8601(progreso.actualizadoEn))
                peticion(
                    metodo = "POST",
                    ruta = "/rest/v1/progress",
                    cuerpo = JSONArray().put(fila).toString(),
                    token = acceso,
                    // Upsert sobre la clave primaria (user_id, book_id).
                    cabecerasExtra = mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"),
                )
                true
            }.getOrElse { false }
        }

    /**
     * Decide qué posición vale. Igual que en el escritorio: gana la escucha
     * más **avanzada**, no la más reciente, para que ningún dispositivo haga
     * retroceder lo escuchado en el otro. Ver docs/SYNC.md.
     */
    fun ganaLaRemota(remota: Progreso?, posicionLocalSegundos: Double): Boolean {
        if (remota == null) return false
        val posRemota = EmparejarLibros.posicionAbsoluta(
            remota.posicionGlobalSegundos, remota.posicionSegundos
        )
        return EmparejarLibros.ganaLaRemota(posicionLocalSegundos, posRemota)
    }

    /* ---------------- HTTP ---------------- */

    private fun peticion(
        metodo: String,
        ruta: String,
        cuerpo: String?,
        token: String,
        cabecerasExtra: Map<String, String> = emptyMap(),
    ): String {
        val conexion = (URL("$url$ruta").openConnection() as HttpURLConnection).apply {
            requestMethod = metodo
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("apikey", anonKey)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            cabecerasExtra.forEach { (clave, valor) -> setRequestProperty(clave, valor) }
            if (cuerpo != null) doOutput = true
        }
        try {
            cuerpo?.let { conexion.outputStream.use { salida -> salida.write(it.toByteArray()) } }
            val codigo = conexion.responseCode
            val flujo = if (codigo in 200..299) conexion.inputStream else conexion.errorStream
            val texto = flujo?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (codigo !in 200..299) throw Exception(texto.ifEmpty { "HTTP $codigo" })
            return texto
        } finally {
            conexion.disconnect()
        }
    }

    private fun traducir(mensaje: String): String = when {
        mensaje.contains("invalid_grant", true) ||
            mensaje.contains("Invalid login", true) -> "Correo o contraseña incorrectos."
        mensaje.contains("Email not confirmed", true) -> "Falta confirmar el correo de la cuenta."
        mensaje.contains("Unable to resolve host", true) ||
            mensaje.contains("timeout", true) -> "Sin conexión con el servidor."
        else -> mensaje.ifEmpty { "No se pudo iniciar sesión." }
    }

    private fun iso8601(instante: Long): String {
        val formato = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        formato.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return formato.format(java.util.Date(instante))
    }

    private fun instanteDe(texto: String): Long {
        if (texto.isEmpty()) return 0
        // PostgREST devuelve con precisión de microsegundos y zona; se recorta
        // a segundos, que es de sobra para decidir qué escucha es posterior.
        val normalizado = texto.substringBefore('.').substringBefore('+').trimEnd('Z')
        return runCatching {
            val formato = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            formato.timeZone = java.util.TimeZone.getTimeZone("UTC")
            formato.parse(normalizado)?.time ?: 0
        }.getOrDefault(0)
    }
}
