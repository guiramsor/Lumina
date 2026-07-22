import { test } from 'node:test'
import assert from 'node:assert/strict'
import { trackFingerprint, bookFingerprintFrom, fingerprintBook } from '../src/lib/fingerprint.js'

/** Blob determinista de `size` bytes con un patrón reproducible. */
function fakeAudio(size, seed = 1) {
  const bytes = new Uint8Array(size)
  for (let i = 0; i < size; i++) bytes[i] = (i * 31 + seed * 7) % 256
  return new Blob([bytes])
}

test('la misma copia del archivo produce la misma huella', async () => {
  const a = await trackFingerprint(fakeAudio(5000))
  const b = await trackFingerprint(fakeAudio(5000))
  assert.equal(a, b)
  assert.match(a, /^[0-9a-f]{64}$/)
})

test('contenidos distintos producen huellas distintas', async () => {
  const a = await trackFingerprint(fakeAudio(5000, 1))
  const b = await trackFingerprint(fakeAudio(5000, 2))
  assert.notEqual(a, b)
})

test('el tamaño forma parte de la identidad', async () => {
  // Mismo patrón, distinta longitud: no deben colisionar.
  const a = await trackFingerprint(fakeAudio(5000))
  const b = await trackFingerprint(fakeAudio(6000))
  assert.notEqual(a, b)
})

test('archivos mayores de 2 MiB usan primer y ultimo MiB', async () => {
  const big = 3 * 1024 * 1024
  const a = await trackFingerprint(fakeAudio(big))
  const b = await trackFingerprint(fakeAudio(big))
  assert.equal(a, b)
})

test('la huella del libro no depende del orden de las pistas', async () => {
  const t1 = 'a'.repeat(64)
  const t2 = 'b'.repeat(64)
  const t3 = 'c'.repeat(64)
  const ordenado = await bookFingerprintFrom([t1, t2, t3])
  const revuelto = await bookFingerprintFrom([t3, t1, t2])
  assert.equal(ordenado, revuelto)
})

test('fingerprintBook conserva el orden original de las pistas', async () => {
  const blobs = [fakeAudio(1000, 1), fakeAudio(1000, 2)]
  const { bookFingerprint, trackFingerprints } = await fingerprintBook(blobs)
  assert.equal(trackFingerprints.length, 2)
  assert.equal(trackFingerprints[0], await trackFingerprint(blobs[0]))
  assert.equal(trackFingerprints[1], await trackFingerprint(blobs[1]))
  assert.match(bookFingerprint, /^[0-9a-f]{64}$/)
})

/**
 * Vectores congelados. La app de Android DEBE reproducir exactamente estos
 * valores; son la prueba de que ambas implementaciones coinciden.
 * Si un cambio los rompe, el progreso ya sincronizado queda huérfano.
 */
test('vectores de referencia para la implementacion en Kotlin', async () => {
  // Archivo de 10 bytes: 00 01 02 ... 09
  const diezBytes = new Blob([new Uint8Array([0, 1, 2, 3, 4, 5, 6, 7, 8, 9])])
  assert.equal(
    await trackFingerprint(diezBytes),
    '83fe3c54f403ec66e809df9dceb0f308fa20394de604b54e9c1a59d805e2e5b7'
  )

  // Libro de dos pistas con huellas conocidas
  assert.equal(
    await bookFingerprintFrom(['00'.repeat(32), 'ff'.repeat(32)]),
    'f7ee6e27721feb087d5ad6f99251059d05183104ae909d2b9830b12cadd4f822'
  )
})
