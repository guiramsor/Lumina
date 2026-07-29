package com.lumina.audiolibros.sync

import android.content.Context
import android.util.Log
import com.lumina.audiolibros.data.AlmacenLocal

/**
 * Único sitio por el que pasa el guardado de la posición.
 *
 * Antes había tres: el reloj de la pantalla, el fin de libro y el cierre desde
 * recientes. Los dos primeros aplicaban las reglas de docs/SYNC.md; el tercero
 * subía a pelo. Bastaba con abrir un libro sin cobertura —lo que deja la
 * lectura remota como no fiable y prohíbe subir— y luego cerrar la app desde
 * recientes con la cobertura ya recuperada para que esa ruta sin filtros
 * mandara la posición del móvil por encima de las siete horas del ordenador.
 *
 * De ahí que esto sea un objeto y no un método más: si el guardado vive en un
 * solo sitio, no hay una segunda puerta que se olvide de cerrar. Y por eso no
 * vive en la pantalla, que desaparece en cuanto el usuario sale de la app
 * mientras el servicio sigue sonando.
 *
 * El guardado **local** no tiene riesgo —nunca puede pisar al otro
 * dispositivo— así que se hace siempre. Todo lo que filtra este objeto es la
 * **subida**.
 */
object GuardadoDeProgreso {

    private const val TAG = "LuminaSync"

    /** Cada cuánto se sube como mucho mientras se escucha sin parar. */
    const val ESPERA_ENTRE_SUBIDAS_MS = 30_000L

    /** Qué ha pasado con el último intento, para que la pantalla lo pinte. */
    enum class Resultado { SIN_SESION, SOLO_LOCAL, SUBIDO, FALLO_AL_SUBIR }

    /* ---------------- La decisión, aparte y sin Android ---------------- */

    /**
     * Todo lo que hace falta saber para decidir. Se pasa entero en vez de leer
     * el estado del objeto para poder fijar la tabla de decisiones con tests
     * de JVM, igual que se hace con las reglas de emparejamiento.
     */
    data class Circunstancias(
        val hayLibroAbierto: Boolean,
        val colocado: Boolean,
        val yaTerminado: Boolean,
        val marcaFinal: Boolean,
        val posicionSegundos: Double,
        val duracionMs: Long,
        val hayCuenta: Boolean,
        val lecturaFiable: Boolean,
        val msDesdeLaUltimaSubida: Long,
        val forzar: Boolean,
        val posicionRemota: Double?,
        val intencionada: Boolean,
    )

    /**
     * Qué se puede hacer con esta posición.
     *
     * `guardaEnDisco` en false significa que lo que hay en el reproductor no
     * representa dónde va la escucha; `sube` en false, que escribirlo en la
     * nube podría destruir el avance del otro dispositivo.
     */
    enum class Veredicto(val guardaEnDisco: Boolean, val sube: Boolean, val motivo: String) {
        SIN_LIBRO(false, false, "no hay ningun libro abierto"),
        SIN_COLOCAR(false, false, "el reproductor aun no esta en su sitio"),
        YA_TERMINADO(false, false, "el libro ya se marco terminado"),
        POSICION_ABSURDA(false, false, "la posicion no es valida"),
        SIN_CUENTA(true, false, "no hay sesion iniciada en la nube"),
        LECTURA_NO_FIABLE(true, false, "la lectura remota fallo al abrir"),
        SIN_DURACION(true, false, "aun no se sabe la duracion del libro"),
        DEMASIADO_PRONTO(true, false, "todavia no toca subir"),
        LA_NUBE_VA_MAS_AVANZADA(true, false, "la nube va mas avanzada"),
        SUBIR(true, true, "toca subir"),
    }

    /**
     * La tabla de decisiones entera, en un solo sitio y sin efectos.
     *
     * El orden importa: los cerrojos que protegen del desastre van antes que
     * los que solo ahorran tráfico.
     */
    fun decidir(c: Circunstancias): Veredicto = when {
        !c.hayLibroAbierto -> Veredicto.SIN_LIBRO
        !c.colocado -> Veredicto.SIN_COLOCAR
        c.yaTerminado && !c.marcaFinal -> Veredicto.YA_TERMINADO
        c.posicionSegundos < 0 -> Veredicto.POSICION_ABSURDA
        !c.hayCuenta -> Veredicto.SIN_CUENTA
        // Nunca sobrescribir una posición que no se ha llegado a leer.
        !c.lecturaFiable -> Veredicto.LECTURA_NO_FIABLE
        // Una fila sin duración no la encuentra después ninguna búsqueda por
        // parecido: mejor no escribirla que dejar el libro partido en dos.
        c.duracionMs <= 0 -> Veredicto.SIN_DURACION
        !c.forzar && c.msDesdeLaUltimaSubida < ESPERA_ENTRE_SUBIDAS_MS -> Veredicto.DEMASIADO_PRONTO
        !EmparejarLibros.debeSubir(
            c.posicionSegundos,
            c.posicionRemota,
            terminado = c.marcaFinal,
            intencionado = c.intencionada,
        ) -> Veredicto.LA_NUBE_VA_MAS_AVANZADA
        else -> Veredicto.SUBIR
    }

