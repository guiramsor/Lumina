import { isAudioFile, parseAudioFile, extractPalette } from './metadata.js'
import { isCueFile, readCueText, parseCueSheet } from './cue.js'
import { fingerprintBook } from './fingerprint.js'
import { putBook, findBookByFingerprint } from './db.js'

const collator = new Intl.Collator('es', { numeric: true, sensitivity: 'base' })

// `_relPath` is set by the drag&drop folder traversal; `webkitRelativePath`
// comes from the folder picker. Plain file selections only have `name`.
function relPath(file) {
  return file._relPath || file.webkitRelativePath || file.name
}

function naturalSort(a, b) {
  return collator.compare(relPath(a), relPath(b))
}

function parentDir(file) {
  const p = file._relPath || file.webkitRelativePath || ''
  const idx = p.lastIndexOf('/')
  return idx >= 0 ? p.slice(0, idx) : ''
}

const IMAGE_EXT = /\.(jpe?g|png|webp|gif|bmp|avif)$/i

function isImageFile(file) {
  return (file.type && file.type.startsWith('image')) || IMAGE_EXT.test(file.name || '')
}

/** Pick the best external cover image sitting in the same folder as the book. */
function pickCoverImage(images, dir) {
  const inDir = images.filter((f) => parentDir(f) === dir)
  if (!inDir.length) return null
  const preferred = inDir.find((f) => /(cover|folder|front|album|portada)/i.test(f.name))
  return preferred || inDir[0]
}

/**
 * Decide which book a file belongs to. Files in different subfolders, or
 * carrying different album tags within the same folder, become separate books.
 * A folder of chapter files that share an album (or share a folder with no
 * album) collapse into a single book.
 */
function groupKey(file, meta) {
  const album = (meta.album || '').trim().toLowerCase()
  return `${parentDir(file)}|${album}`
}

function deriveTitle(entries) {
  const first = entries[0]
  if (first.meta.album) return first.meta.album
  if (entries.length === 1) return first.meta.title || 'Audiolibro'
  const dir = parentDir(first.file)
  if (dir) return dir.split('/').pop()
  return first.meta.title || 'Audiolibro'
}

/**
 * Build a unified navigation list of chapters.
 * - A single file with embedded chapters -> those chapters within track 0.
 * - Multiple files -> one chapter per file (offset by its position).
 * - Multiple files that each carry embedded chapters -> all of them, offset per track.
 */
function buildChapters(tracks, trackChapters) {
  const chapters = []
  let offset = 0
  for (let i = 0; i < tracks.length; i++) {
    const track = tracks[i]
    const embedded = trackChapters[i] || []
    if (embedded.length > 1) {
      for (const ch of embedded) {
        if (track.duration && ch.start >= track.duration) continue
        chapters.push({
          title: ch.title,
          trackIndex: i,
          start: ch.start,
          globalStart: offset + ch.start,
        })
      }
    } else {
      chapters.push({
        title: track.title || track.name,
        trackIndex: i,
        start: 0,
        globalStart: offset,
      })
    }
    offset += track.duration || 0
  }
  return chapters
}

function stripExt(name) {
  return (name || '').replace(/\.[^.]+$/, '').trim().toLowerCase()
}

/**
 * Look for a sidecar .cue that describes the chapters of a single-file book
 * without embedded chapters. The cue must live in the same folder; it is
 * matched by basename, or used directly when it is unambiguous (only cue for
 * the only audio file in that folder).
 */
async function cueChaptersFor(cueFiles, entries, audioCountByDir) {
  if (entries.length !== 1) return null
  const { file, meta } = entries[0]
  if ((meta.chapters || []).length > 1) return null
  const dir = parentDir(file)
  const inDir = cueFiles.filter((c) => parentDir(c) === dir)
  if (!inDir.length) return null
  const base = stripExt(file.name)
  let cue = inDir.find((c) => stripExt(c.name) === base)
  if (!cue && inDir.length === 1 && (audioCountByDir.get(dir) || 0) === 1) cue = inDir[0]
  if (!cue) return null
  try {
    const { chapters } = parseCueSheet(await readCueText(cue))
    return chapters.length > 1 ? chapters : null
  } catch (err) {
    console.warn('No se pudo leer la hoja cue', cue.name, err)
    return null
  }
}

