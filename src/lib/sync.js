/**
 * Sincronización de la posición de escucha entre dispositivos.
 *
 * Solo viaja la posición: los audios nunca salen del dispositivo. Los libros se
 * emparejan por su huella digital (lib/fingerprint.js), así que el mismo
 * archivo copiado al móvil se reconoce como el mismo libro.
 *
 * Todo es opcional: sin credenciales configuradas la app funciona exactamente
 * igual que antes, en local.
 */
import { createClient } from '@supabase/supabase-js'
import {
  agruparMismoLibro,
  elegirCanonica,
  escrituraIncondicional,
  filaMasAvanzada,
  ganaLaRemota,
  posicionAbsoluta,
  toleranciaDuracion,
} from './emparejar.js'

const URL = import.meta.env?.VITE_SUPABASE_URL || ''
const KEY = import.meta.env?.VITE_SUPABASE_ANON_KEY || ''

let client = null
let sesionActiva = false

/** ¿Hay proyecto de Supabase configurado en el build? */
export function isConfigured() {
  return Boolean(URL && KEY)
}

/**
 * ¿Hay sesión iniciada ahora mismo? Es una lectura síncrona para que la
 * interfaz pueda decidir si mostrar el estado de sincronización sin esperar.
 */
export function haySesion() {
  return sesionActiva
}

function getClient() {
  if (!isConfigured()) return null
  if (!client) {
    client = createClient(URL, KEY, {
      auth: { persistSession: true, autoRefreshToken: true },
    })
    // Mantener la bandera al día: al restaurar la sesión guardada, al entrar
    // y al salir.
    client.auth.getSession().then(({ data }) => {
      sesionActiva = Boolean(data?.session)
    })
    client.auth.onAuthStateChange((_evento, sesion) => {
      sesionActiva = Boolean(sesion)
    })
  }
  return client
}

/** Nombre con el que este dispositivo se identifica en las filas de progreso. */
function deviceName() {
  return typeof navigator !== 'undefined' && navigator.userAgent.includes('Electron')
    ? 'PC (Lumina escritorio)'
    : 'Navegador'
}

/* ---------- Sesión ---------- */

export async function signIn(email, password) {
  const db = getClient()
  if (!db) throw new Error('La sincronización no está configurada en esta compilación.')
  const { data, error } = await db.auth.signInWithPassword({ email, password })
  if (error) throw new Error(traducirError(error.message))
  sesionActiva = true
  return data.user
}

export async function signOut() {
  await getClient()?.auth.signOut()
  sesionActiva = false
}

export async function currentUser() {
  const db = getClient()
  if (!db) return null
  const { data } = await db.auth.getUser()
  return data?.user || null
}

function traducirError(mensaje) {
  if (/Invalid login credentials/i.test(mensaje)) return 'Correo o contraseña incorrectos.'
  if (/Email not confirmed/i.test(mensaje)) return 'Falta confirmar el correo de la cuenta.'
  if (/fetch/i.test(mensaje)) return 'Sin conexión con el servidor de sincronización.'
  return mensaje
}

/* ---------- Progreso ---------- */

/**
 * Descarga la posición guardada en la nube para un libro.
 * Devuelve null si no hay nada, si no hay sesión o si falla la red: la
 * sincronización nunca debe impedir escuchar.
 */
const COLUMNAS =
  'book_id, track_id, position, global_position, duration, finished, updated_at, device, title, author'

/**
 * Lectura de la fila de un libro por su identificador de sincronización.
 *
 * Devuelve `{ fila, ok }`. Separar las dos cosas no es un lujo: «no hay fila»
 * y «no he podido leer» llevan a decisiones opuestas. Confundirlos hace que un
 * corte de red se interprete como libro nuevo, y entonces se sube a ciegas por
 * encima del avance del otro dispositivo. Es la regla 3 de docs/SYNC.md.
 */
async function leerFilaPorId(db, syncId) {
  if (!db || !syncId) return { fila: null, ok: true }
  try {
    const { data, error } = await db.from('progress').select(COLUMNAS).eq('book_id', syncId).maybeSingle()
    if (error) throw error
    return { fila: data || null, ok: true }
  } catch (err) {
    console.warn('No se pudo leer el progreso remoto', err)
    return { fila: null, ok: false }
  }
}