    /* ---------------- Estado del libro en curso ---------------- */

    @Volatile private var bookId: String? = null
    @Volatile private var syncId: String? = null
    @Volatile private var trackId: String? = null
    @Volatile private var titulo: String? = null
    @Volatile private var autor: String? = null

    /**
     * Duración sabida al abrir, de la biblioteca del teléfono.
     *
     * Hace falta como respaldo porque `player.duration` vale `TIME_UNSET`
     * mientras el reproductor no ha terminado de preparar el medio, y subir una
     * fila con `duration` nula la deja invisible para siempre: la búsqueda por
     * parecido filtra por rango de duración y nunca la devuelve, así que el
     * libro se queda partido en dos filas que ya no vuelven a encontrarse.
     */
    @Volatile private var duracionConocidaMs: Long = 0

    /** ¿Se pudo leer la nube al abrir? Si no, no se sube nada. Regla 3. */
    @Volatile private var lecturaFiable = false

    /** Última posición remota conocida, en segundos. */
    @Volatile private var posicionRemota: Double? = null

    /**
     * La posición la ha elegido el usuario (barra o saltos), no la inercia de
     * la reproducción. Una posición elegida manda sobre la nube aunque sea
     * anterior: retroceder media hora tiene que persistir.
     */
    @Volatile private var intencionada = false

    /** Evita seguir guardando después de marcar el final. */
    @Volatile private var terminado = false

    /**
     * Hasta que el reproductor no está en la posición correcta no se guarda
     * nada: los primeros milisegundos desde cero se tomarían por la posición
     * real y borrarían el avance.
     */
    @Volatile private var colocado = false

    @Volatile private var velocidad = 1f
    @Volatile private var ultimaSubida = 0L
    @Volatile private var segundosAcumulados = 0.0

    /* ---------------- Lo que la pantalla observa ---------------- */

    @Volatile var ultimoResultado: Resultado = Resultado.SIN_SESION
        private set

    @Volatile var sincronizadoEn: Long? = null
        private set

    /** Hay una subida en vuelo ahora mismo, para que la nube de la barra gire. */
    @Volatile var subiendo: Boolean = false
        private set

    /** Libro que está sonando ahora mismo, o null si no hay ninguno abierto. */
    fun libroEnCurso(): String? = bookId

    fun lecturaRemotaFueFiable(): Boolean = lecturaFiable

    /* ---------------- Ciclo de vida de la sesión ---------------- */

    /**
     * Registra el libro que se acaba de abrir. Lo llaman tanto la pantalla como
     * el servicio (cuando la reproducción arranca desde Android Auto y la
     * interfaz ni siquiera existe).
     */
    fun abrir(
        bookId: String,
        syncId: String,
        trackId: String?,
        titulo: String?,
        autor: String?,
        duracionMs: Long,
        lecturaFiable: Boolean,
        posicionRemota: Double?,
        velocidad: Float = 1f,
    ) {
        this.bookId = bookId
        this.syncId = syncId
        this.trackId = trackId
        this.titulo = titulo
        this.autor = autor
        this.duracionConocidaMs = duracionMs
        this.lecturaFiable = lecturaFiable
        this.posicionRemota = posicionRemota
        this.velocidad = velocidad
        intencionada = false
        terminado = false
        colocado = false
        ultimaSubida = 0L
        segundosAcumulados = 0.0
        ultimoResultado = Resultado.SIN_SESION
    }

    /** La posición ya está puesta en el reproductor: a partir de aquí se guarda. */
    fun colocado() {
        colocado = true
    }

    fun cerrar() {
        bookId = null
        colocado = false
    }

    fun marcarIntencionada() {
        intencionada = true
    }

    fun fijarVelocidad(nueva: Float) {
        velocidad = nueva
    }

    /* ---------------- Estadísticas ---------------- */

    /** Acumula tiempo real de escucha. Vive aquí por lo mismo que el guardado. */
    fun sumarEscucha(context: Context, segundos: Double) {
        segundosAcumulados += segundos
        if (segundosAcumulados >= 20) volcarEscucha(context)
    }

