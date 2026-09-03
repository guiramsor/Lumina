import { test } from 'node:test'
import assert from 'node:assert/strict'
import { ordenarPistas, compararPistas } from '../src/lib/ordenPistas.js'

/**
 * El orden de las pistas es parte del contrato de sincronizacion.
 *
 * La posicion que viaja por la nube es global: la suma de las duraciones
 * anteriores. Si el movil ordena distinto que el ordenador, "hora 6" cae en
 * otro capitulo. Estos vectores son los mismos que en OrdenDePistasTest.kt.
 */

const ENTRADA = [
  'Parte 10.mp3', 'Parte 2.mp3', 'parte 1.mp3',
  'Capítulo 01.mp3', 'Capitulo 2.mp3',
  '10 - Fin.mp3', '2 - Nudo.mp3', '01 - Intro.mp3',
  'b.mp3', 'A.mp3',
]

const ESPERADO = [
  '01 - Intro.mp3', '2 - Nudo.mp3', '10 - Fin.mp3',
  'A.mp3', 'b.mp3',
  'Capítulo 01.mp3', 'Capitulo 2.mp3',
  'parte 1.mp3', 'Parte 2.mp3', 'Parte 10.mp3',
]

test('vectores congelados: el mismo orden que en Android', () => {
  assert.deepEqual(ordenarPistas(ENTRADA), ESPERADO)
})

test('los numeros se comparan como numeros', () => {
  assert.deepEqual(ordenarPistas(['pista 10.mp3', 'pista 2.mp3']), ['pista 2.mp3', 'pista 10.mp3'])
})

test('no distingue mayusculas ni acentos', () => {
  assert.equal(compararPistas('Capítulo 1', 'capitulo 1'), 0)
})

test('el orden no depende del orden de entrada', () => {
  assert.deepEqual(ordenarPistas(ENTRADA), ordenarPistas([...ENTRADA].reverse()))
})

test('nombres vacios o nulos no revientan', () => {
  assert.deepEqual(ordenarPistas(['a', '']), ['', 'a'])
  assert.equal(compararPistas(null, null), 0)
})
