/**
 * Minimal cue-sheet support: many audiobooks ship as one big MP3/M4B plus a
 * sidecar .cue listing the chapters. We read TRACK/TITLE/INDEX 01 entries and
 * turn them into the same chapter shape used for embedded chapters.
 */

const CUE_EXT = /\.cue$/i

export function isCueFile(file) {
  return CUE_EXT.test(file?.name || '')
}

/**
 * Decode a cue file. Cue sheets are frequently saved as Windows-1252/Latin-1;
 * try UTF-8 first and fall back when replacement characters appear.
 */
export async function readCueText(file) {
  const buf = await file.arrayBuffer()
  const utf8 = new TextDecoder('utf-8').decode(buf)
  if (!utf8.includes('�')) return utf8
  return new TextDecoder('windows-1252').decode(buf)
}

/**
 * Parse cue text into { fileName, chapters: [{ title, start }] }.
 * INDEX times are MM:SS:FF (75 frames per second); minutes may exceed 99.
 */
export function parseCueSheet(text) {
  const chapters = []
  let current = null
  let fileName = null
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim()
    let m
    if ((m = line.match(/^FILE\s+"([^"]+)"/i))) {
      fileName = m[1]
      continue
    }
    if ((m = line.match(/^TRACK\s+(\d+)\s+AUDIO/i))) {
      current = { title: `Pista ${Number(m[1])}`, start: null }
      chapters.push(current)
      continue
    }
    if (!current) continue
    if ((m = line.match(/^TITLE\s+"([^"]*)"/i))) {
      if (m[1].trim()) current.title = m[1].trim()
      continue
    }
    if ((m = line.match(/^INDEX\s+0*1\s+(\d+):(\d{1,2}):(\d{1,2})\s*$/i))) {
      current.start = Number(m[1]) * 60 + Number(m[2]) + Number(m[3]) / 75
    }
  }
  const valid = chapters
    .filter((c) => c.start != null && isFinite(c.start))
    .sort((a, b) => a.start - b.start)
  return { fileName, chapters: valid }
}
