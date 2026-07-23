package com.lumina.audiolibros.library

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

data class Audiolibro(
    val uri: Uri,
    val titulo: String,
    val carpeta: String,
    val duracionMs: Long,
    val tamano: Long,
)

/**
 * Biblioteca leída del propio móvil.
 *
 * Se consulta MediaStore en vez de pedir el archivo con el selector de
 * documentos: para un audiolibro, obligar a rebuscar la carpeta cada vez es
 * mala experiencia. Aquí basta con conceder el permiso una vez.
 */
object AudioLibrary {

    /** Descarta tonos, notificaciones y grabaciones cortas. */
    private const val DURACION_MINIMA_MS = 60_000L

    fun listar(context: Context): List<Audiolibro> {
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

        val resultado = mutableListOf<Audiolibro>()
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
                resultado += Audiolibro(
                    uri = ContentUris.withAppendedId(coleccion, id),
                    titulo = cursor.getString(idxNombre) ?: "Audio",
                    carpeta = if (idxRuta >= 0) cursor.getString(idxRuta).orEmpty() else "",
                    duracionMs = cursor.getLong(idxDuracion),
                    tamano = cursor.getLong(idxTamano),
                )
            }
        }

        // Los audiolibros suelen vivir en su propia carpeta; se muestran
        // primero para no perderlos entre la musica.
        return resultado.sortedWith(
            compareByDescending<Audiolibro> { it.carpeta.contains("audiobook", ignoreCase = true) }
                .thenByDescending { it.duracionMs }
        )
    }
}
