package com.lumina.audiolibros.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El orden de las pistas es parte del contrato de sincronizacion.
 *
 * La posicion que viaja por la nube es global: la suma de las duraciones
 * anteriores. Si el movil ordena distinto que el ordenador, "hora 6" cae en
 * otro capitulo. Estos vectores son los mismos que en test/ordenPistas.test.mjs
 * y salen de ejecutar el collator del escritorio.
 */
class OrdenDePistasTest {

    /** Vectores congelados: identicos a los del escritorio. */
    private val entrada = listOf(
        "Parte 10.mp3", "Parte 2.mp3", "parte 1.mp3",
        "Capítulo 01.mp3", "Capitulo 2.mp3",
        "10 - Fin.mp3", "2 - Nudo.mp3", "01 - Intro.mp3",
        "b.mp3", "A.mp3",
    )

    private val esperado = listOf(
        "01 - Intro.mp3", "2 - Nudo.mp3", "10 - Fin.mp3",
        "A.mp3", "b.mp3",
        "Capítulo 01.mp3", "Capitulo 2.mp3",
        "parte 1.mp3", "Parte 2.mp3", "Parte 10.mp3",
    )

    @Test
    fun `el orden coincide con el del escritorio`() {
        assertEquals(esperado, OrdenDePistas.ordenar(entrada) { it })
    }

    @Test
    fun `los numeros se comparan como numeros`() {
        assertEquals(
            listOf("pista 2.mp3", "pista 10.mp3"),
            OrdenDePistas.ordenar(listOf("pista 10.mp3", "pista 2.mp3")) { it },
        )
    }

    @Test
    fun `no distingue mayusculas ni acentos`() {
        assertEquals(0, OrdenDePistas.comparar("Capítulo 1", "capitulo 1"))
    }

    @Test
    fun `el orden no depende del orden de entrada`() {
        assertEquals(
            OrdenDePistas.ordenar(entrada) { it },
            OrdenDePistas.ordenar(entrada.reversed()) { it },
        )
    }

    @Test
    fun `nombres vacios o nulos no revientan`() {
        assertEquals(listOf("", "a"), OrdenDePistas.ordenar(listOf("a", "")) { it })
        assertEquals(0, OrdenDePistas.comparar(null, null))
    }
}
