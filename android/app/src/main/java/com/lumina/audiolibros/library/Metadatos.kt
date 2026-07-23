package com.lumina.audiolibros.library

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File

/** Etiquetas leídas del propio archivo de audio. */
data class Etiquetas(
    val titulo: String?,
    val autor: String?,
    val album: String?,
    val duracionMs: Long,
    val portada: ByteArray?,
)

/**
 * Lectura de etiquetas y portada, equivalente a lo que hace `music-metadata`
 * en la app de escritorio.
 *
 * Las portadas se guardan en la caché de la app la primera vez: volver a
 * abrirlas del archivo original supondría releer un M4B de más de un giga cada
 * vez que se dibuja la biblioteca.
 */
object Metadatos {

    fun leer(context: Context, uri: Uri): Etiquetas {
        val lector = MediaMetadataRetriever()
        return try {
            lector.setDataSource(context, uri)
            Etiquetas(
                titulo = lector.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                autor = lector.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    ?: lector.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = lector.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                duracionMs = lector.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
                portada = lector.embeddedPicture,
            )
        } catch (e: Exception) {
            // Un archivo con etiquetas rotas debe poder escucharse igual.
            Etiquetas(null, null, null, 0L, null)
        } finally {
            runCatching { lector.release() }
        }
    }

    private fun carpetaPortadas(context: Context) =
        File(context.cacheDir, "portadas").apply { mkdirs() }

    fun archivoPortada(context: Context, huella: String) =
        File(carpetaPortadas(context), "$huella.jpg")

    /** Guarda la portada reducida; devuelve la ruta o null si no había imagen. */
    fun guardarPortada(context: Context, huella: String, datos: ByteArray?): String? {
        if (datos == null || datos.isEmpty()) return null
        val destino = archivoPortada(context, huella)
        if (destino.exists() && destino.length() > 0) return destino.absolutePath
        return runCatching {
            // Se reduce a 512 px: en una lista no se aprecia más y evita cargar
            // portadas de varios megas en memoria.
            val opciones = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(datos, 0, datos.size, opciones)
            var muestreo = 1
            while (opciones.outWidth / muestreo > 1024) muestreo *= 2

            val bitmap = BitmapFactory.decodeByteArray(
                datos, 0, datos.size,
                BitmapFactory.Options().apply { inSampleSize = muestreo }
            ) ?: return null

            destino.outputStream().use { salida ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, salida)
            }
            bitmap.recycle()
            destino.absolutePath
        }.getOrNull()
    }

    /**
     * Color dominante de la portada, para teñir la interfaz igual que en el
     * escritorio. Devuelve null si no hay portada o es demasiado apagada.
     */
    fun colorDominante(ruta: String?): Int? {
        if (ruta == null) return null
        return runCatching {
            val bitmap = BitmapFactory.decodeFile(
                ruta,
                BitmapFactory.Options().apply { inSampleSize = 16 }
            ) ?: return null

            var mejorPuntuacion = 0f
            var mejorColor = 0
            val hsv = FloatArray(3)
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val color = bitmap.getPixel(x, y)
                    android.graphics.Color.colorToHSV(color, hsv)
                    // Se busca color vivo pero no quemado, igual que extractPalette.
                    if (hsv[1] < 0.25f || hsv[2] < 0.2f || hsv[2] > 0.95f) continue
                    val puntuacion = hsv[1] * (1f - kotlin.math.abs(hsv[2] - 0.6f))
                    if (puntuacion > mejorPuntuacion) {
                        mejorPuntuacion = puntuacion
                        mejorColor = color
                    }
                }
            }
            bitmap.recycle()
            mejorColor.takeIf { mejorPuntuacion > 0f }
        }.getOrNull()
    }
}
