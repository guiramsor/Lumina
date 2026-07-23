package com.lumina.audiolibros.player

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.lumina.audiolibros.sync.SupabaseSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Clave con la que la pantalla adjunta la huella de la pista al MediaItem. */
const val EXTRA_TRACK_ID = "lumina_track_id"

/**
 * Servicio de reproducción.
 *
 * Es un MediaSessionService, no un reproductor dentro de la Activity: eso es
 * lo que permite que el audio siga sonando con la pantalla apagada o la app
 * en segundo plano, y lo que da la notificación con los controles y el manejo
 * de las teclas del sistema (auriculares, coche).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val alcance = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    // Marcarlo como voz hace que el sistema lo trate como
                    // audiolibro o pódcast, no como música.
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true
            )
            // Pausar al desconectar los auriculares, en vez de seguir sonando
            // por el altavoz.
            .setHandleAudioBecomingNoisy(true)
            .build()

        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Cerrar la app desde recientes detiene la reproducción, pero nunca antes
     * de guardar la posición: es justo el momento en que el otro dispositivo
     * querrá retomar. La subida corre aquí, en el servicio, porque la pantalla
     * ya no existe cuando se retira la tarea.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null) {
            stopSelf()
            return
        }

        val item = player.currentMediaItem
        val bookId = item?.mediaId?.takeIf { it.isNotEmpty() }
        val trackId = item?.mediaMetadata?.extras?.getString(EXTRA_TRACK_ID)
        val titulo = item?.mediaMetadata?.title?.toString()
        val posicionMs = player.currentPosition
        val duracionMs = player.duration

        player.pause()

        if (bookId == null || posicionMs <= 0) {
            detener()
            return
        }

        alcance.launch {
            // Si la red no responde, no se retiene el proceso indefinidamente:
            // vale más cerrar limpio que quedarse colgado.
            withTimeoutOrNull(8_000) {
                SupabaseSync.subir(
                    this@PlaybackService,
                    SupabaseSync.Progreso(
                        bookId = bookId,
                        trackId = trackId,
                        posicionSegundos = posicionMs / 1000.0,
                        posicionGlobalSegundos = posicionMs / 1000.0,
                        duracionSegundos = if (duracionMs > 0) duracionMs / 1000.0 else null,
                        terminado = false,
                        actualizadoEn = System.currentTimeMillis(),
                        dispositivo = null,
                    ),
                    titulo,
                )
            }
            // ExoPlayer solo admite llamadas desde el hilo principal: pararlo
            // desde el hilo de red lanzaría una excepción.
            withContext(Dispatchers.Main) { detener() }
        }
    }

    private fun detener() {
        session?.player?.stop()
        stopSelf()
    }

    override fun onDestroy() {
        alcance.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
