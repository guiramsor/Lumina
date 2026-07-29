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

    /**
     * Tamaño real del archivo. **Falla en vez de devolver cero.**
     *
     * `openFileDescriptor` devuelve null cuando no se puede abrir, y `statSize`
     * vale −1 cuando el proveedor no sabe el tamaño. Dejar pasar cualquiera de
     * los dos era peor que rendirse: la huella de una pista es
     * `SHA-256(primer MiB ‖ último MiB ‖ tamaño)`, así que con tamaño 0 no se
     * lee ni un byte y **todos los archivos ilegibles salían con la misma
     * huella**. Y una huella compartida es una fila de nube compartida: dos
     * libros distintos pisándose la posición el uno al otro.
     *
     * Quien llama ya trata el fallo como «este archivo no entra en la
     * biblioteca», que es la respuesta correcta.
     */
    override val size: Long by lazy {
        val medido = resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            ?: throw java.io.IOException("No se pudo abrir $uri para medirlo")
        if (medido <= 0) throw java.io.IOException("Tamaño desconocido o vacío en $uri: $medido")
        medido
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
