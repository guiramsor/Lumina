import { test } from 'node:test'
import assert from 'node:assert/strict'
import { comienzoDe, aGlobal, desdeGlobal } from '../src/lib/posicionDelLibro.js'

/**
 * La traduccion entre "segundo del libro" y "pista + segundo dentro".
 *
 * Es lo que permite que el ordenador tenga el libro en 12 capitulos y el movil
 * en un archivo unico y aun asi se entiendan: por la nube solo viaja la
 * posicion global. Mismos vectores que PosicionDelLibroTest.kt.
 */

// Tres pistas de 100, 200 y 300. El libro dura 600.
const D = [100, 200, 300]

test('el comienzo de cada pista es la suma de las anteriores', () => {
  assert.equal(comienzoDe(D, 0), 0)
  assert.equal(comienzoDe(D, 1), 100)
  assert.equal(comienzoDe(D, 2), 300)
  assert.equal(comienzoDe(D, 3), 600)
})

test('de pista+dentro a global', () => {
  assert.equal(aGlobal(D, 0, 50), 50)
  assert.equal(aGlobal(D, 1, 50), 150)
  assert.equal(aGlobal(D, 2, 50), 350)
})

test('de global a pista+dentro', () => {
  assert.deepEqual(desdeGlobal(D, 0), { indice: 0, dentro: 0 })
  assert.deepEqual(desdeGlobal(D, 50), { indice: 0, dentro: 50 })
  assert.deepEqual(desdeGlobal(D, 150), { indice: 1, dentro: 50 })
  assert.deepEqual(desdeGlobal(D, 350), { indice: 2, dentro: 50 })
})

test('el limite exacto entre dos pistas cae en la siguiente', () => {
  assert.deepEqual(desdeGlobal(D, 100), { indice: 1, dentro: 0 })
  assert.deepEqual(desdeGlobal(D, 300), { indice: 2, dentro: 0 })
})

test('ir y volver da lo mismo', () => {
  for (const g of [0, 1, 99, 100, 101, 299, 300, 599]) {
    const p = desdeGlobal(D, g)
    assert.equal(aGlobal(D, p.indice, p.dentro), g, `global ${g}`)
  }
})

test('mas alla del final cae en la ultima pista, no se descarta', () => {
  // Pasa cuando el otro dispositivo tiene el libro unos segundos mas largo.
  assert.deepEqual(desdeGlobal(D, 5000), { indice: 2, dentro: 4700 })
})

test('una posicion negativa se trata como el principio', () => {
  assert.deepEqual(desdeGlobal(D, -50), { indice: 0, dentro: 0 })
})

test('un libro de una sola pista: global y local son lo mismo', () => {
  // Es el caso de casi todos los libros, y el que ya funcionaba.
  assert.deepEqual(desdeGlobal([600], 350), { indice: 0, dentro: 350 })
  assert.equal(aGlobal([600], 0, 350), 350)
})

test('sin pistas no revienta', () => {
  assert.deepEqual(desdeGlobal([], 100), { indice: 0, dentro: 0 })
})

test('duraciones desconocidas cuentan como cero', () => {
  assert.deepEqual(desdeGlobal([0, 200], 50), { indice: 1, dentro: 50 })
})
