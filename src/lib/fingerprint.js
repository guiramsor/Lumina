/**
 * Huella digital de audiolibros: identidad estable y portable.
 *
 * Como cada dispositivo guarda su propia copia del archivo, no podemos usar el
 * id local (un UUID aleatorio) para saber que dos ficheros son el mismo libro.
 * La huella se calcula del contenido, así que el mismo archivo copiado al móvil
 * produce exactamente el mismo identificador.
 *
 * IMPORTANTE: este algoritmo es un contrato compartido con la app de Android.
 * Cualquier cambio aquí obliga a cambiar la implementación en Kotlin y deja
 * huérfano el progreso ya sincronizado. Ver docs/SYNC.md.
 *
 *   huella de pista = SHA-256( primer MiB || último MiB || tamaño en ASCII )
 *   huella de libro = SHA-256( huellas de sus pistas, ordenadas y unidas por \n )
 *
 * Solo se leen 2 MiB de cada archivo, así que un M4B de 1,7 GB se resuelve en
 * milisegundos en vez de tener que digerirlo entero.
 */

const CHUNK = 1024 * 1024 // 1 MiB

function toHex(buffer) {
  return Array.from(new Uint8Array(buffer))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
}

async function sha256Hex(bytes) {
  const digest = await crypto.subtle.digest('SHA-256', bytes)
  return toHex(digest)
}

/**
 * Huella de un único archivo de audio (Blob o File).
 * Devuelve 64 caracteres hexadecimales.
 */
export async function trackFingerprint(blob) {
  const size = blob.size
  const head = new Uint8Array(await blob.slice(0, Math.min(CHUNK, size)).arrayBuffer())
  // El último MiB solo se añade si no solapa con el primero; en archivos
  // pequeños el head ya cubre todo el contenido.
  const tailStart = Math.max(head.length, size - CHUNK)
  const tail =
    tailStart < size
      ? new Uint8Array(await blob.slice(tailStart, size).arrayBuffer())
      : new Uint8Array(0)

  const sizeBytes = new TextEncoder().encode(String(size))
  const payload = new Uint8Array(head.length + tail.length + sizeBytes.length)
  payload.set(head, 0)
  payload.set(tail, head.length)
  payload.set(sizeBytes, head.length + tail.length)

  return sha256Hex(payload)
}

/**
 * Huella de un libro completo a partir de las huellas de sus pistas.
 * Se ordenan alfabéticamente para que la identidad del libro no dependa del
 * criterio de ordenación de cada plataforma: si el móvil ordenase las pistas
 * de otra forma, la huella seguiría coincidiendo.
 */
export async function bookFingerprintFrom(trackFingerprints) {
  const joined = [...trackFingerprints].sort().join('\n')
  return sha256Hex(new TextEncoder().encode(joined))
}

/**
 * Calcula las huellas de todas las pistas de un libro y la del libro.
 * Devuelve { bookFingerprint, trackFingerprints } en el orden original.
 */
export async function fingerprintBook(blobs) {
  const trackFingerprints = []
  for (const blob of blobs) {
    trackFingerprints.push(await trackFingerprint(blob))
  }
  return {
    bookFingerprint: await bookFingerprintFrom(trackFingerprints),
    trackFingerprints,
  }
}