async function buildOneBook(entries, fallbackImage, cueChapters) {
  const tracks = []
  const trackChapters = []
  let coverBlob = null
  const firstMeta = entries[0].meta

  for (const { file, meta } of entries) {
    if (!coverBlob && meta.picture) coverBlob = meta.picture
    // Se guarda la ruta, no los bytes: copiar el audio dentro de IndexedDB
    // duplicaba en disco toda la biblioteca. Si no hay ruta disponible (por
    // ejemplo en el navegador, fuera de Electron) se recurre al archivo.
    const ruta = window.lumina?.rutaDeArchivo?.(file) || null
    tracks.push({
      name: file.name,
      title: meta.title,
      ruta,
      blob: ruta ? undefined : file,
      type: file.type || 'audio/mpeg',
      duration: meta.duration || 0,
    })
    trackChapters.push(meta.chapters || [])
  }

  // Single file without embedded chapters: use the sidecar cue sheet.
  if (cueChapters && (!trackChapters[0] || trackChapters[0].length <= 1)) {
    trackChapters[0] = cueChapters
  }

  // No embedded art: fall back to a cover image found alongside the files.
  if (!coverBlob && fallbackImage) coverBlob = fallbackImage

  const palette = await extractPalette(coverBlob)
  const totalDuration = tracks.reduce((sum, t) => sum + (t.duration || 0), 0)
  const chapters = buildChapters(tracks, trackChapters)

  // Huella del contenido: es lo que permite reconocer este mismo libro en el
  // móvil, donde tendrá otro id local. Solo lee 2 MiB por archivo.
  // Se calcula sobre los archivos originales: las pistas ya no llevan sus
  // bytes, solo la ruta.
  const { bookFingerprint, trackFingerprints } = await fingerprintBook(entries.map((e) => e.file))
  tracks.forEach((t, i) => {
    t.fingerprint = trackFingerprints[i]
  })

  // Autodetectar el número dentro de la serie desde la carpeta: "[7] Título",
  // "7 - Título", "07. Título"…
  const dirName = parentDir(entries[0].file).split('/').pop() || ''
  const idxMatch = dirName.match(/^\[?(\d{1,3})\]?[\s._-]+\S/)
  const seriesIndex = idxMatch ? Number(idxMatch[1]) : null

  // Reimportar un libro que ya estaba (misma huella) lo actualiza en vez de
  // duplicarlo, conservando su id y con él su progreso y sus marcadores. Es
  // además la vía para migrar los libros antiguos, que guardaban los bytes.
  const anterior = await findBookByFingerprint(bookFingerprint)

  const book = {
    id: anterior?.id || crypto.randomUUID(),
    fingerprint: bookFingerprint,
    // Lo que el usuario haya cambiado a mano en el editor manda sobre lo que
    // digan las etiquetas: reimportar no debe deshacer sus correcciones.
    title: anterior?.title || deriveTitle(entries),
    author: anterior?.author || firstMeta.author || '',
    narrator: anterior?.narrator || firstMeta.narrator || '',
    series: anterior?.series || '',
    seriesIndex: anterior?.seriesIndex ?? seriesIndex,
    coverFit: anterior?.coverFit,
    coverBlob: anterior?.coverBlob || coverBlob,
    palette: anterior?.coverBlob ? anterior.palette : palette,
    tracks,
    chapters,
    totalDuration,
    addedAt: anterior?.addedAt || Date.now(),
    lastOpened: Date.now(),
  }

  await putBook(book)
  return book
}

/**
 * Build one or more books from a set of selected files (single file, multiple
 * files, or a folder). A folder may contain several independent audiobooks;
 * they are split apart by subfolder and album tag.
 * onProgress({ current, total, label }) is called as parsing advances.
 * Returns an array of books (newest grouping order preserved).
 */
export async function buildBooksFromFiles(fileList, onProgress) {
  const all = Array.from(fileList)
  const files = all.filter(isAudioFile).sort(naturalSort)
  const images = all.filter(isImageFile)
  const cueFiles = all.filter(isCueFile)
  if (!files.length) {
    throw new Error('No se encontró ningún archivo de audio compatible.')
  }

  const audioCountByDir = new Map()
  for (const f of files) {
    const dir = parentDir(f)
    audioCountByDir.set(dir, (audioCountByDir.get(dir) || 0) + 1)
  }

  const total = files.length
  const parsed = []
  for (let i = 0; i < files.length; i++) {
    const file = files[i]
    onProgress?.({ current: i, total, label: file.name })
    const meta = await parseAudioFile(file)
    parsed.push({ file, meta })
  }

  // Group files into separate books, preserving the natural sort order.
  const groups = new Map()
  for (const entry of parsed) {
    const key = groupKey(entry.file, entry.meta)
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key).push(entry)
  }

  onProgress?.({
    current: total,
    total,
    label: groups.size > 1 ? `Procesando ${groups.size} audiolibros…` : 'Procesando portada…',
  })

  const books = []
  for (const entries of groups.values()) {
    const dir = parentDir(entries[0].file)
    const fallbackImage = pickCoverImage(images, dir)
    const cueChapters = await cueChaptersFor(cueFiles, entries, audioCountByDir)
    books.push(await buildOneBook(entries, fallbackImage, cueChapters))
  }
  return books
}
