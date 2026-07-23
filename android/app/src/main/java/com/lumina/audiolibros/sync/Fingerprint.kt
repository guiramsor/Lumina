package com.lumina.audiolibros.sync

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Huella digital de audiolibros: identidad estable y portable.
 *
 * Contrato compartido con la app de escritorio. Cualquier cambio aquí debe
 * hacerse también en `src/lib/fingerprint.js` del proyecto raíz, o los dos
 * dispositivos dejarán de reconocer los mismos libros. La especificación
 * completa y los vectores de referencia están en `docs/SYNC.md`.
 *
 *   huella de pista = SHA-256( primer MiB || último MiB || tamaño en ASCII )
 *   huella de libro = SHA-256( huellas de sus pistas, ordenadas y unidas por \n )
 *
 * Solo se leen 2 MiB por archivo: un M4B de 1,76 GB se resuelve en ~15 ms.
 */
object Fingerprint {

    private const val CHUNK = 1024 * 1024

    /**
     * Origen de bytes con acceso aleatorio. Se abstrae porque en Android los
     * audios pueden venir de un `File` o de un `content://` del selector de
     * documentos, y el algoritmo debe ser el mismo en ambos casos.
     */
    interface ByteSource {
        val size: Long
        fun read(offset: Long, length: Int): ByteArray
    }

    class FileSource(private val file: File) : ByteSource {
        override val size: Long get() = file.length()
        override fun read(offset: Long, length: Int): ByteArray {
            val buffer = ByteArray(length)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                raf.readFully(buffer)
            }
            return buffer
        }
    }

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(data)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** Huella de una pista. Devuelve 64 caracteres hexadecimales en minúsculas. */
    fun ofTrack(source: ByteSource): String {
        val size = source.size
        val headLength = minOf(CHUNK.toLong(), size).toInt()
        val head = if (headLength > 0) source.read(0, headLength) else ByteArray(0)

        // El último MiB solo se añade si no solapa con el primero.
        val tailStart = maxOf(headLength.toLong(), size - CHUNK)
        val tail = if (tailStart < size) source.read(tailStart, (size - tailStart).toInt()) else ByteArray(0)

        val sizeBytes = size.toString().toByteArray(Charsets.US_ASCII)
        return sha256Hex(head + tail + sizeBytes)
    }

    fun ofTrack(file: File): String = ofTrack(FileSource(file))

    /** Huella de una pista ya cargada en memoria (usado sobre todo en tests). */
    fun ofBytes(content: ByteArray): String = ofTrack(object : ByteSource {
        override val size: Long get() = content.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray =
            content.copyOfRange(offset.toInt(), offset.toInt() + length)
    })

    /**
     * Huella de un libro a partir de las de sus pistas. Se ordenan
     * alfabéticamente para que la identidad no dependa del criterio de
     * ordenación de cada plataforma.
     */
    fun ofBook(trackFingerprints: List<String>): String =
        sha256Hex(trackFingerprints.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
}
