import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  normalizarTexto,
  claveBlanda,
  elegirCoincidencia,
  ganaLaRemota,
  debeSubir,
} from '../src/lib/emparejar.js'

/* ---------------- Normalización ---------------- */

test('la normalizacion ignora mayusculas, acentos y puntuacion', () => {
  assert.equal(normalizarTexto('El Ritmo de la Guerra'), 'el ritmo de la guerra')
  assert.equal(normalizarTexto('EL RITMO DE LA GUERRA'), 'el ritmo de la guerra')
  assert.equal(normalizarTexto('  El  Rítmo, de la Guerra!  '), 'el ritmo de la guerra')
})

test('titulos escritos de formas distintas dan la misma clave', () => {
  assert.equal(
    claveBlanda('Trenza del Mar Esmeralda', 'Brandon Sanderson'),
    claveBlanda('trenza  del  mar  esmeralda', 'BRANDON SANDERSON')
  )
})

test('libros distintos no comparten clave', () => {
  assert.notEqual(claveBlanda('Elantris', 'Sanderson'), claveBlanda('Nacidos de la bruma', 'Sanderson'))
})

/* ---------------- Emparejamiento por duración ---------------- */

const fila = (duracion, title = 'X', author = 'Y') => ({ duration: duracion, title, author })

test('empareja una unica fila de duracion parecida', () => {
  const r = elegirCoincidencia([fila(47631)], { duracion: 47628, titulo: 'otro titulo', autor: '' })
  assert.equal(r.duration, 47631)
})

test('no empareja si la duracion se aleja demasiado', () => {
  assert.equal(elegirCoincidencia([fila(47631)], { duracion: 40000, titulo: 'X', autor: 'Y' }), null)
})

test('con varias duraciones parecidas desempata el titulo', () => {
  const filas = [fila(47631, 'Elantris', 'Sanderson'), fila(47640, 'Trenza', 'Sanderson')]
  const r = elegirCoincidencia(filas, { duracion: 47635, titulo: 'TRENZA', autor: 'sanderson' })
  assert.equal(r.title, 'Trenza')
})

test('ante la duda no empareja nada', () => {
  // Dos candidatas y ningun titulo coincide: mejor no sincronizar que mezclar
  // dos libros distintos.
  const filas = [fila(47631, 'Elantris', 'S'), fila(47640, 'Trenza', 'S')]
  assert.equal(elegirCoincidencia(filas, { duracion: 47635, titulo: 'Otro', autor: 'S' }), null)
})

test('la tolerancia crece con la duracion pero nunca baja de diez segundos', () => {
  // Un libro corto admite 10 s; uno de 13 horas, unos 95.
  assert.equal(elegirCoincidencia([fila(120)], { duracion: 128, titulo: 'a', autor: '' })?.duration, 120)
  assert.equal(elegirCoincidencia([fila(120)], { duracion: 145, titulo: 'a', autor: '' }), null)
  assert.equal(elegirCoincidencia([fila(47631)], { duracion: 47700, titulo: 'a', autor: '' })?.duration, 47631)
})

/* ---------------- Resolución de posiciones ---------------- */

test('gana la escucha mas avanzada, no la mas reciente', () => {
  assert.equal(ganaLaRemota(100, 5000), true)
  assert.equal(ganaLaRemota(5000, 100), false)
})

test('una diferencia de segundos no hace saltar la reproduccion', () => {
  assert.equal(ganaLaRemota(1000, 1003), false)
  assert.equal(ganaLaRemota(1000, 1006), true)
})

test('no se pisa una posicion remota mas avanzada', () => {
  // El PC va por 100 s y la nube por 5000: subir borraria el avance del movil.
  assert.equal(debeSubir(100, 5000), false)
  assert.equal(debeSubir(6000, 5000), true)
})

test('reiniciar un libro si se propaga', () => {
  assert.equal(debeSubir(12, 5000), true)
  assert.equal(debeSubir(100, 5000, { terminado: true }), true)
})

test('sin nada remoto conocido siempre se sube', () => {
  assert.equal(debeSubir(42, null), true)
})
