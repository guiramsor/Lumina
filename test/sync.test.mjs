import { test } from 'node:test'
import assert from 'node:assert/strict'
import { reconciliarLibro, resolveProgress, pushProgress } from '../src/lib/sync.js'

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

test('si la nube tarda demasiado se sigue sin ella, y NO se declara fiable', async () => {
  // Abrir un libro esperaba hasta 30 s a la red antes de que sonara nada.
  // Rendirse es correcto siempre que quede claro que no se ha leido: con
  // ok:false no se subira, asi que no se puede pisar al otro dispositivo.
  const colgada = new Promise(() => {}) // no resuelve jamas
  const db = {
    from: () => ({
      select: () => ({
        eq: () => ({ maybeSingle: () => colgada }),
        gte: () => ({ lte: () => colgada }),
      }),
    }),
  }
  const empezo = Date.now()
  const r = await reconciliarLibro(libro, db)
  const tardo = Date.now() - empezo
  assert.equal(r.ok, false)
  assert.equal(r.syncId, 'fila-compartida')
  assert.ok(tardo < 12000, `tardo ${tardo} ms: el tope no salto`)
})

/* ---------------- Escritura condicional ---------------- */

/**
 * Nube falsa con UNA fila, que aplica la condicion igual que la funcion
 * `guardar_progreso` de supabase/schema.sql. Si la SQL y esto se separan, los
 * tests dejan de significar nada: es la parte fragil de probar contra un doble.
 */
function nubeFalsa(filaInicial = null) {
  const estado = { fila: filaInicial, llamadas: [] }
  return {
    estado,
    async rpc(nombre, p) {
      estado.llamadas.push({ nombre, incondicional: p.p_incondicional })
      if (nombre !== 'guardar_progreso') return { error: new Error('funcion desconocida') }
      const actual = estado.fila
      const nueva = {
        book_id: p.p_book_id,
        global_position: p.p_global_position,
        position: p.p_position,
        duration: p.p_duration ?? actual?.duration ?? null,
        title: p.p_title ?? actual?.title ?? null,
        author: p.p_author ?? actual?.author ?? null,
        finished: p.p_finished,
      }
      if (!actual) {
        estado.fila = nueva
        return { data: true, error: null }
      }
      if (p.p_incondicional || actual.global_position < p.p_global_position) {
        estado.fila = nueva
        return { data: true, error: null }
      }
      return { data: false, error: null } // rechazada por ir por detras
    },
  }
}

const escucha = (global, extra = {}) => ({
  bookId: 'libro',
  position: global,
  globalPosition: global,
  duration: 47631,
  title: 'El Ritmo de la Guerra',
  author: 'Brandon Sanderson',
  updatedAt: Date.now(),
  ...extra,
})

test('una escritura por inercia NO pisa una posicion mas avanzada', async () => {
  // El caso real que se vio: el movil leyo 52452 al abrir, el PC escribio
  // 53828 mientras tanto, y el movil subio 52486 creyendo que iba por delante.
  // Se comio 22 minutos. Ahora lo para el servidor.
  const db = nubeFalsa({ book_id: 'libro', global_position: 53828 })
  assert.equal(await pushProgress(escucha(52486), db), true)
  assert.equal(db.estado.fila.global_position, 53828, 'la nube no debe retroceder')
  assert.equal(db.estado.llamadas[0].incondicional, false)
})

test('una escritura por inercia SI avanza la nube cuando va por delante', async () => {
  const db = nubeFalsa({ book_id: 'libro', global_position: 52000 })
  assert.equal(await pushProgress(escucha(52486), db), true)
  assert.equal(db.estado.fila.global_position, 52486)
})

test('un retroceso elegido por el usuario si pisa la nube', async () => {
  // Te has perdido y vuelves media hora atras: esa es tu posicion.
  const db = nubeFalsa({ book_id: 'libro', global_position: 53828 })
  assert.equal(await pushProgress(escucha(23400, { intencionado: true }), db), true)
  assert.equal(db.estado.fila.global_position, 23400)
  assert.equal(db.estado.llamadas[0].incondicional, true)
})

test('terminar el libro tambien pisa la nube', async () => {
  const db = nubeFalsa({ book_id: 'libro', global_position: 53828 })
  await pushProgress(escucha(0, { finished: true }), db)
  assert.equal(db.estado.fila.finished, true)
  assert.equal(db.estado.llamadas[0].incondicional, true)
})

