package com.lumina.audiolibros.ui

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.lumina.audiolibros.data.AlmacenLocal
import com.lumina.audiolibros.library.Audiolibro
import com.lumina.audiolibros.player.EXTRA_SYNC_ID
import com.lumina.audiolibros.player.EXTRA_TRACK_ID
import com.lumina.audiolibros.player.PlaybackService
import com.lumina.audiolibros.sync.EmparejarLibros
import com.lumina.audiolibros.sync.SupabaseSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Retroceso al reanudar, copiado de la app de escritorio: cuanto más tiempo
 * lleves sin escuchar, más atrás retoma para que recuperes el hilo.
 * Debe coincidir con smartRewindSeconds de src/player/PlayerContext.jsx.
 */
fun segundosDeRebobinado(pausaMs: Long): Long {
    val s = pausaMs / 1000
    return when {
        s < 30 -> 0
        s < 5 * 60 -> 5
        s < 30 * 60 -> 10
        s < 2 * 3600 -> 15
        s < 24 * 3600 -> 25
        else -> 30
    }
}

val VELOCIDADES = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

/** Modo del temporizador de sueño. */
enum class ModoSueno { NINGUNO, MINUTOS }

/** Para poder mostrar sin ruido si la posición llegó o no a la nube. */
enum class EstadoSync { INACTIVO, SUBIENDO, HECHO, FALLO }

/**
 * Estado central de la reproducción: la versión Android del PlayerContext.
 * Reúne el reproductor, el almacén local y la sincronización, para que las
 * pantallas solo pinten.
 */
