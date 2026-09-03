package com.lumina.audiolibros.library

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.lumina.audiolibros.sync.Fingerprint
import com.lumina.audiolibros.sync.OrdenDePistas
import com.lumina.audiolibros.sync.UriSource
import org.json.JSONObject

/** Un archivo de audio dentro de un libro. */
data class Pista(
    val uri: Uri,
    val trackId: String,
    val duracionMs: Long,
    val nombre: String,
    val tamano: Long,
)

/**
 * Un audiolibro de la biblioteca del teléfono, ya identificado y etiquetado.
 *
 * Puede tener **varias pistas**. Antes cada archivo era un libro, y eso rompía
 * la sincronización en silencio: la huella de un libro es el hash de las
 * huellas de todas sus pistas, así que un libro que en el ordenador son doce
 * capítulos nunca podía coincidir con doce libros de una pista en el móvil. Ni
 * por huella ni por duración —trece horas contra una—. No daba ningún error:
 * cada dispositivo llevaba su cuenta y no volvían a encontrarse.
 */
data class Audiolibro(
    val bookId: String,
    val titulo: String,
    val autor: String,
    val carpeta: String,
    val pistas: List<Pista>,
    val portada: String?,
) {
    /** Duración del libro entero, que es lo que viaja por la nube. */
    val duracionMs: Long get() = pistas.sumOf { it.duracionMs }

    val duraciones: List<Long> get() = pistas.map { it.duracionMs }

    /** La primera pista. Para los sitios a los que les basta con una. */
    val uri: Uri get() = pistas.first().uri
    val trackId: String get() = pistas.first().trackId
}

/**
 * Biblioteca leída del propio móvil.
 *
 * Se consulta MediaStore en vez de pedir el archivo con el selector de
 * documentos: para un audiolibro, obligar a rebuscar la carpeta cada vez es
 * mala experiencia. Basta con conceder el permiso una vez.
 *
 * Calcular huella y leer etiquetas de cada archivo cuesta, así que el
 * resultado se cachea por URI y tamaño: si el archivo no ha cambiado, no se
 * vuelve a tocar.
 */
object AudioLibrary {

    private const val CACHE = "lumina_escaneo"

    /** Descarta tonos, notificaciones y grabaciones cortas. */
    private const val DURACION_MINIMA_MS = 60_000L

    /**
     * Carpetas que delatan una biblioteca de audiolibros. Si existe alguna, se
     * muestran solo sus archivos: de lo contrario cualquier canción de más de
     * un minuto acaba en la biblioteca, y en un teléfono con música eso es la
     * mayoría de lo que aparece.
     */
    private val CARPETA_DE_LIBROS = Regex("audiobook|audiolibro|audio_?libro", RegexOption.IGNORE_CASE)

    fun pareceCarpetaDeLibros(carpeta: String): Boolean = CARPETA_DE_LIBROS.containsMatchIn(carpeta)

    /**
     * ¿Hay alguna carpeta de audiolibros en este teléfono? Si no la hay, el
     * filtro se desactiva solo: más vale enseñarlo todo que una lista vacía.
     */
    fun hayCarpetaDeLibros(context: Context): Boolean =
        consultarMediaStore(context).any { pareceCarpetaDeLibros(it.carpeta) }

    /**
     * Con qué criterio dos archivos son el mismo libro.
     *
     * El mismo que en el escritorio (`groupKey` de importBooks.js): la carpeta
     * y la etiqueta de álbum. Archivos en carpetas distintas, o con álbumes
     * distintos dentro de la misma carpeta, son libros distintos; una carpeta
     * de capítulos que comparten álbum —o que no tienen ninguno— se juntan.
     */
    internal fun claveDeAgrupacion(carpeta: String, album: String?): String {
        val sinBarra = carpeta.trimEnd('/')
        return sinBarra + "|" + album.orEmpty().trim().lowercase()
    }

    fun listar(
        context: Context,
        soloCarpetaDeLibros: Boolean = true,
        alProgresar: ((Int, Int) -> Unit)? = null,
    ): List<Audiolibro> {
        val todos = consultarMediaStore(context)
        val enCarpeta = todos.filter { pareceCarpetaDeLibros(it.carpeta) }
        val crudos = if (soloCarpetaDeLibros && enCarpeta.isNotEmpty()) enCarpeta else todos
        val cache = context.getSharedPreferences(CACHE, Context.MODE_PRIVATE)

        // 1. Identificar cada archivo (huella, etiquetas, portada), con caché.
        val identificados = mutableListOf<ArchivoIdentificado>()
        crudos.forEachIndexed { indice, crudo ->
            alProgresar?.invoke(indice, crudos.size)
            identificar(context, cache, crudo)?.let { identificados += it }
        }
        alProgresar?.invoke(crudos.size, crudos.size)

        // 2. Agrupar en libros, con el mismo criterio que el escritorio.
        return identificados
            .groupBy { claveDeAgrupacion(it.crudo.carpeta, it.crudo.album) }
            .map { (_, delGrupo) -> montarLibro(delGrupo) }
            .sortedBy { it.titulo.lowercase() }
    }

    private data class ArchivoIdentificado(
        val crudo: Crudo,
        val trackId: String,
        val titulo: String,
        val autor: String,
        val portada: String?,
    )

