import { test } from 'node:test'
import assert from 'node:assert/strict'
import { reconciliarLibro, resolveProgress } from '../src/lib/sync.js'

/**
 * La bandera `ok` de reconciliarLibro.
 *
 * Es la que decide si el reproductor se atreve a subir. Si vale true cuando en
 * realidad no se ha podido leer la nube, el equipo se cree que el libro es
 * nuevo y sube encima del avance del otro dispositivo. Ese es exactamente el
 * fallo que estos tests fijan, y por eso se comprueban tambien los caminos en
 * los que la funcion sale antes de tiempo.
 */

/** Cliente de Supabase falso: solo lo justo de la cadena que se usa. */
function clienteFalso({ porId, porDuracion }) {
  const llamadas = { upserts: [], borrados: [] }
  const cliente = {
    llamadas,
    from() {
      return {
        select() {
          return {
            // Camino rapido: .eq(...).maybeSingle()
            eq: () => ({ maybeSingle: async () => porId }),
            // Camino por duracion: .gte(...).lte(...) es esperable directamente
            gte: () => ({ lte: () => Promise.resolve(porDuracion) }),
          }
        },
        upsert: async (fila) => {
          llamadas.upserts.push(fila)
          return { error: null }
        },
        delete: () => ({
          in: async (_columna, ids) => {
            llamadas.borrados.push(...ids)
            return { error: null }
          },
        }),
      }
    },
  }
  return cliente
}

const FALLO = { data: null, error: new Error('sin conexion') }
const VACIO = { data: null, error: null }

const libro = {
  fingerprint: 'huella-del-pc',
  syncId: 'fila-compartida',
  duracion: 47631,
  titulo: 'El Ritmo de la Guerra',
  autor: 'Brandon Sanderson',
}

test('una lectura correcta sin fila remota es fiable', async () => {
  const db = clienteFalso({ porId: VACIO, porDuracion: { data: [], error: null } })
  const r = await reconciliarLibro(libro, db)
  assert.equal(r.ok, true)
  assert.equal(r.progreso, null)
})

test('si falla la lectura por id y no hay duracion, NO se declara fiable', async () => {
  // Sin duracion no hay segunda via, asi que lo unico que sabemos es que la
  // lectura fallo. Antes se devolvia ok:true y el reproductor subia a ciegas.
  const db = clienteFalso({ porId: FALLO, porDuracion: { data: [], error: null } })
  const r = await reconciliarLibro({ ...libro, duracion: 0 }, db)
  assert.equal(r.ok, false)
})

test('si falla la lectura por id y la busqueda por duracion no encuentra nada, NO se declara fiable', async () => {
  // Es el caso de una fila con `duration` nula: la consulta por rango no la
  // devuelve nunca, asi que "no hay grupo" no prueba que el libro sea nuevo.
  const db = clienteFalso({ porId: FALLO, porDuracion: { data: [], error: null } })
  const r = await reconciliarLibro(libro, db)
  assert.equal(r.ok, false)
})

test('si falla la lectura por id pero la duracion si encuentra el libro, es fiable', async () => {
  const fila = {
    book_id: 'fila-compartida',
    duration: 47631,
    global_position: 25593,
    position: 25593,
    title: 'El Ritmo de la Guerra',
    author: 'Brandon Sanderson',
  }
  const db = clienteFalso({ porId: FALLO, porDuracion: { data: [fila], error: null } })
  const r = await reconciliarLibro(libro, db)
  assert.equal(r.ok, true)
  assert.equal(r.progreso.global_position, 25593)
})

test('si falla la busqueda por duracion, NO se declara fiable', async () => {
  const db = clienteFalso({ porId: VACIO, porDuracion: FALLO })
  const r = await reconciliarLibro(libro, db)
  assert.equal(r.ok, false)
})

test('sin cliente configurado se sale en fiable: no hay nada que pisar', async () => {
  const r = await reconciliarLibro(libro, null)
  assert.equal(r.ok, true)
  assert.equal(r.syncId, 'fila-compartida')
})

test('se adopta el identificador de la fila ajena y se retiran las sobrantes', async () => {
  const ajena = {
    book_id: 'aaa-huella-del-movil',
    duration: 47631,
    global_position: 25593,
    position: 25593,
    title: 'El Ritmo de la Guerra',
    author: 'Brandon Sanderson',
  }
  const propia = { ...ajena, book_id: 'zzz-huella-del-pc', global_position: 100, position: 100 }
  const db = clienteFalso({ porId: VACIO, porDuracion: { data: [ajena, propia], error: null } })
  const r = await reconciliarLibro({ ...libro, fingerprint: 'zzz-huella-del-pc', syncId: null }, db)
  // Gana el identificador menor, y con el la posicion mas avanzada del grupo.
  assert.equal(r.syncId, 'aaa-huella-del-movil')
  assert.equal(r.progreso.global_position, 25593)
  assert.deepEqual(db.llamadas.borrados, ['zzz-huella-del-pc'])
})

/* ---------------- resolveProgress ---------------- */

test('una fila sin global no se toma por el principio del libro', () => {
  // Misma spec que EmparejarLibros.posicionAbsoluta en el movil.
  const local = { globalTime: 100 }
  const remota = { global_position: 0, position: 400 }
  assert.equal(resolveProgress(local, remota).winner, 'remote')
})