class EstadoReproductor(
    private val context: Context,
    private val alcance: CoroutineScope,
) {
    var controller: MediaController? = null
    var libro by mutableStateOf<Audiolibro?>(null)
        private set
    var sonando by mutableStateOf(false)
    var posicionMs by mutableLongStateOf(0L)
    var duracionMs by mutableLongStateOf(0L)
    var velocidad by mutableFloatStateOf(1f)
        private set
    var aviso by mutableStateOf<String?>(null)
    var cargando by mutableStateOf(false)

    var modoSueno by mutableStateOf(ModoSueno.NINGUNO)
        private set
    var suenoRestanteS by mutableIntStateOf(0)

    var estadoSync by mutableStateOf(EstadoSync.INACTIVO)
        private set
    var sincronizadoEn by mutableStateOf<Long?>(null)
        private set

    private var ultimaPausaEn: Long? = null
    private var ultimaSubida = 0L
    private var segundosAcumulados = 0.0

    /**
     * Dos cerrojos que impiden destruir el avance hecho en el otro dispositivo:
     *
     * - `colocado`: hasta que el reproductor no está en la posición correcta,
     *   no se guarda nada. Si no, los primeros segundos desde cero se
     *   guardarían como si fueran la posición real.
     * - `lecturaRemotaFiable`: si no se ha podido leer la nube, jamás se sube.
     *   Sobrescribir lo que no has llegado a leer es la forma más rápida de
     *   perder siete horas de escucha.
     */
    private var colocado = false
    private var lecturaRemotaFiable = false
    /** Ultima posicion remota conocida del libro abierto, en segundos. */
    private var posicionRemotaConocida: Double? = null
    /** Fila de la nube en la que vive el libro abierto. */
    private var syncIdActual: String? = null
    /**
     * La posición la ha elegido el usuario (barra o saltos), no la inercia de
     * la reproducción. Una posición elegida manda sobre la nube aunque sea
     * anterior: retroceder media hora tiene que persistir.
     */
    private var posicionIntencionada = false
    /** Evita marcar el final varias veces si el reproductor repite el estado. */
    private var terminadoYa = false

    /* ---------------- Abrir ---------------- */

    fun abrir(elegido: Audiolibro, alTerminar: () -> Unit = {}) {
        val c = controller ?: return
        cargando = true
        aviso = null
        colocado = false
        lecturaRemotaFiable = false
        posicionIntencionada = false
        terminadoYa = false
        alcance.launch {
            val local = AlmacenLocal.progreso(context, elegido.bookId)
            // Averigua en qué fila de la nube vive el libro. La duración y las
            // etiquetas lo reconocen aunque el archivo del ordenador no sea
            // idéntico al de este móvil, y entonces se adopta su identificador.
            val lectura = SupabaseSync.reconciliar(
                context,
                fingerprint = elegido.bookId,
                syncId = AlmacenLocal.syncId(context, elegido.bookId),
                duracionSegundos = elegido.duracionMs / 1000.0,
                titulo = elegido.titulo,
                autor = elegido.autor,
            )
            val remoto = lectura.getOrNull()?.progreso
            lecturaRemotaFiable = lectura.isSuccess
            val idNube = lectura.getOrNull()?.syncId ?: elegido.bookId
            syncIdActual = idNube
            AlmacenLocal.guardarSyncId(context, elegido.bookId, idNube)
            posicionRemotaConocida = remoto?.let {
                EmparejarLibros.posicionAbsoluta(it.posicionGlobalSegundos, it.posicionSegundos)
            }

            var posicion = local?.posicionMs ?: 0L
            var escuchadoEn = local?.actualizadoEn ?: 0L
            var terminado = local?.terminado == true

            // Gana la escucha más avanzada, venga de donde venga.
            if (SupabaseSync.ganaLaRemota(remoto, posicion / 1000.0) && remoto != null) {
                // Absoluta, no la del interior de la pista: si el ordenador
                // tiene el libro partido en capítulos, `position` es el segundo
                // dentro de uno de ellos y saltaríamos a un punto sin relación.
                val segundos = EmparejarLibros.posicionAbsoluta(
                    remoto.posicionGlobalSegundos, remoto.posicionSegundos
                )
                posicion = (segundos * 1000).toLong()
                    .coerceIn(0L, if (elegido.duracionMs > 0) elegido.duracionMs else Long.MAX_VALUE)
                escuchadoEn = remoto.actualizadoEn
                terminado = remoto.terminado
                aviso = "Retomado desde ${formatearTiempo(posicion)}" +
                    (remoto.dispositivo?.let { " · $it" } ?: "")
            }

            // Un libro terminado se reabre desde el principio: es una
            // decisión, no inercia, así que debe propagarse.
            if (terminado) {
                posicion = 0L
                posicionIntencionada = true
            }

            // Rebobinado inteligente entre sesiones.
            if (posicion > 0 && escuchadoEn > 0) {
                posicion = (posicion - segundosDeRebobinado(System.currentTimeMillis() - escuchadoEn) * 1000)
                    .coerceAtLeast(0L)
            }

            val suya = local?.velocidad ?: AlmacenLocal.velocidadPorDefecto(context)
            libro = elegido
            velocidad = suya
            ultimaPausaEn = null

            android.util.Log.i(
                "LuminaSync",
                "abrir '${elegido.titulo}' local=${local?.posicionMs}ms " +
                    "remoto=${remoto?.posicionSegundos?.toLong()}s fiable=$lecturaRemotaFiable -> ${posicion}ms"
            )

            val item = MediaItem.Builder()
                .setUri(elegido.uri)
                .setMediaId(elegido.bookId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(elegido.titulo)
                        .setArtist(elegido.autor.ifEmpty { "Audiolibro" })
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .setExtras(
                            Bundle().apply {
                                putString(EXTRA_TRACK_ID, elegido.trackId)
                                // El servicio guarda al cerrar la app, cuando
                                // esta clase ya no existe: necesita saber en
                                // qué fila escribir.
                                putString(EXTRA_SYNC_ID, idNube)
                            }
                        )
                        .build()
                )
                .build()

            // La posición se pasa al cargar el medio, no con un seekTo posterior:
            // así no hay ventana en la que el reproductor esté en el segundo 0.
            c.setMediaItem(item, posicion)
            c.setPlaybackSpeed(suya)
            c.prepare()
            c.play()
            colocado = true
            cargando = false

            if (!lecturaRemotaFiable) {
                aviso = "Sin conexión con la nube: se escucha desde la posición del móvil " +
                    "y no se subirá nada para no pisar la del ordenador."
            }
            alTerminar()
        }
    }

    /* ---------------- Controles ---------------- */

    fun alternar() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            // Al reanudar tras una pausa larga, retroceder un poco.
            ultimaPausaEn?.let { pausa ->
                val atras = segundosDeRebobinado(System.currentTimeMillis() - pausa) * 1000
                if (atras > 0) c.seekTo((c.currentPosition - atras).coerceAtLeast(0L))
            }
            ultimaPausaEn = null
            c.play()
        }
    }

    fun saltar(segundos: Long) {
        val c = controller ?: return
        val destino = (c.currentPosition + segundos * 1000).coerceIn(0L, c.duration.coerceAtLeast(0L))
        c.seekTo(destino)
        ultimaPausaEn = null
        posicionIntencionada = true
        guardar(forzar = true)
    }

    fun buscar(ms: Long) {
        val c = controller ?: return
        c.seekTo(ms.coerceIn(0L, c.duration.coerceAtLeast(0L)))
        ultimaPausaEn = null
        posicionIntencionada = true
        guardar(forzar = true)
    }

    fun cambiarVelocidad(nueva: Float) {
        velocidad = nueva
        controller?.setPlaybackSpeed(nueva)
        AlmacenLocal.guardarVelocidadPorDefecto(context, nueva)
        guardar(forzar = false)
    }

    /* ---------------- Temporizador de sueño ---------------- */

    /**
     * El temporizador guarda el instante en que debe callarse, no cuántos
     * ticks le quedan: así no se desfasa si el reloj de la interfaz se retrasa,
     * y lo que se muestra es siempre el tiempo real que queda.
     */
    private var finSuenoEn: Long? = null

    fun iniciarSueno(minutos: Int) {
        AlmacenLocal.guardarMinutosSueno(context, minutos)
        modoSueno = ModoSueno.MINUTOS
        finSuenoEn = System.currentTimeMillis() + minutos * 60_000L
        suenoRestanteS = minutos * 60
    }

    fun cancelarSueno() {
        modoSueno = ModoSueno.NINGUNO
        finSuenoEn = null
        suenoRestanteS = 0
        controller?.volume = 1f
    }

    /** Cuenta atrás con desvanecido en los últimos 12 s, como en el escritorio. */
    fun tictacSueno() {
        if (modoSueno != ModoSueno.MINUTOS) return
        val fin = finSuenoEn ?: return
        val restante = ((fin - System.currentTimeMillis()) / 1000).toInt()
        suenoRestanteS = restante.coerceAtLeast(0)
        val c = controller
        if (restante <= 0) {
            c?.pause()
            c?.volume = 1f
            modoSueno = ModoSueno.NINGUNO
            finSuenoEn = null
            return
        }
        c?.volume = if (restante <= 12) restante / 12f else 1f
    }

    /* ---------------- Fin del libro ---------------- */

    /**
     * El libro ha llegado al final. Se marca como terminado en local y en la
     * nube: sin esto el móvil seguía subiendo `finished: false` y borraba la
     * marca que sí escribe el ordenador, y al reabrirlo se quedaba parado en
     * el último segundo en vez de empezar de nuevo.
     */
    fun marcarTerminado() {
        val c = controller ?: return
        val actual = libro ?: return
        if (terminadoYa) return
        terminadoYa = true

        val duracion = c.duration.coerceAtLeast(0L)
        val ahora = System.currentTimeMillis()
        AlmacenLocal.guardarProgreso(
            context,
            AlmacenLocal.Progreso(
                bookId = actual.bookId,
                posicionMs = duracion,
                duracionMs = duracion,
                terminado = true,
                velocidad = velocidad,
                actualizadoEn = ahora,
            )
        )
        if (!SupabaseSync.haySesion(context) || !lecturaRemotaFiable) return
        estadoSync = EstadoSync.SUBIENDO
        alcance.launch {
            val bien = SupabaseSync.subir(
                context,
                SupabaseSync.Progreso(
                    bookId = syncIdActual ?: actual.bookId,
                    trackId = actual.trackId,
                    posicionSegundos = duracion / 1000.0,
                    posicionGlobalSegundos = duracion / 1000.0,
                    duracionSegundos = duracion / 1000.0,
                    terminado = true,
                    actualizadoEn = ahora,
                    dispositivo = null,
                ),
                actual.titulo,
            )
            estadoSync = if (bien) EstadoSync.HECHO else EstadoSync.FALLO
            if (bien) sincronizadoEn = System.currentTimeMillis()
        }
    }

    /* ---------------- Guardado ---------------- */

    fun guardar(forzar: Boolean) {
        val c = controller ?: return
        val actual = libro ?: return
        // Sin haber colocado la posición, lo que hay en el reproductor no
        // representa dónde va la escucha: guardarlo destruiría el avance.
        if (!colocado || terminadoYa) return
        val posicion = c.currentPosition
        if (posicion <= 0) return
        val ahora = System.currentTimeMillis()

        AlmacenLocal.guardarProgreso(
            context,
            AlmacenLocal.Progreso(
                bookId = actual.bookId,
                posicionMs = posicion,
                duracionMs = c.duration.coerceAtLeast(0L),
                terminado = false,
                velocidad = velocidad,
                actualizadoEn = ahora,
            )
        )

        // La nube se actualiza mucho menos a menudo que el disco local.
        if (!forzar && ahora - ultimaSubida < 30_000) return
        if (!SupabaseSync.haySesion(context)) return
        // Nunca sobrescribir una posición remota que no se ha podido leer.
        if (!lecturaRemotaFiable) {
            android.util.Log.w("LuminaSync", "No se sube: la lectura remota fallo al abrir")
            return
        }
        // No pisar una posición más avanzada guardada por el otro dispositivo.
        if (!EmparejarLibros.debeSubir(
                posicion / 1000.0, posicionRemotaConocida, intencionado = posicionIntencionada
            )
        ) {
            android.util.Log.i("LuminaSync", "No se sube: la nube va mas avanzada")
            return
        }
        ultimaSubida = ahora
        estadoSync = EstadoSync.SUBIENDO
        alcance.launch {
            val bien = SupabaseSync.subir(
                context,
                SupabaseSync.Progreso(
                    bookId = syncIdActual ?: actual.bookId,
                    trackId = actual.trackId,
                    posicionSegundos = posicion / 1000.0,
                    posicionGlobalSegundos = posicion / 1000.0,
                    duracionSegundos = (c.duration.coerceAtLeast(0L)) / 1000.0,
                    terminado = false,
                    actualizadoEn = ahora,
                    dispositivo = null,
                ),
                actual.titulo,
            )
            estadoSync = if (bien) EstadoSync.HECHO else EstadoSync.FALLO
            if (bien) {
                sincronizadoEn = System.currentTimeMillis()
                // Lo que acabamos de subir pasa a ser la referencia remota; si
                // no, un retroceso quedaría bloqueado en los guardados
                // siguientes por su propia posición anterior.
                posicionRemotaConocida = posicion / 1000.0
            }
        }
    }

    fun anotarPausa() {
        ultimaPausaEn = System.currentTimeMillis()
    }

    /** Acumula tiempo real de escucha para las estadísticas. */
    fun sumarEscucha(segundos: Double) {
        segundosAcumulados += segundos
        if (segundosAcumulados >= 20) {
            AlmacenLocal.sumarEscucha(context, segundosAcumulados.toInt())
            segundosAcumulados = 0.0
        }
    }

    fun volcarEscucha() {
        if (segundosAcumulados >= 1) {
            AlmacenLocal.sumarEscucha(context, segundosAcumulados.toInt())
            segundosAcumulados = 0.0
        }
    }
}

