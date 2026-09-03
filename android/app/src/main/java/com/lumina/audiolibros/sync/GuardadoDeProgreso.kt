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
    @Volatile private var titulo: String? = null
    @Volatile private var autor: String? = null

    /**
     * Las pistas del libro abierto, en el orden del contrato.
     *
     * Hacen falta para traducir «pista 7, minuto 3» a la posición global que es
     * lo único que viaja por la nube, y para saber qué huella de pista subir:
     * el otro dispositivo la usa para retomar en la pista exacta.
     */
    @Volatile private var duraciones: List<Long> = emptyList()
    @Volatile private var trackIds: List<String> = emptyList()

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
     *
     * Se consume con la subida que la lleva a la nube: ver `Intencion`.
     */
    private val intencion = Intencion()

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

    /**
     * ¿Está la sesión desbloqueada para guardar? Registrar el libro no basta:
     * hasta que el reproductor no está en su posición, guardar destruiría el
     * avance. Lo consulta la prueba del catálogo del coche, porque olvidarse de
     * desbloquearla deja un viaje entero sin guardar y no da ningún error.
     */
    fun estaColocado(): Boolean = colocado

    /* ---------------- Ciclo de vida de la sesión ---------------- */

    /**
     * Registra el libro que se acaba de abrir. Lo llaman tanto la pantalla como
     * el servicio (cuando la reproducción arranca desde Android Auto y la
     * interfaz ni siquiera existe).
     */
    fun abrir(
        bookId: String,
        syncId: String,
        duraciones: List<Long>,
        trackIds: List<String>,
        titulo: String?,
        autor: String?,
        lecturaFiable: Boolean,
        posicionRemota: Double?,
        velocidad: Float = 1f,
    ) {
        this.bookId = bookId
        this.syncId = syncId
        this.duraciones = duraciones
        this.trackIds = trackIds
        this.titulo = titulo
        this.autor = autor
        // La duración del libro sale de sus pistas, no del reproductor: el
        // reproductor solo sabe lo que dura el archivo que tiene puesto, que en
        // un libro de doce capítulos es una doceava parte.
        this.duracionConocidaMs = duraciones.sumOf { it.coerceAtLeast(0) }
        this.lecturaFiable = lecturaFiable
        this.posicionRemota = posicionRemota
        this.velocidad = velocidad
        intencion.reiniciar()
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
        intencion.marcar()
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
     * Guarda la posición: siempre en el disco, y en la nube solo si se cumplen
     * todas las reglas de docs/SYNC.md.
     *
     * Se recibe **dónde está el reproductor** —en qué pista y por qué punto de
     * ella— y aquí se traduce a la posición global del libro, que es lo único
     * que viaja. La duración del libro no se le pregunta al reproductor: él
     * solo sabe lo que dura el archivo que tiene puesto, que en un libro de
     * doce capítulos es una doceava parte.
     */
    suspend fun guardar(
        context: Context,
        indiceDePista: Int,
        dentroDeLaPistaMs: Long,
        forzar: Boolean,
    ): Resultado = aplicar(context, indiceDePista, dentroDeLaPistaMs, forzar, marcaFinal = false)

    /**
     * El libro ha llegado al final.
     *
     * Se marca terminado en local y en la nube: sin esto el móvil seguía
     * subiendo `finished: false` y borraba la marca que sí escribe el
     * ordenador, y al reabrirlo se quedaba parado en el último segundo en vez
     * de empezar de nuevo.
     */
    suspend fun marcarTerminado(context: Context): Resultado {
        if (terminado) return ultimoResultado
        val ultima = (duraciones.size - 1).coerceAtLeast(0)
        val r = aplicar(
            context,
            indiceDePista = ultima,
            dentroDeLaPistaMs = duraciones.getOrElse(ultima) { 0L },
            forzar = true,
            marcaFinal = true,
        )
        terminado = true
        return r
    }

    private suspend fun aplicar(
        context: Context,
        indiceDePista: Int,
        dentroDeLaPistaMs: Long,
        forzar: Boolean,
        marcaFinal: Boolean,
    ): Resultado {
        val libro = bookId
        val duracion = duracionConocidaMs
        val ahora = System.currentTimeMillis()
        // Lo que viaja es la posición global: la suma de lo que duran las
        // pistas anteriores más lo que se lleva de la actual.
        val posicion =
            if (marcaFinal && duracion > 0) duracion
            else PosicionDelLibro.aGlobal(duraciones, indiceDePista, dentroDeLaPistaMs)
        // La huella de la pista en la que se está, para que el otro dispositivo
        // pueda retomar en ella exactamente.
        val pistaActual = trackIds.getOrNull(indiceDePista)

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
                intencionada = intencion.activa(),
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
        // El sello se toma ANTES de salir: si el usuario vuelve a saltar
        // mientras esta subida está en vuelo, al volver no habrá que bajar la
        // bandera, porque ya no será la misma intención.
        val selloDeEstaSubida = intencion.sello()
        val bien = try {
            SupabaseSync.subir(
                context,
                SupabaseSync.Progreso(
                    bookId = syncId ?: libro,
                    trackId = pistaActual,
                    posicionSegundos = posicion / 1000.0,
                    posicionGlobalSegundos = posicion / 1000.0,
                    duracionSegundos = duracion / 1000.0,
                    terminado = marcaFinal,
                    actualizadoEn = ahora,
                    dispositivo = null,
                ),
                titulo,
                autor,
                // Manda al servidor si esta escritura puede ir hacia atras.
                intencionado = intencion.activa(),
            )
        } finally {
            subiendo = false
        }
        if (!bien) return anotar(Resultado.FALLO_AL_SUBIR)

        // La intención ya está en la nube: deja de valer. Sin esto, un solo
        // toque a «+30 s» dejaba todas las subidas de las horas siguientes
        // saltándose la comprobación del servidor.
        intencion.cumplida(selloDeEstaSubida)
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
