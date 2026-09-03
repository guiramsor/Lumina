/**
 * Orden de las pistas dentro de un libro.
 *
 * No es cosmético: la posición que viaja por la nube es **global**, la suma de
 * las duraciones de las pistas anteriores más lo que llevas de la actual. Si el
 * ordenador y el móvil ordenan las pistas de forma distinta, «hora 6» cae en un
 * sitio en cada uno y la sincronización te deja en mitad de otro capítulo.
 *
 * Por eso el orden es parte del contrato, con vectores congelados en los tests
 * de las dos plataformas. Ver `OrdenDePistas.kt` y docs/SYNC.md.
 *
 * Reglas: los números se comparan como números («Parte 2» antes que
 * «Parte 10»), y las letras sin distinguir mayúsculas ni acentos.
 */

const COLLATOR = new Intl.Collator('es', { numeric: true, sensitivity: 'base' })

/** Compara dos nombres de pista. Devuelve <0, 0 o >0, como todo comparador. */
export function compararPistas(a, b) {
  return COLLATOR.compare(a || '', b || '')
}

/** Ordena una lista de pistas por su nombre de archivo. */
export function ordenarPistas(pistas, nombreDe = (p) => p) {
  return [...pistas].sort((x, y) => compararPistas(nombreDe(x), nombreDe(y)))
}
