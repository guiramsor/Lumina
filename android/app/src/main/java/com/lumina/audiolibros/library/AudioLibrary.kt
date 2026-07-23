package com.lumina.audiolibros.library

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.lumina.audiolibros.sync.Fingerprint
import com.lumina.audiolibros.sync.UriSource
import org.json.JSONObject

/** Un audiolibro de la biblioteca del teléfono, ya identificado y etiquetado. */
data class Audiolibro(
    val uri: Uri,
    val bookId: String,
    val trackId: String,
    val titulo: String,
    val autor: String,
    val carpeta: String,
    val duracionMs: Long,
    val tamano: Long,
    val portada: String?,
)

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

    fun listar(context: Context, alProgresar: ((Int, Int) -> Unit)? = null): List<Audiolibro> {
        val crudos = consultarMediaStore(context)
        val cache = context.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
        val resultado = mutableListOf<Audiolibro>()

        crudos.forEachIndexed { indice, crudo ->
            alProgresar?.invoke(indice, crudos.size)
            val clave = crudo.uri.toString()
            val guardado = runCatching { JSONObject(cache.getString(clave, "") ?: "") }.getOrNull()

            // La cache solo vale si el archivo sigue siendo el mismo byte a byte.
            if (guardado != null && guardado.optLong("tamano") == crudo.tamano) {
                resultado += Audiolibro(
                    uri = crudo.uri,
                    bookId = guardado.optString("bookId"),
                    trackId = guardado.optString("trackId"),
                    titulo = guardado.optString("titulo"),
                    autor = guardado.optString("autor"),
                    carpeta = crudo.carpeta,
                    duracionMs = crudo.duracionMs,
                    tamano = crudo.tamano,
                    portada = guardado.optString("portada").takeIf { it.isNotEmpty() },
                )
                return@forEachIndexed
            }

            val trackId = runCatching {
                Fingerprint.ofTrack(UriSource(context.contentResolver, crudo.uri))
            }.getOrNull() ?: return@forEachIndexed
            val bookId = Fingerprint.ofBook(listOf(trackId))

            val etiquetas = Metadatos.leer(context, crudo.uri)
            val portada = Metadatos.guardarPortada(context, trackId, etiquetas.portada)
            val titulo = etiquetas.titulo?.takeIf { it.isNotBlank() }
                ?: crudo.nombre.substringBeforeLast('.')
            val autor = etiquetas.autor?.takeIf { it.isNotBlank() } ?: ""

            cache.edit().putString(
                clave,
                JSONObject()
                    .put("tamano", crudo.tamano)
                    .put("bookId", bookId)
                    .put("trackId", trackId)
                    .put("titulo", titulo)
                    .put("autor", autor)
                    .put("portada", portada ?: "")
                    .toString()
            ).apply()

            resultado += Audiolibro(
                uri = crudo.uri,
                bookId = bookId,
                trackId = trackId,
                titulo = titulo,
                autor = autor,
                carpeta = crudo.carpeta,
                duracionMs = if (crudo.duracionMs > 0) crudo.duracionMs else etiquetas.duracionMs,
                tamano = crudo.tamano,
                portada = portada,
            )
        }
        alProgresar?.invoke(crudos.size, crudos.size)
        return resultado
    }

    private data class Crudo(
        val uri: Uri,
        val nombre: String,
        val carpeta: String,
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
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            columnas += MediaStore.Audio.Media.RELATIVE_PATH
        }

        val salida = mutableListOf<Crudo>()
        context.contentResolver.query(
            coleccion,
            columnas.toTypedArray(),
            "${MediaStore.Audio.Media.DURATION} >= ?",
            arrayOf(DURACION_MINIMA_MS.toString()),
            "${MediaStore.Audio.Media.DISPLAY_NAME} ASC",
        )?.use { cursor ->
            val idxId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val idxNombre = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val idxDuracion = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val idxTamano = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val idxRuta = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            } else -1

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idxId)
                salida += Crudo(
                    uri = ContentUris.withAppendedId(coleccion, id),
                    nombre = cursor.getString(idxNombre) ?: "Audio",
                    carpeta = if (idxRuta >= 0) cursor.getString(idxRuta).orEmpty() else "",
                    duracionMs = cursor.getLong(idxDuracion),
                    tamano = cursor.getLong(idxTamano),
                )
            }
        }
        return salida
    }
}