/** Crea el estado y lo mantiene conectado al servicio mientras viva la pantalla. */
@Composable
fun recordarEstadoReproductor(alcance: CoroutineScope): EstadoReproductor {
    val context = LocalContext.current
    val estado = remember { EstadoReproductor(context, alcance) }

    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val futuro = MediaController.Builder(context, token).buildAsync()
        futuro.addListener({ estado.controller = futuro.get() }, ContextCompat.getMainExecutor(context))
        onDispose {
            estado.volcarEscucha()
            estado.controller = null
            MediaController.releaseFuture(futuro)
        }
    }

    DisposableEffect(estado.controller) {
        val c = estado.controller
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                estado.sonando = isPlaying
                if (!isPlaying) {
                    estado.anotarPausa()
                    estado.volcarEscucha()
                    estado.guardar(forzar = true)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    estado.volcarEscucha()
                    estado.marcarTerminado()
                }
            }
        }
        c?.addListener(listener)
        estado.sonando = c?.isPlaying == true
        onDispose { c?.removeListener(listener) }
    }

    // Reloj: posición, estadísticas, guardado periódico y temporizador.
    LaunchedEffect(estado.controller) {
        var ultimoTic = System.currentTimeMillis()
        var segundoSueno = System.currentTimeMillis()
        while (true) {
            delay(500)
            val c = estado.controller ?: continue
            estado.posicionMs = c.currentPosition
            estado.duracionMs = c.duration.coerceAtLeast(0L)

            val ahora = System.currentTimeMillis()
            if (estado.sonando) {
                val delta = (ahora - ultimoTic) / 1000.0
                if (delta in 0.0..2.0) estado.sumarEscucha(delta)
                estado.guardar(forzar = false)
            }
            ultimoTic = ahora

            if (estado.modoSueno == ModoSueno.MINUTOS && estado.sonando && ahora - segundoSueno >= 1000) {
                segundoSueno = ahora
                estado.tictacSueno()
            }
        }
    }

    return estado
}

internal suspend fun <T> enFondo(bloque: () -> T): T = withContext(Dispatchers.IO) { bloque() }
