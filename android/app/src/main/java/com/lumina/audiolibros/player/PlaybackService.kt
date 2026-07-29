package com.lumina.audiolibros.player

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.lumina.audiolibros.sync.GuardadoDeProgreso
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Clave con la que la pantalla adjunta la huella de la pista al MediaItem. */
const val EXTRA_TRACK_ID = "lumina_track_id"

/** Fila de la nube del libro: puede no coincidir con la huella del archivo. */
const val EXTRA_SYNC_ID = "lumina_sync_id"

/**
 * Servicio de reproducción.
 *
 * No es un reproductor dentro de la Activity: eso es lo que permite que el
 * audio siga sonando con la pantalla apagada, y lo que da la notificación con
 * los controles y el manejo de las teclas del sistema.
 *
 * Es un MediaLibraryService, no un MediaSessionService a secas, porque Android
 * Auto no habla con la interfaz: le pide el catálogo al servicio y le manda a
 * reproducir un identificador. Sin catálogo navegable, la aplicación ni
 * siquiera aparece en la lista del coche.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private val alcance = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reloj: Job? = null

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

        // El guardado de la posición cuelga del servicio, no de la pantalla.
        // La pantalla se destruye al pulsar atrás o cuando el sistema reclama
        // memoria, y desde Android Auto ni siquiera llega a existir; el
        // servicio, en cambio, vive exactamente lo que dura la reproducción,
        // que es justo lo que hay que registrar.
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) arrancarReloj() else {
                    pararReloj()
                    guardarLoQueSuena(forzar = true)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    pararReloj()
                    val duracion = player.duration
                    alcance.launch {
                        GuardadoDeProgreso.volcarEscucha(this@PlaybackService)
                        GuardadoDeProgreso.marcarTerminado(this@PlaybackService, duracion, duracion)
                    }
                }
            }
        })

        session = MediaLibrarySession.Builder(this, player, CallbackDelCoche()).build()
    }

    /* ---------------- Reloj de guardado ---------------- */

    /**
     * Un tic por segundo mientras suena: acumula la escucha real y ofrece la
     * posición al guardián, que decide si toca escribirla y si toca subirla.
     */
    private fun arrancarReloj() {
        if (reloj?.isActive == true) return
        reloj = alcance.launch {
            var ultimoTic = System.currentTimeMillis()
            while (isActive) {
                delay(1_000)
                val ahora = System.currentTimeMillis()
                val delta = (ahora - ultimoTic) / 1000.0
                ultimoTic = ahora
                // Un salto grande significa que el proceso estuvo dormido: no
                // cuenta como escucha.
                if (delta in 0.0..2.0) {
                    GuardadoDeProgreso.sumarEscucha(this@PlaybackService, delta)
                }
                val (posicion, duracion) = posicionYDuracion()
                GuardadoDeProgreso.guardar(this@PlaybackService, posicion, duracion, forzar = false)
                avanzarTemporizadorDeSueno()
            }
        }
    }

    /**
     * El temporizador de sueño también corre aquí: es lo que hace que se
     * cumpla con el móvil en el bolsillo y la pantalla apagada, que es cuando
     * se usa. El reproductor solo se deja tocar desde el hilo principal.
     */
    private suspend fun avanzarTemporizadorDeSueno() {
        val tic = TemporizadorDeSueno.tictac() ?: return
        withContext(Dispatchers.Main) {
            val p = session?.player ?: return@withContext
            p.volume = tic.volumen
            if (tic.pausar) p.pause()
        }
    }

    private fun pararReloj() {
        reloj?.cancel()
        reloj = null
    }

    /** ExoPlayer solo se deja consultar desde el hilo principal. */
    private suspend fun posicionYDuracion(): Pair<Long, Long> = withContext(Dispatchers.Main) {
        val p = session?.player
        (p?.currentPosition ?: -1L) to (p?.duration ?: 0L)
    }

    private fun guardarLoQueSuena(forzar: Boolean) {
        alcance.launch {
            GuardadoDeProgreso.volcarEscucha(this@PlaybackService)
            val (posicion, duracion) = posicionYDuracion()
            GuardadoDeProgreso.guardar(this@PlaybackService, posicion, duracion, forzar)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /**
     * Lo que ve y pide el coche.
     *
     * Los controladores externos reciben los libros sin su URI, por seguridad,
     * así que cuando el coche manda reproducir uno hay que volver a resolverlo
     * aquí a partir de su identificador.
     */
    private inner class CallbackDelCoche : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(Catalogo.raiz(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId != RAIZ_DEL_CATALOGO) {
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            }
            val futuro = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            alcance.launch {
                // Leer la biblioteca toca disco: nunca en el hilo principal.
                val items = Catalogo.libros(this@PlaybackService).map { Catalogo.itemDe(it) }
                futuro.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
            }
            return futuro
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val futuro = SettableFuture.create<LibraryResult<MediaItem>>()
            alcance.launch {
                val libro = Catalogo.porId(this@PlaybackService, mediaId)
                futuro.set(
                    if (libro == null) LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                    else LibraryResult.ofItem(Catalogo.itemDe(libro), null)
                )
            }
            return futuro
        }

        /** Devolver los libros con su URI: sin ella no hay nada que reproducir. */
        override fun onAddMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val futuro = SettableFuture.create<MutableList<MediaItem>>()
            alcance.launch {
                futuro.set(mediaItems.map { resolver(it) }.toMutableList())
            }
            return futuro
        }

        /**
         * Al arrancar desde el coche también se retoma donde se dejó: la
         * posición se resuelve aquí, con la nube incluida, porque la pantalla
         * de la aplicación no interviene.
         */
        override fun onSetMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val futuro = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            alcance.launch {
                val indice = if (startIndex == C.INDEX_UNSET) 0 else startIndex
                val elegido = mediaItems.getOrNull(indice)

                // Lo que manda la propia pantalla ya trae URI y fila de nube
                // resueltas: repetir el trabajo aquí sería una consulta de más
                // y podría pisar la posición que ella ya decidió.
                val yaResuelto = elegido?.localConfiguration != null &&
                    elegido.mediaMetadata.extras?.getString(EXTRA_SYNC_ID) != null
                if (yaResuelto) {
                    futuro.set(
                        MediaSession.MediaItemsWithStartPosition(mediaItems, indice, startPositionMs)
                    )
                    return@launch
                }

                val libro = elegido?.mediaId?.let { Catalogo.porId(this@PlaybackService, it) }
                if (libro == null) {
                    futuro.set(
                        MediaSession.MediaItemsWithStartPosition(
                            mediaItems.map { resolver(it) }, indice, startPositionMs
                        )
                    )
                    return@launch
                }

                val (item, posicion) = Catalogo.prepararParaSonar(this@PlaybackService, libro)
                // Si quien llama ya pidió una posición concreta, manda la suya.
                val desde = if (startPositionMs == C.TIME_UNSET || startPositionMs <= 0) posicion
                else startPositionMs
                // El reproductor arranca ya en `desde`, así que a partir de
                // aquí lo que marque es la escucha real y se puede guardar.
                // Sin esto, arrancar desde el coche registraba la sesión pero
                // no la desbloqueaba nunca, y no se guardaba una sola posición
                // en todo el viaje.
                GuardadoDeProgreso.colocado()
                futuro.set(MediaSession.MediaItemsWithStartPosition(listOf(item), 0, desde))
            }
            return futuro
        }

        private fun resolver(item: MediaItem): MediaItem {
            if (item.localConfiguration != null) return item
            val libro = Catalogo.porId(this@PlaybackService, item.mediaId) ?: return item
            return Catalogo.itemDe(libro)
        }
    }

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

        val posicionMs = player.currentPosition
        val duracionMs = player.duration
        pararReloj()
        player.pause()

        if (GuardadoDeProgreso.libroEnCurso() == null) {
            // Sin sesión registrada no se sabe si la nube se llegó a leer ni
            // por dónde iba el otro dispositivo. Subir aquí a ciegas era el
            // agujero por el que se colaba la posición del móvil por encima de
            // las horas del ordenador. Callarse es la única respuesta segura.
            detener()
            return
        }

        alcance.launch {
            // Si la red no responde, no se retiene el proceso indefinidamente:
            // vale más cerrar limpio que quedarse colgado.
            withTimeoutOrNull(8_000) {
                GuardadoDeProgreso.volcarEscucha(this@PlaybackService)
                GuardadoDeProgreso.guardar(this@PlaybackService, posicionMs, duracionMs, forzar = true)
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
