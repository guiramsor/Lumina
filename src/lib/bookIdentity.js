/**
 * Identidad sincronizable de los libros ya guardados.
 *
 * Los libros importados antes de existir la sincronización no tienen huella.
 * En vez de recalcular la biblioteca entera de golpe, se completa la huella la
 * primera vez que se abre cada libro y se guarda para siempre.
 */
import { fingerprintBook } from './fingerprint.js'
import { updateBook } from './db.js'

/**
 * Devuelve el libro con `fingerprint` propio y `fingerprint` en cada pista,
 * calculándolos y persistiéndolos si faltaban.
 */
export async function ensureFingerprints(book) {
  if (!book?.tracks?.length) return book
  const completo = book.fingerprint && book.tracks.every((t) => t.fingerprint)
  if (completo) return book
  // Los libros que guardan la ruta en vez de los bytes reciben su huella al
  // importarse; aquí solo se completan los antiguos, que sí llevan blob.
  if (!book.tracks.every((t) => t.blob)) return book

  try {
    const { bookFingerprint, trackFingerprints } = await fingerprintBook(book.tracks.map((t) => t.blob))
    const tracks = book.tracks.map((t, i) => ({ ...t, fingerprint: trackFingerprints[i] }))
    const guardado = await updateBook(book.id, { fingerprint: bookFingerprint, tracks })
    return guardado || { ...book, fingerprint: bookFingerprint, tracks }
  } catch (err) {
    // Sin huella el libro se escucha igual, solo que no sincroniza.
    console.warn('No se pudo calcular la huella del libro', book.title, err)
    return book
  }
}

/** Índice de la pista cuya huella coincide, o -1. */
export function trackIndexByFingerprint(book, fingerprint) {
  if (!fingerprint || !book?.tracks) return -1
  return book.tracks.findIndex((t) => t.fingerprint === fingerprint)
}
