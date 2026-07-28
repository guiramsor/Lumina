import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  normalizarTexto,
  claveBlanda,
  elegirCoincidencia,
  agruparMismoLibro,
  elegirCanonica,
  filaMasAvanzada,
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

/* ---------------- Agrupar el mismo libro ---------------- */

const conId = (id, duracion, position = 0, title = 'X', author = 'Y') => ({
  book_id: id,
  duration: duracion,
  position,
  global_position: position,
  title,
  author,
})

const contexto = { duracion: 47631, titulo: 'X', autor: 'Y' }

test('sin filas remotas el grupo esta vacio', () => {
  assert.deepEqual(agruparMismoLibro([], { idsPropios: ['mia'], ...contexto }), [])
})

test('agrupa la fila ajena que es el mismo libro', () => {
  const grupo = agruparMismoLibro([conId('ajena', 47630)], { idsPropios: ['mia'], ...contexto })
  assert.deepEqual(grupo.map((f) => f.book_id), ['ajena'])
})

test('agrupa la propia y la ajena cuando existen las dos', () => {
  const filas = [conId('mia', 47631), conId('ajena', 47630)]
  const grupo = agruparMismoLibro(filas, { idsPropios: ['mia'], ...contexto })
  assert.deepEqual(grupo.map((f) => f.book_id).sort(), ['ajena', 'mia'])
})

test('no agrupa filas de otra duracion', () => {
  const filas = [conId('mia', 47631), conId('otroLibro', 12000)]
  const grupo = agruparMismoLibro(filas, { idsPropios: ['mia'], ...contexto })
  assert.deepEqual(grupo.map((f) => f.book_id), ['mia'])
})

test('ante dos ajenas ambiguas no se agrupa ninguna', () => {
  // Dos libros distintos de duracion parecida y ningun titulo coincide: no
  // se fusiona nada, que es peor que no sincronizar.
  const filas = [
    conId('mia', 47631),
    conId('ajena1', 47630, 0, 'Elantris', 'S'),
    conId('ajena2', 47635, 0, 'Trenza', 'S'),
  ]
  const grupo = agruparMismoLibro(filas, { idsPropios: ['mia'], duracion: 47631, titulo: 'Otro', autor: 'S' })
  assert.deepEqual(grupo.map((f) => f.book_id), ['mia'])
})

test('ante dos ajenas desempata el titulo', () => {
  const filas = [
    conId('ajena1', 47630, 0, 'Elantris', 'S'),
    conId('ajena2', 47635, 0, 'Trenza', 'S'),
  ]
  const grupo = agruparMismoLibro(filas, { idsPropios: [], duracion: 47631, titulo: 'TRENZA', autor: 's' })
  assert.deepEqual(grupo.map((f) => f.book_id), ['ajena2'])
})

test('la fila canonica es la misma se mire desde donde se mire', () => {
  const a = conId('aaa', 47631)
  const b = conId('zzz', 47631)
  // Los dos dispositivos ven el grupo en distinto orden y deben coincidir.
  assert.equal(elegirCanonica([a, b]).book_id, 'aaa')
  assert.equal(elegirCanonica([b, a]).book_id, 'aaa')
})

test('al fusionar se conserva la posicion mas avanzada', () => {
  const grupo = [conId('aaa', 47631, 100), conId('zzz', 47631, 25593)]
  assert.equal(filaMasAvanzada(grupo).position, 25593)
  // Y no tiene por que ser la canonica: por eso se guardan por separado.
  assert.equal(elegirCanonica(grupo).book_id, 'aaa')
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

test('un retroceso deliberado siempre se propaga', () => {
  // Vas por 7 h, te pierdes y retrocedes a 6 h 30: esa es tu posicion, aunque
  // la nube tenga una mas avanzada. Sin esto, reabrir te devolvia a las 7 h.
  assert.equal(debeSubir(23400, 25593, { intencionado: true }), true)
  // Y sin intencion, el mismo caso sigue protegido.
  assert.equal(debeSubir(23400, 25593), false)
})

test('reiniciar un libro si se propaga', () => {
  assert.equal(debeSubir(12, 5000, { intencionado: true }), true)
  assert.equal(debeSubir(100, 5000, { terminado: true }), true)
})

test('estar al principio no basta para pisar la nube', () => {
  // Antes cualquier posicion menor de 30 s se tomaba por un reinicio; abrir un
  // libro apenas empezado y salir borraba el avance del otro dispositivo.
  assert.equal(debeSubir(0, 5000), false)
  assert.equal(debeSubir(12, 5000), false)
})

test('sin nada remoto conocido siempre se sube', () => {
  assert.equal(debeSubir(42, null), true)
})
