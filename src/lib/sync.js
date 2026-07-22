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

const URL = import.meta.env?.VITE_SUPABASE_URL || ''
const KEY = import.meta.env?.VITE_SUPABASE_ANON_KEY || ''

let client = null

/** ¿Hay proyecto de Supabase configurado en el build? */
export function isConfigured() {
  return Boolean(URL && KEY)
}

function getClient() {
  if (!isConfigured()) return null
  if (!client) {
    client = createClient(URL, KEY, {
      auth: { persistSession: true, autoRefreshToken: true },
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
  return data.user
}

export async function signOut() {
  await getClient()?.auth.signOut()
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
export async function pullProgress(bookId) {
  const db = getClient()
  if (!db || !bookId) return null
  try {
    const { data, error } = await db
      .from('progress')
      .select('book_id, track_id, position, global_position, duration, finished, updated_at, device')
      .eq('book_id', bookId)
      .maybeSingle()
    if (error) throw error
    return data || null
  } catch (err) {
    console.warn('No se pudo leer el progreso remoto', err)
    return null
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
 * Decide qué posición vale cuando hay una local y una remota: gana la más
 * reciente. El margen de 60 s evita que un pequeño desfase de reloj entre
 * dispositivos haga saltar la reproducción hacia atrás sin motivo.
 */
export function resolveProgress(local, remote) {
  if (!remote) return { winner: 'local', progress: local }
  if (!local) return { winner: 'remote', progress: remote }
  const remoteAt = new Date(remote.updated_at).getTime()
  const localAt = local.updatedAt || 0
  if (remoteAt > localAt + 60_000) return { winner: 'remote', progress: remote }
  return { winner: 'local', progress: local }
}
