package com.lumina.audiolibros.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mismos casos que `test/emparejar.test.mjs` en el escritorio. Si las dos
 * plataformas no deciden igual, un dispositivo emparejaría libros que el otro
 * no, o haría retroceder la escucha del otro. Ver docs/SYNC.md.
 */
class EmparejarLibrosTest {

    private data class Fila(val duracion: Double?, val titulo: String?, val autor: String?)

    private fun elegir(filas: List<Fila>, duracion: Double, titulo: String?, autor: String?) =
        EmparejarLibros.elegirCoincidencia(
            filas, duracion, titulo, autor,
            duracionDe = { it.duracion }, tituloDe = { it.titulo }, autorDe = { it.autor },
        )

    /* ---------------- Normalización ---------------- */

    @Test
    fun `la normalizacion ignora mayusculas, acentos y puntuacion`() {
        assertEquals("el ritmo de la guerra", EmparejarLibros.normalizarTexto("El Ritmo de la Guerra"))
        assertEquals("el ritmo de la guerra", EmparejarLibros.normalizarTexto("EL RITMO DE LA GUERRA"))
        assertEquals("el ritmo de la guerra", EmparejarLibros.normalizarTexto("  El  Rítmo, de la Guerra!  "))
    }

    @Test
    fun `titulos escritos de formas distintas dan la misma clave`() {
        assertEquals(
            EmparejarLibros.claveBlanda("Trenza del Mar Esmeralda", "Brandon Sanderson"),
            EmparejarLibros.claveBlanda("trenza  del  mar  esmeralda", "BRANDON SANDERSON"),
        )
    }

    @Test
    fun `libros distintos no comparten clave`() {
        assertNotEquals(
            EmparejarLibros.claveBlanda("Elantris", "Sanderson"),
            EmparejarLibros.claveBlanda("Nacidos de la bruma", "Sanderson"),
        )
    }

    /* ---------------- Emparejamiento por duración ---------------- */

    @Test
    fun `empareja una unica fila de duracion parecida`() {
        val r = elegir(listOf(Fila(47631.0, "X", "Y")), 47628.0, "otro titulo", "")
        assertEquals(47631.0, r?.duracion)
    }

    @Test
    fun `no empareja si la duracion se aleja demasiado`() {
        assertNull(elegir(listOf(Fila(47631.0, "X", "Y")), 40000.0, "X", "Y"))
    }

    @Test
    fun `con varias duraciones parecidas desempata el titulo`() {
        val filas = listOf(Fila(47631.0, "Elantris", "Sanderson"), Fila(47640.0, "Trenza", "Sanderson"))
        assertEquals("Trenza", elegir(filas, 47635.0, "TRENZA", "sanderson")?.titulo)
    }

    @Test
    fun `ante la duda no empareja nada`() {
        val filas = listOf(Fila(47631.0, "Elantris", "S"), Fila(47640.0, "Trenza", "S"))
        assertNull(elegir(filas, 47635.0, "Otro", "S"))
    }

    @Test
    fun `la tolerancia crece con la duracion pero nunca baja de diez segundos`() {
        assertEquals(120.0, elegir(listOf(Fila(120.0, "a", "")), 128.0, "a", "")?.duracion)
        assertNull(elegir(listOf(Fila(120.0, "a", "")), 145.0, "a", ""))
        assertEquals(47631.0, elegir(listOf(Fila(47631.0, "a", "")), 47700.0, "a", "")?.duracion)
    }

    /* ---------------- Resolución de posiciones ---------------- */

    @Test
    fun `gana la escucha mas avanzada, no la mas reciente`() {
        assertTrue(EmparejarLibros.ganaLaRemota(100.0, 5000.0))
        assertFalse(EmparejarLibros.ganaLaRemota(5000.0, 100.0))
    }

    @Test
    fun `una diferencia de segundos no hace saltar la reproduccion`() {
        assertFalse(EmparejarLibros.ganaLaRemota(1000.0, 1003.0))
        assertTrue(EmparejarLibros.ganaLaRemota(1000.0, 1006.0))
    }

    @Test
    fun `no se pisa una posicion remota mas avanzada`() {
        assertFalse(EmparejarLibros.debeSubir(100.0, 5000.0))
        assertTrue(EmparejarLibros.debeSubir(6000.0, 5000.0))
    }

    @Test
    fun `reiniciar un libro si se propaga`() {
        assertTrue(EmparejarLibros.debeSubir(12.0, 5000.0))
        assertTrue(EmparejarLibros.debeSubir(100.0, 5000.0, terminado = true))
    }

    @Test
    fun `sin nada remoto conocido siempre se sube`() {
        assertTrue(EmparejarLibros.debeSubir(42.0, null))
    }
}
