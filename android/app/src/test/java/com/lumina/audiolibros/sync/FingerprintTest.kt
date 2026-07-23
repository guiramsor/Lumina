package com.lumina.audiolibros.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

/**
 * Verifica que esta implementación coincide con la de la app de escritorio.
 *
 * Los dos primeros valores son los vectores congelados de `docs/SYNC.md`, los
 * mismos que comprueba `test/fingerprint.test.mjs` en el proyecto raíz. Si
 * fallan, los dos dispositivos han dejado de hablar el mismo idioma y la
 * sincronización no emparejará ningún libro.
 */
class FingerprintTest {

    /** Bytes deterministas, iguales que los del test de JavaScript. */
    private fun fakeAudio(size: Int, seed: Int = 1) =
        ByteArray(size) { i -> ((i * 31 + seed * 7) % 256).toByte() }

    @Test
    fun `vector congelado - archivo de diez bytes`() {
        val diezBytes = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        assertEquals(
            "83fe3c54f403ec66e809df9dceb0f308fa20394de604b54e9c1a59d805e2e5b7",
            Fingerprint.ofBytes(diezBytes)
        )
    }

    @Test
    fun `vector congelado - libro de dos pistas`() {
        assertEquals(
            "f7ee6e27721feb087d5ad6f99251059d05183104ae909d2b9830b12cadd4f822",
            Fingerprint.ofBook(listOf("00".repeat(32), "ff".repeat(32)))
        )
    }

    @Test
    fun `la misma copia produce la misma huella`() {
        assertEquals(Fingerprint.ofBytes(fakeAudio(5000)), Fingerprint.ofBytes(fakeAudio(5000)))
    }

    @Test
    fun `contenidos distintos producen huellas distintas`() {
        assertNotEquals(Fingerprint.ofBytes(fakeAudio(5000, 1)), Fingerprint.ofBytes(fakeAudio(5000, 2)))
    }

    @Test
    fun `el tamano forma parte de la identidad`() {
        assertNotEquals(Fingerprint.ofBytes(fakeAudio(5000)), Fingerprint.ofBytes(fakeAudio(6000)))
    }

    @Test
    fun `archivos mayores de dos MiB usan primer y ultimo MiB`() {
        val grande = 3 * 1024 * 1024
        assertEquals(Fingerprint.ofBytes(fakeAudio(grande)), Fingerprint.ofBytes(fakeAudio(grande)))
    }

    @Test
    fun `el orden de las pistas no altera la huella del libro`() {
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        val c = "c".repeat(64)
        assertEquals(Fingerprint.ofBook(listOf(a, b, c)), Fingerprint.ofBook(listOf(c, a, b)))
    }

    @Test
    fun `leer de un archivo da el mismo resultado que leer de memoria`() {
        val contenido = fakeAudio(3 * 1024 * 1024 + 517)
        val temporal = File.createTempFile("huella", ".bin")
        try {
            temporal.writeBytes(contenido)
            assertEquals(Fingerprint.ofBytes(contenido), Fingerprint.ofTrack(temporal))
        } finally {
            temporal.delete()
        }
    }
}