/** Lectura directa de la fila de un libro por su identificador de sincronización. */
export async function pullProgress(syncId) {
  const { fila } = await leerFilaPorId(getClient(), syncId)
  return fila
}

/**
 * Averigua en qué fila de la nube vive este libro, y deja solo una.
 *
 * La huella identifica el archivo, que es distinto en cada dispositivo si las
 * copias no son idénticas. El `syncId` identifica la fila compartida: en
 * cuanto un dispositivo reconoce el libro del otro, adopta su identificador y
 * los dos escriben en el mismo sitio. Sin esto, cada uno seguiría escribiendo
 * en su propia fila y no volverían a encontrarse nunca.
 *
 * Devuelve `{ syncId, progreso, fusionadas, ok }`. `ok` en false significa que
 * no se ha podido consultar la nube, que NO es lo mismo que no haya fila:
 * confundirlos lleva a subir a ciegas y borrar el avance del otro dispositivo.
 *
 * Nunca lanza: quedarse sin sincronizar es aceptable, no poder escuchar no.
 */
/**
 * Lo máximo que se hace esperar al usuario antes de que empiece a sonar.
 *
 * Rendirse a tiempo es correcto: la lectura cuenta como fallida, y una lectura
 * fallida ya prohíbe subir, así que no se puede pisar el avance del móvil.
 * Escuchar nunca debe depender de la nube.
 */
const ESPERA_MAXIMA_MS = 8000

function conTope(promesa, alRendirse) {
  return new Promise((resolve) => {
    const reloj = setTimeout(() => resolve(alRendirse()), ESPERA_MAXIMA_MS)
    promesa.then(
      (v) => {
        clearTimeout(reloj)
        resolve(v)
      },
      () => {
        clearTimeout(reloj)
        resolve(alRendirse())
      }
    )
  })
}

export function reconciliarLibro(libro, db = getClient()) {
  const porDefecto = { syncId: libro.syncId || libro.fingerprint, progreso: null, fusionadas: 0, ok: true }
  return conTope(reconciliarSinTope(libro, db), () => {
    console.warn(`La nube no respondió en ${ESPERA_MAXIMA_MS} ms: se escucha en local`)
    return { ...porDefecto, ok: false }
  })
}

async function reconciliarSinTope({ fingerprint, syncId, duracion, titulo, autor }, db) {
  const porDefecto = { syncId: syncId || fingerprint, progreso: null, fusionadas: 0, ok: true }
  if (!db || !fingerprint) return porDefecto

  // Se arrastra si alguna lectura ha fallado por el camino. Salir con `ok` en
  // true después de una lectura fallida es peor que salir con false: hace creer
  // al reproductor que la nube está vacía y le da permiso para subir encima.
  let fiable = true

  try {
    // Camino rápido: el libro ya sabe dónde vive.
    if (syncId) {
      const { fila, ok } = await leerFilaPorId(db, syncId)
      if (!ok) fiable = false
      if (fila) return { syncId, progreso: fila, fusionadas: 0, ok: true }
    }

    // Sin duración no hay segunda vía: la búsqueda por parecido necesita
    // justamente eso. Si además la lectura por id falló, no sabemos nada.
    if (!duracion) return { ...porDefecto, ok: fiable }

    const margen = toleranciaDuracion(duracion)
    const { data, error } = await db
      .from('progress')
      .select(COLUMNAS)
      .gte('duration', duracion - margen)
      .lte('duration', duracion + margen)
    if (error) throw error

    const idsPropios = [fingerprint, syncId].filter(Boolean)
    const grupo = agruparMismoLibro(data || [], { idsPropios, duracion, titulo, autor })
    // No haber encontrado grupo solo significa «este libro no está en la nube»
    // si todo lo que había que leer se ha leído. Una fila con `duration` nula,
    // por ejemplo, no la devuelve esta consulta y solo la ve el camino por id.
    if (!grupo.length) return { ...porDefecto, ok: fiable }

    const canonica = elegirCanonica(grupo)
    const avanzada = filaMasAvanzada(grupo)
    const sobrantes = grupo.filter((f) => f.book_id !== canonica.book_id)

    // Varias filas para el mismo libro: se conserva la posición más avanzada
    // en la canónica y se retiran las demás, que solo servirían para volver a
    // dividir la sincronización más adelante.
    if (sobrantes.length) {
      if (avanzada.book_id !== canonica.book_id) {
        await db.from('progress').upsert(
          { ...avanzada, book_id: canonica.book_id, updated_at: new Date().toISOString() },
          { onConflict: 'user_id,book_id' }
        )
      }
      await db
        .from('progress')
        .delete()
        .in('book_id', sobrantes.map((f) => f.book_id))
      console.info(`Fusionadas ${sobrantes.length + 1} filas del mismo libro en ${canonica.book_id}`)
    }

    const progreso =
      avanzada.book_id === canonica.book_id ? avanzada : { ...avanzada, book_id: canonica.book_id }
    return { syncId: canonica.book_id, progreso, fusionadas: sobrantes.length, ok: true }
  } catch (err) {
    console.warn('No se pudo reconciliar el libro con la nube', err)
    return { ...porDefecto, ok: false }
  }
}

