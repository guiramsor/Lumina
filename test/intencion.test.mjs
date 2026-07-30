import { test } from 'node:test'
import assert from 'node:assert/strict'
import { crearIntencion } from '../src/lib/intencion.js'

/**
 * «Esta posicion la ha elegido el usuario.»
 *
 * Es lo unico que permite subir una posicion anterior a la que hay en la nube,
 * porque el servidor rechaza cualquier retroceso que no venga marcado. Antes se
 * ponia al saltar y solo se bajaba al abrir otro libro, asi que un unico toque
 * a «+30 s» dejaba todas las subidas de las horas siguientes saltandose la
 * comprobacion. Como los botones de salto son los que mas se usan, la
 * proteccion quedaba apagada casi siempre.
 *
 * Misma spec que Intencion.kt.
 */

test('un libro recien abierto no tiene intencion pendiente', () => {
  const i = crearIntencion()
  assert.equal(i.activa, false)
})

test('saltar la marca', () => {
  const i = crearIntencion()
  i.marcar()
  assert.equal(i.activa, true)
})

test('la intencion se CONSUME con la subida que la lleva a la nube', () => {
  // Esto es lo que faltaba: seguia activa el resto de la sesion.
  const i = crearIntencion()
  i.marcar()
  const sello = i.sello
  i.cumplida(sello)
  assert.equal(i.activa, false, 'ya esta guardada: lo que venga despues es inercia')
})

test('una subida fallida no consume la intencion', () => {
  // Si no, un retroceso se perderia por un corte de red: el reintento iria
  // condicionado y el servidor lo rechazaria por ir hacia atras.
  const i = crearIntencion()
  i.marcar()
  const sello = i.sello
  // No se llama a cumplida() porque la subida no llego.
  assert.equal(i.activa, true)
  i.cumplida(sello)
  assert.equal(i.activa, false, 'y se consume cuando por fin llega')
})

test('saltar otra vez mientras la subida esta en vuelo NO pierde la intencion nueva', () => {
  // La carrera: sale la subida de la primera intencion, el usuario salta otra
  // vez, y al volver la respuesta no se puede bajar la bandera porque la que
  // hay ahora es otra y todavia no ha llegado a ninguna parte.
  const i = crearIntencion()
  i.marcar()
  const selloEnVuelo = i.sello

  i.marcar() // el usuario salta de nuevo
  i.cumplida(selloEnVuelo) // responde la subida vieja

  assert.equal(i.activa, true, 'la intencion nueva sigue viva')
  i.cumplida(i.sello)
  assert.equal(i.activa, false)
})

test('varios saltos seguidos cuentan como una sola intencion pendiente', () => {
  const i = crearIntencion()
  i.marcar()
  i.marcar()
  i.marcar()
  assert.equal(i.activa, true)
  i.cumplida(i.sello)
  assert.equal(i.activa, false, 'la ultima subida las salda todas')
})

test('abrir otro libro empieza sin intencion', () => {
  const i = crearIntencion()
  i.marcar()
  i.reiniciar()
  assert.equal(i.activa, false)
})

test('escenario completo: un salto no desactiva la proteccion el resto de la tarde', () => {
  const i = crearIntencion()

  // Pulsas +30 s una vez.
  i.marcar()
  assert.equal(i.activa, true, 'esa subida puede ir hacia atras: la elegiste tu')
  i.cumplida(i.sello)

  // Y sigues escuchando tres horas. Ninguna de esas subidas deberia saltarse
  // la comprobacion del servidor.
  for (let subida = 0; subida < 360; subida++) {
    assert.equal(i.activa, false, `la subida ${subida} debe ir condicionada`)
    i.cumplida(i.sello)
  }
})
