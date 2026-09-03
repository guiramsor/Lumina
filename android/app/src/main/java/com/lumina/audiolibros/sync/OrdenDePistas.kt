package com.lumina.audiolibros.sync

import java.text.Collator
import java.util.Locale

/**
 * Orden de las pistas dentro de un libro.
 *
 * No es cosmético: la posición que viaja por la nube es **global**, la suma de
 * las duraciones de las pistas anteriores más lo que se lleva de la actual. Si
 * el ordenador y el móvil ordenan las pistas de forma distinta, «hora 6» cae en
 * un sitio en cada uno y la sincronización deja al oyente en mitad de otro
 * capítulo.
 *
 * Por eso el orden es parte del contrato, con los mismos vectores congelados en
 * los tests de las dos plataformas. Ver `src/lib/ordenPistas.js` y docs/SYNC.md.
 *
 * Reglas: los números se comparan como números («Parte 2» antes que «Parte
 * 10»), y las letras sin distinguir mayúsculas ni acentos. Es lo que hace
 * `Intl.Collator('es', { numeric: true, sensitivity: 'base' })` en el
 * escritorio; aquí se compone con un Collator de fuerza primaria más el troceo
 * numérico, que Java no trae de serie.
 */
object OrdenDePistas {

    private val LETRAS: Collator = Collator.getInstance(Locale("es")).apply {
        // PRIMARY ignora mayúsculas y acentos, como `sensitivity: 'base'`.
        strength = Collator.PRIMARY
    }

    /** Compara dos nombres de pista. Devuelve <0, 0 o >0, como todo comparador. */
    fun comparar(a: String?, b: String?): Int {
        val x = trocear(a.orEmpty())
        val y = trocear(b.orEmpty())
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val cmp = when {
                p.numero != null && q.numero != null -> p.numero.compareTo(q.numero)
                // Un trozo numérico y otro de letras: mandan las letras, que es
                // lo que hace el collator del escritorio.
                else -> LETRAS.compare(p.texto, q.texto)
            }
            if (cmp != 0) return cmp
        }
        return x.size - y.size
    }

    val COMPARADOR: Comparator<String> = Comparator { a, b -> comparar(a, b) }

    /** Ordena una lista por el nombre de archivo de cada elemento. */
    fun <T> ordenar(pistas: List<T>, nombreDe: (T) -> String): List<T> =
        pistas.sortedWith { a, b -> comparar(nombreDe(a), nombreDe(b)) }

    private data class Trozo(val texto: String, val numero: java.math.BigInteger?)

    /**
     * Parte el nombre en tramos de dígitos y de no dígitos. Los de dígitos se
     * guardan además como número, para que «2» vaya antes que «10» aunque como
     * texto sea al revés.
     */
    private fun trocear(s: String): List<Trozo> {
        val trozos = mutableListOf<Trozo>()
        var i = 0
        while (i < s.length) {
            val digito = s[i].isDigit()
            var j = i
            while (j < s.length && s[j].isDigit() == digito) j++
            val texto = s.substring(i, j)
            trozos += Trozo(texto, if (digito) java.math.BigInteger(texto) else null)
            i = j
        }
        return trozos
    }
}
