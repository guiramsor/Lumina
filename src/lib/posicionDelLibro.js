/**
 * Convertir entre «segundo del libro» y «pista + segundo dentro de ella».
 *
 * Lo que viaja por la nube es la posición **global**: los segundos desde el
 * principio del libro. Cada dispositivo la traduce a su propio reparto de
 * pistas, que puede no ser el mismo —el ordenador puede tener el libro partido
 * en capítulos y el móvil en un solo archivo, o al revés—.
 *
 * Las unidades dan igual mientras sean las mismas en ambos lados: el
 * escritorio trabaja en segundos y Android en milisegundos.
 *
 * Contrato compartido con `PosicionDelLibro.kt`. Ver docs/SYNC.md.
 */

/** Suma de las duraciones de las pistas anteriores a `indice`. */
export function comienzoDe(duraciones, indice) {
  let acc = 0
  for (let i = 0; i < indice && i < duraciones.length; i++) acc += duraciones[i] || 0
  return acc
}

/** Segundo del libro en el que estás, estando en `dentro` de la pista `indice`. */
export function aGlobal(duraciones, indice, dentro) {
  return comienzoDe(duraciones, indice) + (dentro || 0)
}

/**
 * Pista y segundo dentro de ella que corresponden a un segundo del libro.
 *
 * Una posición más allá del final cae en la última pista, no se descarta: es
 * lo que pasa cuando el otro dispositivo tiene el libro con unos segundos más
 * por otra codificación.
 */
export function desdeGlobal(duraciones, global) {
  if (!duraciones.length) return { indice: 0, dentro: 0 }
  const objetivo = Math.max(0, global || 0)
  let acc = 0
  for (let i = 0; i < duraciones.length; i++) {
    const dur = duraciones[i] || 0
    if (objetivo < acc + dur || i === duraciones.length - 1) {
      return { indice: i, dentro: Math.max(0, objetivo - acc) }
    }
    acc += dur
  }
  return { indice: 0, dentro: 0 }
}
