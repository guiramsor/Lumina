package com.lumina.audiolibros.sync

import android.content.ContentResolver
import android.net.Uri
import java.io.FileInputStream

/**
 * Origen de bytes sobre una URI de Android, para poder calcular la huella de
 * los audios sin copiarlos ni cargarlos enteros en memoria.
 *
 * Se abre el descriptor y se salta al desplazamiento pedido, así que de un
 * archivo de 1,7 GB solo se leen los 2 MiB que exige el algoritmo.
 */
class UriSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : Fingerprint.ByteSource {

    override val size: Long by lazy {
        resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
    }

    override fun read(offset: Long, length: Int): ByteArray {
        val buffer = ByteArray(length)
        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                stream.channel.position(offset)
                var leidos = 0
                while (leidos < length) {
                    val n = stream.read(buffer, leidos, length - leidos)
                    if (n < 0) break
                    leidos += n
                }
            }
        }
        return buffer
    }
}