    fun volcarEscucha(context: Context) {
        if (segundosAcumulados < 1) return
        AlmacenLocal.sumarEscucha(context, segundosAcumulados.toInt())
        segundosAcumulados -= segundosAcumulados.toInt()
    }

    /* ---------------- Guardado ---------------- */

    /**
     * Duración que se puede afirmar del libro.
     *
     * La del reproductor manda cuando la sabe; si no, la que traía la
     * biblioteca al abrir. Nunca se devuelve un valor sin sentido.
     */
    private fun duracionSegura(delReproductorMs: Long): Long =
        if (delReproductorMs > 0) delReproductorMs else duracionConocidaMs

    /**
     * Guarda la posición: siempre en el disco, y en la nube solo si se cumplen
     * todas las reglas de docs/SYNC.md.
     */
    suspend fun guardar(
        context: Context,
        posicionMs: Long,
        duracionDelReproductorMs: Long,
        forzar: Boolean,
    ): Resultado = aplicar(context, posicionMs, duracionDelReproductorMs, forzar, marcaFinal = false)

    /**
     * El libro ha llegado al final.
     *
     * Se marca terminado en local y en la nube: sin esto el móvil seguía
     * subiendo `finished: false` y borraba la marca que sí escribe el
     * ordenador, y al reabrirlo se quedaba parado en el último segundo en vez
     * de empezar de nuevo.
     */
    suspend fun marcarTerminado(
        context: Context,
        posicionMs: Long,
        duracionDelReproductorMs: Long,
    ): Resultado {
        if (terminado) return ultimoResultado
        val r = aplicar(context, posicionMs, duracionDelReproductorMs, forzar = true, marcaFinal = true)
        terminado = true
        return r
    }

    private suspend fun aplicar(
        context: Context,
        posicionMs: Long,
        duracionDelReproductorMs: Long,
        forzar: Boolean,
        marcaFinal: Boolean,
    ): Resultado {
        val libro = bookId
        val duracion = duracionSegura(duracionDelReproductorMs)
        val ahora = System.currentTimeMillis()
        // Al terminar se guarda la duración entera, que es donde se ha quedado.
        val posicion = if (marcaFinal && duracion > 0) duracion else posicionMs

        val veredicto = decidir(
            Circunstancias(
                hayLibroAbierto = libro != null,
                colocado = colocado,
                yaTerminado = terminado,
                marcaFinal = marcaFinal,
                posicionSegundos = posicion / 1000.0,
                duracionMs = duracion,
                hayCuenta = SupabaseSync.haySesion(context),
                lecturaFiable = lecturaFiable,
                msDesdeLaUltimaSubida = ahora - ultimaSubida,
                forzar = forzar,
                posicionRemota = posicionRemota,
                intencionada = intencionada,
            )
        )

        if (!veredicto.guardaEnDisco) return anotar(Resultado.SIN_SESION)

        // El disco siempre que la posición valga algo: no puede pisar a nadie y
        // es lo que permite escuchar sin conexión.
        AlmacenLocal.guardarProgreso(
            context,
            AlmacenLocal.Progreso(
                bookId = libro!!,
                posicionMs = posicion,
                duracionMs = duracion,
                terminado = marcaFinal,
                velocidad = velocidad,
                actualizadoEn = ahora,
            )
        )

        if (!veredicto.sube) {
            if (veredicto != Veredicto.DEMASIADO_PRONTO) {
                Log.i(TAG, "No se sube '$titulo': ${veredicto.motivo}")
            }
            return anotar(Resultado.SOLO_LOCAL)
        }

        ultimaSubida = ahora
        subiendo = true
        val bien = try {
            SupabaseSync.subir(
                context,
                SupabaseSync.Progreso(
                    bookId = syncId ?: libro,
                    trackId = trackId,
                    posicionSegundos = posicion / 1000.0,
                    posicionGlobalSegundos = posicion / 1000.0,
                    duracionSegundos = duracion / 1000.0,
                    terminado = marcaFinal,
                    actualizadoEn = ahora,
                    dispositivo = null,
                ),
                titulo,
                autor,
            )
        } finally {
            subiendo = false
        }
        if (!bien) return anotar(Resultado.FALLO_AL_SUBIR)

        sincronizadoEn = System.currentTimeMillis()
        // Lo que acabamos de subir pasa a ser la referencia remota; si no, un
        // retroceso quedaría bloqueado en los guardados siguientes por su
        // propia posición anterior.
        posicionRemota = posicion / 1000.0
        return anotar(Resultado.SUBIDO)
    }

    private fun anotar(resultado: Resultado): Resultado {
        ultimoResultado = resultado
        return resultado
    }
}