/**
 * Sube la posición actual. `updatedAt` es el momento real de la escucha, no el
 * de la subida, para que una sincronización tardía no pise una escucha
 * posterior hecha en el otro dispositivo.
 *
 * La escritura es **condicional**: el servidor solo acepta la nueva posición si
 * va por delante de la que ya hay. Comprobarlo aquí no bastaba, porque la
 * referencia local se queda vieja en cuanto el otro dispositivo escribe algo
 * (ver `escrituraIncondicional`), y entre leer y escribir cabe una sesión de
 * escucha entera.
 *
 * La excepción sigue siendo la de siempre: si la posición la ha elegido el
 * usuario, o el libro se ha terminado, manda aunque vaya hacia atrás.
 */
export async function pushProgress(entry, db = getClient()) {
  if (!db || !entry?.bookId) return false
  const terminado = Boolean(entry.finished)

  try {
    const { error } = await db.rpc('guardar_progreso', {
      p_book_id: entry.bookId,
      p_global_position: entry.globalPosition ?? 0,
      p_position: entry.position ?? 0,
      p_track_id: entry.trackId ?? null,
      p_duration: entry.duration ?? null,
      p_finished: terminado,
      p_title: entry.title ?? null,
      p_author: entry.author ?? null,
      p_device: deviceName(),
      p_updated_at: new Date(entry.updatedAt || Date.now()).toISOString(),
      p_incondicional: escrituraIncondicional({
        terminado,
        intencionado: entry.intencionado,
      }),
    })
    if (error) throw error
    // `false` significa que el servidor la ha rechazado por ir por detrás, y
    // eso es una respuesta correcta, no un fallo: la nube ya tiene algo mejor.
    return true
  } catch (err) {
    if (/guardar_progreso|PGRST202|schema cache/i.test(err?.message || '')) {
      console.error(
        'Falta la funcion guardar_progreso en Supabase. Ejecuta supabase/schema.sql ' +
          'en el SQL Editor: sin ella no se sube nada, para no pisar el otro dispositivo.'
      )
    } else {
      console.warn('No se pudo subir el progreso', err)
    }
    return false
  }
}

/**
 * Decide qué posición vale cuando hay una local y una remota.
 *
 * Gana la escucha **más avanzada**, no la más reciente: así ningún dispositivo
 * puede hacer retroceder lo escuchado en el otro, que es el error que de
 * verdad duele. Ver docs/SYNC.md.
 */
export function resolveProgress(local, remote) {
  if (!remote) return { winner: 'local', progress: local }
  if (!local) return { winner: 'remote', progress: remote }
  const posLocal = local.globalTime ?? local.time ?? 0
  const posRemota = posicionAbsoluta(remote.global_position, remote.position)
  if (ganaLaRemota(posLocal, posRemota)) return { winner: 'remote', progress: remote }
  return { winner: 'local', progress: local }
}
