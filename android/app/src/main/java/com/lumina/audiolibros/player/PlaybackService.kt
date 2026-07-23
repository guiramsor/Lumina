package com.lumina.audiolibros.player

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

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
     * Si el usuario cierra la app desde recientes mientras está en pausa, se
     * para el servicio. Si estaba sonando, se deja: es justo lo que se espera
     * de un audiolibro.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
