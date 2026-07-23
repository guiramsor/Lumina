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
import com.lumina.audiolibros.player.EXTRA_TRACK_ID
import com.lumina.audiolibros.player.PlaybackService
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

    private var ultimaPausaEn: Long? = null
    private var ultimaSubida = 0L
    private var segundosAcumulados = 0.0

    /* ---------------- Abrir ---------------- */

    fun abrir(elegido: Audiolibro, alTerminar: () -> Unit = {}) {
        val c = controller ?: return
        cargando = true
        aviso = null
        alcance.launch {
            val local = AlmacenLocal.progreso(context, elegido.bookId)
            val remoto = SupabaseSync.descargar(context, elegido.bookId)

            var posicion = local?.posicionMs ?: 0L
            var escuchadoEn = local?.actualizadoEn ?: 0L
            var terminado = local?.terminado == true

            // Si el otro dispositivo escuchó después, su posición manda.
            if (SupabaseSync.ganaLaRemota(remoto, escuchadoEn) && remoto != null) {
                posicion = (remoto.posicionSegundos * 1000).toLong()
                escuchadoEn = remoto.actualizadoEn
                terminado = remoto.terminado
                aviso = "Retomado desde ${formatearTiempo(posicion)}" +
                    (remoto.dispositivo?.let { " · $it" } ?: "")
            }

            // Un libro terminado se reabre desde el principio.
            if (terminado) posicion = 0L

            // Rebobinado inteligente entre sesiones.
            if (posicion > 0 && escuchadoEn > 0) {
                posicion = (posicion - segundosDeRebobinado(System.currentTimeMillis() - escuchadoEn) * 1000)
                    .coerceAtLeast(0L)
            }

            val suya = local?.velocidad ?: AlmacenLocal.velocidadPorDefecto(context)
            libro = elegido
            velocidad = suya
            ultimaPausaEn = null

            c.setMediaItem(
                MediaItem.Builder()
                    .setUri(elegido.uri)
                    .setMediaId(elegido.bookId)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(elegido.titulo)
                            .setArtist(elegido.autor.ifEmpty { "Audiolibro" })
                            .setIsPlayable(true)
                            .setIsBrowsable(false)
                            .setExtras(Bundle().apply { putString(EXTRA_TRACK_ID, elegido.trackId) })
                            .build()
                    )
                    .build()
            )
            c.prepare()
            if (posicion > 0) c.seekTo(posicion)
            c.setPlaybackSpeed(suya)
            c.play()
            cargando = false
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
        guardar(forzar = true)
    }

    fun buscar(ms: Long) {
        val c = controller ?: return
        c.seekTo(ms.coerceIn(0L, c.duration.coerceAtLeast(0L)))
        ultimaPausaEn = null
        guardar(forzar = true)
    }

    fun cambiarVelocidad(nueva: Float) {
        velocidad = nueva
        controller?.setPlaybackSpeed(nueva)
        AlmacenLocal.guardarVelocidadPorDefecto(context, nueva)
        guardar(forzar = false)
    }

    /* ---------------- Temporizador de sueño ---------------- */

    fun iniciarSueno(minutos: Int) {
        AlmacenLocal.guardarMinutosSueno(context, minutos)
        modoSueno = ModoSueno.MINUTOS
        suenoRestanteS = minutos * 60
    }

    fun cancelarSueno() {
        modoSueno = ModoSueno.NINGUNO
        suenoRestanteS = 0
        controller?.volume = 1f
    }

    /** Cuenta atrás con desvanecido en los últimos 12 s, como en el escritorio. */
    fun tictacSueno() {
        if (modoSueno != ModoSueno.MINUTOS) return
        suenoRestanteS -= 1
        val c = controller
        if (suenoRestanteS <= 0) {
            c?.pause()
            c?.volume = 1f
            modoSueno = ModoSueno.NINGUNO
            return
        }
        c?.volume = if (suenoRestanteS <= 12) suenoRestanteS / 12f else 1f
    }

    /* ---------------- Guardado ---------------- */

    fun guardar(forzar: Boolean) {
        val c = controller ?: return
        val actual = libro ?: return
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
        ultimaSubida = ahora
        alcance.launch {
            SupabaseSync.subir(
                context,
                SupabaseSync.Progreso(
                    bookId = actual.bookId,
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