test('un libro sin fila en la nube la crea', async () => {
  const db = nubeFalsa(null)
  assert.equal(await pushProgress(escucha(120), db), true)
  assert.equal(db.estado.fila.global_position, 120)
})

test('una subida sin etiquetas no borra las que ya habia', async () => {
  // Un cliente que aun no las sabe no debe dejar la fila sin duracion ni autor:
  // sin duracion se vuelve invisible para la busqueda por parecido.
  const db = nubeFalsa({
    book_id: 'libro', global_position: 100, duration: 47631,
    title: 'El Ritmo de la Guerra', author: 'Brandon Sanderson',
  })
  await pushProgress(
    { bookId: 'libro', globalPosition: 200, position: 200, updatedAt: Date.now() },
    db
  )
  assert.equal(db.estado.fila.duration, 47631)
  assert.equal(db.estado.fila.author, 'Brandon Sanderson')
})

test('si falta la funcion en Supabase no se sube nada', async () => {
  // Mejor no subir que subir a ciegas: quedarse sin sincronizar se ve en la
  // barra, pisar el avance del otro dispositivo no se ve hasta que duele.
  const db = { async rpc() { return { error: new Error('PGRST202: schema cache') } } }
  assert.equal(await pushProgress(escucha(200), db), false)
})

test('si falla la red no se sube nada', async () => {
  const db = { async rpc() { return { error: new Error('sin conexion') } } }
  assert.equal(await pushProgress(escucha(200), db), false)
})

/* ---------------- Respaldo cuando falta la funcion ---------------- */

/**
 * Nube falsa SIN `guardar_progreso` instalada, como esta ahora mismo el
 * proyecto de Supabase. La aplicacion tiene que seguir sincronizando: dejarla
 * muerta hasta que alguien ejecute un SQL a mano fue un error de diseno.
 */
function nubeSinFuncion(filaInicial = null) {
  const estado = { fila: filaInicial, ops: [] }
  return {
    estado,
    async rpc() {
      estado.ops.push('rpc')
      return { error: { code: 'PGRST202', message: 'Could not find the function public.guardar_progreso' } }
    },
    from() {
      return {
        select() {
          return {
            eq: () => ({
              maybeSingle: async () => {
                estado.ops.push('leer')
                return { data: estado.fila, error: null }
              },
            }),
          }
        },
        async upsert(fila) {
          estado.ops.push('escribir')
          estado.fila = { ...(estado.fila || {}), ...fila }
          return { error: null }
        },
      }
    },
  }
}

test('sin la funcion instalada la sincronizacion sigue funcionando', async () => {
  const db = nubeSinFuncion(null)
  assert.equal(await pushProgress(escucha(120), db), true)
  assert.equal(db.estado.fila.global_position, 120)
})

test('sin la funcion, tampoco pisa una posicion mas avanzada', async () => {
  // La ventana pasa a ser de milisegundos en vez de la sesion entera, que es
  // lo que importa. Atomico solo cuando la funcion este instalada.
  const db = nubeSinFuncion({ book_id: 'libro', global_position: 53828 })
  assert.equal(await pushProgress(escucha(52486), db), true)
  assert.equal(db.estado.fila.global_position, 53828, 'la nube no debe retroceder')
  assert.ok(db.estado.ops.includes('leer'), 'comprueba antes de escribir')
  assert.ok(!db.estado.ops.includes('escribir'), 'y no escribe si va por detras')
})

test('sin la funcion, un retroceso elegido por el usuario si pisa', async () => {
  const db = nubeSinFuncion({ book_id: 'libro', global_position: 53828 })
  await pushProgress(escucha(23400, { intencionado: true }), db)
  assert.equal(db.estado.fila.global_position, 23400)
  assert.ok(!db.estado.ops.includes('leer'), 'ni se molesta en comprobar')
})

test('sin la funcion, si falla la lectura no se escribe nada', async () => {
  const db = nubeSinFuncion({ book_id: 'libro', global_position: 100 })
  db.from = () => ({
    select: () => ({ eq: () => ({ maybeSingle: async () => ({ data: null, error: new Error('sin conexion') }) }) }),
  })
  assert.equal(await pushProgress(escucha(200), db), false)
})

/* ---------------- resolveProgress ---------------- */

test('una fila sin global no se toma por el principio del libro', () => {
  // Misma spec que EmparejarLibros.posicionAbsoluta en el movil.
  const local = { globalTime: 100 }
  const remota = { global_position: 0, position: 400 }
  assert.equal(resolveProgress(local, remota).winner, 'remote')
})
