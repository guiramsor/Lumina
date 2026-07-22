import { parseBlob } from 'music-metadata'

// Formats Chromium (and therefore Electron) can actually decode. WMA/AIFF are
// deliberately excluded: their tags could be read, but playback would fail.
const AUDIO_EXT = /\.(mp3|m4a|m4b|mp4|aac|ogg|oga|opus|wav|flac|webm|mka|weba)$/i
const UNPLAYABLE_EXT = /\.(wma|aiff?|aax|aa|m4p)$/i

export function isAudioFile(file) {
  if (UNPLAYABLE_EXT.test(file.name || '')) return false
  if (file.type && file.type.startsWith('audio')) return true
  return AUDIO_EXT.test(file.name || '')
}

function baseName(name) {
  return (name || 'Pista')
    .replace(/\.[^.]+$/, '')
    .replace(/^\d+[\s._-]+/, '')
    .replace(/[_]+/g, ' ')
    .trim()
}

/**
 * Parse a single audio file's tags.
 * Returns { title, author, narrator, album, picture: Blob|null, duration }
 */
export async function parseAudioFile(file) {
  let result = {
    title: baseName(file.name),
    author: '',
    narrator: '',
    album: '',
    picture: null,
    duration: 0,
    chapters: [],
  }
  try {
    const metadata = await parseBlob(file, {
      duration: true,
      skipCovers: false,
      includeChapters: true,
    })
    const c = metadata.common || {}
    const f = metadata.format || {}
    result.title = c.title || result.title
    result.album = c.album || ''
    result.author = c.albumartist || c.artist || (c.artists && c.artists[0]) || ''
    result.narrator = c.composer ? (Array.isArray(c.composer) ? c.composer[0] : c.composer) : ''
    if (f.duration) result.duration = f.duration
    if (c.picture && c.picture.length) {
      const pic = c.picture[0]
      const data = pic.data instanceof Uint8Array ? pic.data : new Uint8Array(pic.data)
      result.picture = new Blob([data], { type: pic.format || 'image/jpeg' })
    }
    result.chapters = normalizeChapters(f.chapters)
  } catch (err) {
    console.warn('No se pudieron leer las etiquetas de', file.name, err)
  }
  if (!result.duration) {
    result.duration = await getAudioDuration(file)
  }
  return result
}

/** Convert music-metadata chapter timestamps to seconds. */
function chapterToSeconds(value, timeScale) {
  if (value == null) return 0
  if (timeScale && timeScale > 0) return value / timeScale
  return value
}

function normalizeChapters(raw) {
  if (!Array.isArray(raw) || !raw.length) return []
  const seen = new Set()
  return raw
    .map((c, i) => ({
      title: (c.title || '').toString().trim() || `Capítulo ${i + 1}`,
      start: Math.max(0, chapterToSeconds(c.start, c.timeScale)),
    }))
    .filter((c) => {
      if (!isFinite(c.start)) return false
      const key = Math.round(c.start)
      if (seen.has(key)) return false
      seen.add(key)
      return true
    })
    .sort((a, b) => a.start - b.start)
}

/** Fallback duration using a hidden audio element. */
export function getAudioDuration(file) {
  return new Promise((resolve) => {
    const url = URL.createObjectURL(file)
    const audio = document.createElement('audio')
    audio.preload = 'metadata'
    let settled = false
    const done = (value) => {
      if (settled) return
      settled = true
      URL.revokeObjectURL(url)
      audio.removeAttribute('src')
      resolve(value)
    }
    audio.addEventListener('loadedmetadata', () => {
      done(isFinite(audio.duration) ? audio.duration : 0)
    })
    audio.addEventListener('error', () => done(0))
    setTimeout(() => done(isFinite(audio.duration) ? audio.duration : 0), 8000)
    audio.src = url
  })
}

/* ---------------- Color extraction ---------------- */

function rgbToHsl(r, g, b) {
  r /= 255
  g /= 255
  b /= 255
  const max = Math.max(r, g, b)
  const min = Math.min(r, g, b)
  let h = 0
  let s = 0
  const l = (max + min) / 2
  const d = max - min
  if (d !== 0) {
    s = l > 0.5 ? d / (2 - max - min) : d / (max + min)
    switch (max) {
      case r:
        h = (g - b) / d + (g < b ? 6 : 0)
        break
      case g:
        h = (b - r) / d + 2
        break
      default:
        h = (r - g) / d + 4
        break
    }
    h *= 60
  }
  return [h, s, l]
}

const FALLBACK_PALETTE = { hue: 268, sat: 62, light: 60 }

/**
 * Extract a vibrant dominant palette from a cover image Blob.
 * Returns { hue, sat, light } (HSL, hue 0-360, sat/light 0-100).
 */
export async function extractPalette(blob) {
  if (!blob) return { ...FALLBACK_PALETTE }
  try {
    const bitmap = await loadBitmap(blob)
    const size = 28
    const canvas = document.createElement('canvas')
    canvas.width = size
    canvas.height = size
    const ctx = canvas.getContext('2d', { willReadFrequently: true })
    ctx.drawImage(bitmap, 0, 0, size, size)
    const { data } = ctx.getImageData(0, 0, size, size)

    const buckets = new Map() // hueBucket -> { score, hSum, sSum, lSum, n }
    let avgR = 0
    let avgG = 0
    let avgB = 0
    let avgN = 0

    for (let i = 0; i < data.length; i += 4) {
      const r = data[i]
      const g = data[i + 1]
      const b = data[i + 2]
      const a = data[i + 3]
      if (a < 125) continue
      avgR += r
      avgG += g
      avgB += b
      avgN++
      const [h, s, l] = rgbToHsl(r, g, b)
      if (s < 0.18 || l < 0.12 || l > 0.92) continue
      const key = Math.round(h / 12)
      const weight = s * (1 - Math.abs(l - 0.55))
      const cur = buckets.get(key) || { score: 0, hSum: 0, sSum: 0, lSum: 0, n: 0 }
      cur.score += weight
      cur.hSum += h
      cur.sSum += s
      cur.lSum += l
      cur.n++
      buckets.set(key, cur)
    }

    let best = null
    for (const bucket of buckets.values()) {
      if (!best || bucket.score > best.score) best = bucket
    }

    if (best && best.n > 0) {
      const hue = Math.round(best.hSum / best.n)
      let sat = Math.round((best.sSum / best.n) * 100)
      let light = Math.round((best.lSum / best.n) * 100)
      sat = Math.min(92, Math.max(55, sat))
      light = Math.min(68, Math.max(48, light))
      return { hue, sat, light }
    }

    // Grayscale / muted cover: derive a tinted neutral from the average.
    if (avgN > 0) {
      const [h, s] = rgbToHsl(avgR / avgN, avgG / avgN, avgB / avgN)
      return { hue: Math.round(h) || FALLBACK_PALETTE.hue, sat: Math.max(28, Math.round(s * 100)), light: 58 }
    }
  } catch (err) {
    console.warn('No se pudo extraer el color de la portada', err)
  }
  return { ...FALLBACK_PALETTE }
}

function loadBitmap(blob) {
  if ('createImageBitmap' in window) {
    return createImageBitmap(blob)
  }
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(blob)
    const img = new Image()
    img.onload = () => {
      URL.revokeObjectURL(url)
      resolve(img)
    }
    img.onerror = (e) => {
      URL.revokeObjectURL(url)
      reject(e)
    }
    img.src = url
  })
}
