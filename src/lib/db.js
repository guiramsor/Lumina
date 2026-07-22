import { openDB } from 'idb'

const DB_NAME = 'lumina-audiobooks'
const DB_VERSION = 2

let dbPromise = null

function getDB() {
  if (!dbPromise) {
    dbPromise = openDB(DB_NAME, DB_VERSION, {
      upgrade(db) {
        if (!db.objectStoreNames.contains('books')) {
          db.createObjectStore('books', { keyPath: 'id' })
        }
        if (!db.objectStoreNames.contains('progress')) {
          db.createObjectStore('progress', { keyPath: 'bookId' })
        }
        if (!db.objectStoreNames.contains('bookmarks')) {
          const store = db.createObjectStore('bookmarks', { keyPath: 'id' })
          store.createIndex('byBook', 'bookId')
        }
        if (!db.objectStoreNames.contains('settings')) {
          db.createObjectStore('settings', { keyPath: 'id' })
        }
        if (!db.objectStoreNames.contains('stats')) {
          // Una fila por día natural: { day: 'YYYY-MM-DD', seconds, perBook }
          db.createObjectStore('stats', { keyPath: 'day' })
        }
      },
    })
  }
  return dbPromise
}

/* ---------- Books ---------- */

export async function getAllBooks() {
  const db = await getDB()
  const books = await db.getAll('books')
  return books.sort((a, b) => (b.lastOpened || b.addedAt) - (a.lastOpened || a.addedAt))
}

export async function getBook(id) {
  const db = await getDB()
  return db.get('books', id)
}

export async function putBook(book) {
  const db = await getDB()
  await db.put('books', book)
  return book
}

export async function touchBook(id) {
  const db = await getDB()
  const book = await db.get('books', id)
  if (book) {
    book.lastOpened = Date.now()
    await db.put('books', book)
  }
}

export async function updateBook(id, patch) {
  const db = await getDB()
  const book = await db.get('books', id)
  if (!book) return null
  const next = { ...book, ...patch }
  await db.put('books', next)
  return next
}

export async function deleteBook(id) {
  const db = await getDB()
  const tx = db.transaction(['books', 'progress', 'bookmarks'], 'readwrite')
  await tx.objectStore('books').delete(id)
  await tx.objectStore('progress').delete(id)
  const bmStore = tx.objectStore('bookmarks')
  const keys = await bmStore.index('byBook').getAllKeys(id)
  await Promise.all(keys.map((k) => bmStore.delete(k)))
  await tx.done
}

/* ---------- Progress ---------- */

export async function getProgress(bookId) {
  const db = await getDB()
  return db.get('progress', bookId)
}

export async function putProgress(progress) {
  const db = await getDB()
  await db.put('progress', { ...progress, updatedAt: Date.now() })
}

export async function getAllProgress() {
  const db = await getDB()
  const list = await db.getAll('progress')
  const map = {}
  for (const p of list) map[p.bookId] = p
  return map
}

export async function putBookSpeed(bookId, speed) {
  const db = await getDB()
  const current = (await db.get('progress', bookId)) || { bookId }
  await db.put('progress', { ...current, bookId, speed, updatedAt: Date.now() })
}

/* ---------- Bookmarks ---------- */

export async function getBookmarks(bookId) {
  const db = await getDB()
  const list = await db.getAllFromIndex('bookmarks', 'byBook', bookId)
  return list.sort((a, b) => a.globalTime - b.globalTime)
}

export async function addBookmark(bookmark) {
  const db = await getDB()
  const record = { id: crypto.randomUUID(), createdAt: Date.now(), ...bookmark }
  await db.put('bookmarks', record)
  return record
}

export async function updateBookmark(bookmark) {
  const db = await getDB()
  await db.put('bookmarks', bookmark)
}

export async function deleteBookmark(id) {
  const db = await getDB()
  await db.delete('bookmarks', id)
}

/* ---------- Listening stats ---------- */

export function todayKey(date = new Date()) {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

/** Accumulate listened wall-clock seconds into today's row. */
export async function addListeningTime(bookId, seconds) {
  if (!seconds || seconds <= 0) return
  const db = await getDB()
  const day = todayKey()
  const row = (await db.get('stats', day)) || { day, seconds: 0, perBook: {} }
  row.seconds += seconds
  if (bookId) row.perBook[bookId] = (row.perBook[bookId] || 0) + seconds
  await db.put('stats', row)
}

export async function getAllStats() {
  const db = await getDB()
  return db.getAll('stats')
}

/* ---------- Settings ---------- */

const SETTINGS_KEY = 'global'

const DEFAULT_SETTINGS = {
  id: SETTINGS_KEY,
  speed: 1,
  visualMode: 'vinyl', // 'vinyl' | 'book'
  volume: 1,
  lastSleepMinutes: 30,
  theme: 'noche', // ver THEMES en theme.js
  librarySort: 'reciente',
}

export async function getSettings() {
  const db = await getDB()
  const saved = await db.get('settings', SETTINGS_KEY)
  return { ...DEFAULT_SETTINGS, ...(saved || {}) }
}

export async function putSettings(patch) {
  const db = await getDB()
  const current = (await db.get('settings', SETTINGS_KEY)) || DEFAULT_SETTINGS
  const next = { ...current, ...patch, id: SETTINGS_KEY }
  await db.put('settings', next)
  return next
}

/* ---------- Storage estimate ---------- */

export async function getStorageEstimate() {
  if (navigator.storage && navigator.storage.estimate) {
    try {
      const { usage, quota } = await navigator.storage.estimate()
      return { usage, quota }
    } catch {
      return null
    }
  }
  return null
}