    /** Un archivo con su huella y sus etiquetas, de la caché o recalculado. */
    private fun identificar(
        context: Context,
        cache: SharedPreferences,
        crudo: Crudo,
    ): ArchivoIdentificado? {
        val clave = crudo.uri.toString()
        val guardado = runCatching { JSONObject(cache.getString(clave, "") ?: "") }.getOrNull()

        // La cache solo vale si el archivo sigue siendo el mismo byte a byte.
        if (guardado != null && guardado.optLong("tamano") == crudo.tamano) {
            val trackId = guardado.optString("trackId")
            if (trackId.isEmpty()) return null
            var portada = guardado.optString("portada").takeIf { it.isNotEmpty() }

            // Las caratulas viven en la cache de la aplicacion, que Android
            // vacia cuando necesita espacio y que desaparece al reinstalar. Sin
            // esta comprobacion el libro se quedaba sin portada **para
            // siempre**: el acierto de cache impedia volver a leerla del audio.
            if (portada != null && !java.io.File(portada).exists()) {
                portada = Metadatos.guardarPortada(
                    context, trackId, Metadatos.leer(context, crudo.uri).portada
                )
                cache.edit().putString(
                    clave,
                    JSONObject(guardado.toString()).put("portada", portada ?: "").toString()
                ).apply()
            }
            return ArchivoIdentificado(
                crudo, trackId,
                guardado.optString("titulo"), guardado.optString("autor"), portada,
            )
        }

        val trackId = runCatching {
            Fingerprint.ofTrack(UriSource(context.contentResolver, crudo.uri))
        }.getOrNull() ?: return null

        val etiquetas = Metadatos.leer(context, crudo.uri)
        val portada = Metadatos.guardarPortada(context, trackId, etiquetas.portada)
        val titulo = etiquetas.titulo?.takeIf { it.isNotBlank() }
            ?: crudo.nombre.substringBeforeLast('.')
        val autor = etiquetas.autor?.takeIf { it.isNotBlank() } ?: ""

        cache.edit().putString(
            clave,
            JSONObject()
                .put("tamano", crudo.tamano)
                .put("trackId", trackId)
                .put("titulo", titulo)
                .put("autor", autor)
                .put("portada", portada ?: "")
                .toString()
        ).apply()

        return ArchivoIdentificado(crudo, trackId, titulo, autor, portada)
    }

    /**
     * Monta el libro a partir de sus archivos.
     *
     * El orden de las pistas es el del contrato (`OrdenDePistas`), no el de
     * MediaStore: de él depende la posición global, y si los dos dispositivos
     * no ordenan igual, «hora 6» cae en capítulos distintos.
     */
    private fun montarLibro(delGrupo: List<ArchivoIdentificado>): Audiolibro {
        val enOrden = OrdenDePistas.ordenar(delGrupo) { it.crudo.nombre }
        val primero = enOrden.first()

        val pistas = enOrden.map {
            Pista(
                uri = it.crudo.uri,
                trackId = it.trackId,
                duracionMs = it.crudo.duracionMs.coerceAtLeast(0),
                nombre = it.crudo.nombre,
                tamano = it.crudo.tamano,
            )
        }

        // Mismo criterio que `deriveTitle` del escritorio: manda el album; si
        // solo hay un archivo, su titulo; si no, el nombre de la carpeta.
        val album = primero.crudo.album?.trim()?.takeIf { it.isNotEmpty() }
        val nombreDeCarpeta = primero.crudo.carpeta.trimEnd('/').substringAfterLast('/')
        val titulo = album
            ?: if (enOrden.size == 1) primero.titulo
            else nombreDeCarpeta.ifEmpty { primero.titulo }

        val autor = enOrden.map { it.autor }.firstOrNull { it.isNotBlank() } ?: ""

        return Audiolibro(
            bookId = Fingerprint.ofBook(pistas.map { it.trackId }),
            titulo = titulo,
            autor = autor,
            carpeta = primero.crudo.carpeta,
            pistas = pistas,
            portada = enOrden.firstNotNullOfOrNull { it.portada },
        )
    }

    private data class Crudo(
        val uri: Uri,
        val nombre: String,
        val carpeta: String,
        val album: String?,
        val duracionMs: Long,
        val tamano: Long,
    )

    private fun consultarMediaStore(context: Context): List<Crudo> {
        val coleccion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val columnas = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            // El album agrupa los capitulos de un mismo libro, igual que en el
            // escritorio. Sale de la misma etiqueta y viene gratis en la
            // consulta, sin tener que abrir el archivo.
            MediaStore.Audio.Media.ALBUM,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            columnas += MediaStore.Audio.Media.RELATIVE_PATH
        }

        val salida = mutableListOf<Crudo>()
        context.contentResolver.query(
            coleccion,
            columnas.toTypedArray(),
            MediaStore.Audio.Media.DURATION + " >= ?",
            arrayOf(DURACION_MINIMA_MS.toString()),
            MediaStore.Audio.Media.DISPLAY_NAME + " ASC",
        )?.use { cursor ->
            val idxId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val idxNombre = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val idxDuracion = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val idxTamano = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val idxAlbum = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            val idxRuta = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            } else -1

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idxId)
                salida += Crudo(
                    uri = ContentUris.withAppendedId(coleccion, id),
                    nombre = cursor.getString(idxNombre) ?: "Audio",
                    carpeta = if (idxRuta >= 0) cursor.getString(idxRuta).orEmpty() else "",
                    album = if (idxAlbum >= 0) cursor.getString(idxAlbum) else null,
                    duracionMs = cursor.getLong(idxDuracion),
                    tamano = cursor.getLong(idxTamano),
                )
            }
        }
        return salida
    }
}
