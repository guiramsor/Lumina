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
  filaMasAvanzada,
  ganaLaRemota,
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

/** Lectura directa de la fila de un libro por su identificador de sincronización. */
export async function pullProgress(syncId) {
  const db = getClient()
  if (!db || !syncId) return null
  try {
    const { data, error } = await db.from('progress').select(COLUMNAS).eq('book_id', syncId).maybeSingle()
    if (error) throw error
    return data || null
  } catch (err) {
    console.warn('No se pudo leer el progreso remoto', err)
    return null
  }
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
export async function reconciliarLibro({ fingerprint, syncId, duracion, titulo, autor }) {
  const db = getClient()
  const porDefecto = { syncId: syncId || fingerprint, progreso: null, fusionadas: 0, ok: true }
  if (!db || !fingerprint) return porDefecto

  try {
    // Camino rápido: el libro ya sabe dónde vive.
    if (syncId) {
      const fila = await pullProgress(syncId)
      if (fila) return { syncId, progreso: fila, fusionadas: 0, ok: true }
    }

    if (!duracion) return porDefecto

    const margen = toleranciaDuracion(duracion)
    const { data, error } = await db
      .from('progress')
      .select(COLUMNAS)
      .gte('duration', duracion - margen)
      .lte('duration', duracion + margen)
    if (error) throw error

    const idsPropios = [fingerprint, syncId].filter(Boolean)
    const grupo = agruparMismoLibro(data || [], { idsPropios, duracion, titulo, autor })
    if (!grupo.length) return porDefecto

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
 */
export async function pushProgress(entry) {
  const db = getClient()
  if (!db || !entry?.bookId) return false
  try {
    const { error } = await db.from('progress').upsert(
      {
        book_id: entry.bookId,
        track_id: entry.trackId ?? null,
        position: entry.position ?? 0,
        global_position: entry.globalPosition ?? 0,
        duration: entry.duration ?? null,
        finished: Boolean(entry.finished),
        title: entry.title ?? null,
        author: entry.author ?? null,
        device: deviceName(),
        updated_at: new Date(entry.updatedAt || Date.now()).toISOString(),
      },
      { onConflict: 'user_id,book_id' }
    )
    if (error) throw error
    return true
  } catch (err) {
    console.warn('No se pudo subir el progreso', err)
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
  const posRemota = remote.global_position ?? remote.position ?? 0
  if (ganaLaRemota(posLocal, posRemota)) return { winner: 'remote', progress: remote }
  return { winner: 'local', progress: local }
}
