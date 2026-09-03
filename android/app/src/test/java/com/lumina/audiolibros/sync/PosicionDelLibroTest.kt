package com.lumina.audiolibros.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La traduccion entre "milisegundo del libro" y "pista + milisegundo dentro".
 *
 * Es lo que permite que el ordenador tenga el libro en 12 capitulos y el movil
 * en un archivo unico y aun asi se entiendan: por la nube solo viaja la
 * posicion global. Mismos vectores que test/posicionDelLibro.test.mjs.
 */
class PosicionDelLibroTest {

    // Tres pistas de 100, 200 y 300. El libro dura 600.
    private val d = listOf(100L, 200L, 300L)

    @Test
    fun `el comienzo de cada pista es la suma de las anteriores`() {
        assertEquals(0L, PosicionDelLibro.comienzoDe(d, 0))
        assertEquals(100L, PosicionDelLibro.comienzoDe(d, 1))
        assertEquals(300L, PosicionDelLibro.comienzoDe(d, 2))
        assertEquals(600L, PosicionDelLibro.comienzoDe(d, 3))
    }

    @Test
    fun `de pista mas dentro a global`() {
        assertEquals(50L, PosicionDelLibro.aGlobal(d, 0, 50))
        assertEquals(150L, PosicionDelLibro.aGlobal(d, 1, 50))
        assertEquals(350L, PosicionDelLibro.aGlobal(d, 2, 50))
    }

    @Test
    fun `de global a pista mas dentro`() {
        assertEquals(PosicionDelLibro.Punto(0, 0), PosicionDelLibro.desdeGlobal(d, 0))
        assertEquals(PosicionDelLibro.Punto(0, 50), PosicionDelLibro.desdeGlobal(d, 50))
        assertEquals(PosicionDelLibro.Punto(1, 50), PosicionDelLibro.desdeGlobal(d, 150))
        assertEquals(PosicionDelLibro.Punto(2, 50), PosicionDelLibro.desdeGlobal(d, 350))
    }

    @Test
    fun `el limite exacto entre dos pistas cae en la siguiente`() {
        assertEquals(PosicionDelLibro.Punto(1, 0), PosicionDelLibro.desdeGlobal(d, 100))
        assertEquals(PosicionDelLibro.Punto(2, 0), PosicionDelLibro.desdeGlobal(d, 300))
    }

    @Test
    fun `ir y volver da lo mismo`() {
        for (g in listOf(0L, 1L, 99L, 100L, 101L, 299L, 300L, 599L)) {
            val p = PosicionDelLibro.desdeGlobal(d, g)
            assertEquals("global $g", g, PosicionDelLibro.aGlobal(d, p.indice, p.dentro))
        }
    }

    @Test
    fun `mas alla del final cae en la ultima pista`() {
        assertEquals(PosicionDelLibro.Punto(2, 4700), PosicionDelLibro.desdeGlobal(d, 5000))
    }

    @Test
    fun `una posicion negativa se trata como el principio`() {
        assertEquals(PosicionDelLibro.Punto(0, 0), PosicionDelLibro.desdeGlobal(d, -50))
    }

    @Test
    fun `un libro de una sola pista global y local son lo mismo`() {
        assertEquals(PosicionDelLibro.Punto(0, 350), PosicionDelLibro.desdeGlobal(listOf(600L), 350))
        assertEquals(350L, PosicionDelLibro.aGlobal(listOf(600L), 0, 350))
    }

    @Test
    fun `sin pistas no revienta`() {
        assertEquals(PosicionDelLibro.Punto(0, 0), PosicionDelLibro.desdeGlobal(emptyList(), 100))
    }

    @Test
    fun `duraciones desconocidas cuentan como cero`() {
        assertEquals(PosicionDelLibro.Punto(1, 50), PosicionDelLibro.desdeGlobal(listOf(0L, 200L), 50))
    }
}
