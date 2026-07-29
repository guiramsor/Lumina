package com.lumina.audiolibros.player

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.lumina.audiolibros.data.AlmacenLocal
import com.lumina.audiolibros.library.AudioLibrary
import com.lumina.audiolibros.library.Audiolibro
import com.lumina.audiolibros.library.Metadatos
import com.lumina.audiolibros.sync.EmparejarLibros
import com.lumina.audiolibros.sync.GuardadoDeProgreso
import com.lumina.audiolibros.sync.SupabaseSync

/** Identificador de la carpeta raíz que ve el coche al abrir Lumina. */
const val RAIZ_DEL_CATALOGO = "lumina_raiz"

/**
 * El catálogo de audiolibros tal y como lo ven la pantalla y el coche.
 *
 * Android Auto no habla con la interfaz: pide el catálogo al servicio y le
 * manda a reproducir un identificador. Todo lo que necesita para eso vive
 * aquí, para que un libro se vea y suene igual empezando desde el móvil o
 * desde el coche.
 */
object Catalogo {

    @Volatile private var cache: List<Audiolibro> = emptyList()

    /** Lista de libros. Se recalcula solo si aún no hay nada o si se fuerza. */
    fun libros(context: Context, refrescar: Boolean = false): List<Audiolibro> {
        if (refrescar || cache.isEmpty()) {
            cache = runCatching {
                AudioLibrary.listar(context, AlmacenLocal.soloCarpetaDeLibros(context))
            }.getOrDefault(emptyList())
        }
        return cache
    }

    fun porId(context: Context, mediaId: String): Audiolibro? =
        libros(context).firstOrNull { it.bookId == mediaId }
            ?: libros(context, refrescar = true).firstOrNull { it.bookId == mediaId }

    /**
     * El libro convertido en algo reproducible.
     *
     * La portada viaja como bytes dentro de los metadatos: es lo que hace que
     * se vea en la notificación del móvil y en la pantalla del coche. El
     * identificador de la fila de la nube va en los extras porque el servicio
     * guarda la posición cuando la pantalla ya no existe.
     */
    fun itemDe(libro: Audiolibro, syncId: String? = null): MediaItem =
        MediaItem.Builder()
            .setUri(libro.uri)
            .setMediaId(libro.bookId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(libro.titulo)
                    .setArtist(libro.autor.ifEmpty { "Audiolibro" })
                    .setAlbumTitle(libro.titulo)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                    .setArtworkData(
                        Metadatos.portadaParaLaSesion(libro.portada),
                        MediaMetadata.PICTURE_TYPE_FRONT_COVER,
                    )
                    .setExtras(
                        Bundle().apply {
                            putString(EXTRA_TRACK_ID, libro.trackId)
                            if (syncId != null) putString(EXTRA_SYNC_ID, syncId)
                        }
                    )
                    .build()
            )
            .build()

    /** La carpeta raíz que el coche muestra al entrar en la aplicación. */
    fun raiz(): MediaItem =
        MediaItem.Builder()
            .setMediaId(RAIZ_DEL_CATALOGO)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Lumina")
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                    .build()
            )
            .build()

    /**
     * Prepara un libro para sonar: resuelve dónde retomarlo y con qué
     * identificador de nube, igual que hace la pantalla al abrirlo.
     *
     * Llamar desde un hilo de fondo: consulta la nube.
     */
    suspend fun prepararParaSonar(context: Context, libro: Audiolibro): Pair<MediaItem, Long> {
        val local = AlmacenLocal.progreso(context, libro.bookId)
        val guardado = AlmacenLocal.syncId(context, libro.bookId)
        val lectura = SupabaseSync.reconciliar(
            context,
            fingerprint = libro.bookId,
            syncId = guardado,
            duracionSegundos = libro.duracionMs / 1000.0,
            titulo = libro.titulo,
            autor = libro.autor,
        )
        val fiable = lectura.isSuccess
        // Si la lectura falló no se sabe nada nuevo, así que se conserva el
        // identificador que ya se había adoptado. Guardar aquí la huella
        // pisaría la fila compartida y devolvería al móvil a escribir en la
        // suya, deshaciendo la adopción por un simple corte de red.
        val syncId = lectura.getOrNull()?.syncId ?: guardado ?: libro.bookId
        if (fiable) AlmacenLocal.guardarSyncId(context, libro.bookId, syncId)

        val remoto = lectura.getOrNull()?.progreso
        var posicion = local?.posicionMs ?: 0L
        if (SupabaseSync.ganaLaRemota(remoto, posicion / 1000.0) && remoto != null) {
            posicion = (
                EmparejarLibros.posicionAbsoluta(
                    remoto.posicionGlobalSegundos, remoto.posicionSegundos
                ) * 1000
                ).toLong()
        }
        val terminado = local?.terminado == true || remoto?.terminado == true
        // Un libro terminado se reabre desde el principio.
        if (terminado) posicion = 0L

        // Arrancar desde el coche tiene que dejar registrada la sesión igual
        // que abrirlo desde la pantalla: es lo que permite que el servicio
        // guarde la posición mientras se conduce, sin interfaz ninguna.
        GuardadoDeProgreso.abrir(
            bookId = libro.bookId,
            syncId = syncId,
            trackId = libro.trackId,
            titulo = libro.titulo,
            autor = libro.autor,
            duracionMs = libro.duracionMs,
            lecturaFiable = fiable,
            posicionRemota = remoto?.let {
                EmparejarLibros.posicionAbsoluta(it.posicionGlobalSegundos, it.posicionSegundos)
            },
            velocidad = local?.velocidad ?: AlmacenLocal.velocidadPorDefecto(context),
        )
        // Volver a empezar un libro terminado es una decisión, no inercia.
        if (terminado) GuardadoDeProgreso.marcarIntencionada()

        val tope = if (libro.duracionMs > 0) libro.duracionMs else Long.MAX_VALUE
        return itemDe(libro, syncId) to posicion.coerceIn(0L, tope)
    }
}
