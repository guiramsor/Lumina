package com.lumina.audiolibros.sync

/**
 * Convertir entre «milisegundo del libro» y «pista + milisegundo dentro de ella».
 *
 * Lo que viaja por la nube es la posición **global**: el tiempo desde el
 * principio del libro. Cada dispositivo la traduce a su propio reparto de
 * pistas, que puede no ser el mismo —el ordenador puede tener el libro partido
 * en capítulos y el móvil en un solo archivo, o al revés—.
 *
 * Contrato compartido con `src/lib/posicionDelLibro.js`. Ver docs/SYNC.md.
 */
object PosicionDelLibro {

    /** Suma de las duraciones de las pistas anteriores a `indice`. */
    fun comienzoDe(duraciones: List<Long>, indice: Int): Long {
        var acc = 0L
        for (i in 0 until minOf(indice, duraciones.size)) acc += duraciones[i].coerceAtLeast(0)
        return acc
    }

    /** Milisegundo del libro estando en `dentro` de la pista `indice`. */
    fun aGlobal(duraciones: List<Long>, indice: Int, dentro: Long): Long =
        comienzoDe(duraciones, indice) + dentro.coerceAtLeast(0)

    /** Pista y milisegundo dentro de ella. */
    data class Punto(val indice: Int, val dentro: Long)

    /**
     * Pista y milisegundo que corresponden a un tiempo del libro.
     *
     * Una posición más allá del final cae en la última pista, no se descarta:
     * es lo que pasa cuando el otro dispositivo tiene el libro con unos
     * segundos más por otra codificación.
     */
    fun desdeGlobal(duraciones: List<Long>, global: Long): Punto {
        if (duraciones.isEmpty()) return Punto(0, 0)
        val objetivo = global.coerceAtLeast(0)
        var acc = 0L
        for (i in duraciones.indices) {
            val dur = duraciones[i].coerceAtLeast(0)
            if (objetivo < acc + dur || i == duraciones.size - 1) {
                return Punto(i, (objetivo - acc).coerceAtLeast(0))
            }
            acc += dur
        }
        return Punto(0, 0)
    }
}
