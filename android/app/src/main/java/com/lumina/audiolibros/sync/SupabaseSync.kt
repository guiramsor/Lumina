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

    /** Devuelve un token válido, renovándolo si está a punto de caducar. */
    private fun token(context: Context): String? {
        val p = prefs(context)
        val acceso = p.getString(CLAVE_ACCESO, null) ?: return null
        val caduca = p.getLong(CLAVE_CADUCA, 0)
        if (System.currentTimeMillis() < caduca - 60_000) return acceso

        val refresco = p.getString(CLAVE_REFRESCO, null) ?: return acceso
        return runCatching {
            val respuesta = peticion(
                metodo = "POST",
                ruta = "/auth/v1/token?grant_type=refresh_token",
                cuerpo = JSONObject().put("refresh_token", refresco).toString(),
                token = anonKey,
            )
            guardarSesion(context, JSONObject(respuesta))
            p.getString(CLAVE_ACCESO, null)
        }.getOrElse {
            // Si el refresco falla se sigue con el token viejo: puede que solo
            // sea un corte de red y escuchar nunca debe depender de esto.
            acceso
        }
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
    suspend fun descargar(
        context: Context,
        bookId: String,
        duracionSegundos: Double = 0.0,
        titulo: String? = null,
        autor: String? = null,
    ): Result<Progreso?> = withContext(Dispatchers.IO) {
        if (!configurado()) return@withContext Result.success(null)
        val acceso = token(context)
            ?: return@withContext Result.failure(Exception("Sin sesión"))
        runCatching {
            val filtro = URLEncoder.encode("eq.$bookId", "UTF-8")
            val cuerpo = peticion(
                metodo = "GET",
                ruta = "/rest/v1/progress?book_id=$filtro&select=*",
                cuerpo = null,
                token = acceso,
            )
            val filas = JSONArray(cuerpo)
            if (filas.length() > 0) return@runCatching leerFila(filas.getJSONObject(0))

            // Sin fila para esta huella: el mismo libro puede estar en el otro
            // dispositivo con otra codificación o las etiquetas editadas. Se
            // busca por duración, que es lo que no cambia.
            if (duracionSegundos <= 0) return@runCatching null
            val margen = EmparejarLibros.toleranciaDuracion(duracionSegundos)
            val desde = URLEncoder.encode("gte.${duracionSegundos - margen}", "UTF-8")
            val hasta = URLEncoder.encode("lte.${duracionSegundos + margen}", "UTF-8")
            val porDuracion = JSONArray(
                peticion(
                    metodo = "GET",
                    ruta = "/rest/v1/progress?duration=$desde&duration=$hasta&select=*",
                    cuerpo = null,
                    token = acceso,
                )
            )
            val candidatas = (0 until porDuracion.length()).map { porDuracion.getJSONObject(it) }
            val elegida = EmparejarLibros.elegirCoincidencia(
                filas = candidatas,
                duracion = duracionSegundos,
                titulo = titulo,
                autor = autor,
                duracionDe = { it.optDouble("duration").takeIf { d -> !d.isNaN() } },
                tituloDe = { it.optString("title") },
                autorDe = { it.optString("author") },
            )
            elegida?.let {
                android.util.Log.i("LuminaSync", "Libro emparejado por duración con ${it.optString("title")}")
                leerFila(it)
            }
        }.onFailure { android.util.Log.w("LuminaSync", "No se pudo leer el progreso remoto", it) }
    }

    private fun leerFila(fila: JSONObject) = Progreso(
        bookId = fila.getString("book_id"),
        trackId = fila.optString("track_id").takeIf { it.isNotEmpty() && it != "null" },
        posicionSegundos = fila.optDouble("position", 0.0),
        posicionGlobalSegundos = fila.optDouble("global_position", 0.0),
        duracionSegundos = fila.optDouble("duration").takeIf { !it.isNaN() },
        terminado = fila.optBoolean("finished", false),
        actualizadoEn = instanteDe(fila.optString("updated_at")),
        dispositivo = fila.optString("device").takeIf { it.isNotEmpty() },
    )

    /** Sube la posición actual. Nunca lanza: un fallo de red no debe molestar. */
    suspend fun subir(context: Context, progreso: Progreso, titulo: String?): Boolean =
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
        val posRemota = if (remota.posicionGlobalSegundos > 0) {
            remota.posicionGlobalSegundos
        } else remota.posicionSegundos
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
