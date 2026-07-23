import { useEffect, useMemo, useRef, useState } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import BookCard from './BookCard.jsx'
import BookEditor from './BookEditor.jsx'
import StatsPanel from './StatsPanel.jsx'
import SyncPanel from './SyncPanel.jsx'
import { buildBooksFromFiles } from '../lib/importBooks.js'
import { getSettings, putSettings } from '../lib/db.js'
import { THEMES } from '../lib/theme.js'
import { formatTime } from '../lib/format.js'
import {
  PlusIcon,
  BookIcon,
  PlayIcon,
  SearchIcon,
  ChartIcon,
  PaletteIcon,
  HelpIcon,
  CloudIcon,
} from './Icons.jsx'

const collator = new Intl.Collator('es', { sensitivity: 'base', numeric: true })

const SORTS = [
  { id: 'reciente', label: 'Recientes' },
  { id: 'titulo', label: 'Título' },
  { id: 'autor', label: 'Autor' },
  { id: 'progreso', label: 'Progreso' },
  { id: 'serie', label: 'Serie' },
]

function pctOf(book, progress) {
  if (!progress || !book.totalDuration) return 0
  if (progress.finished) return 100
  return Math.min(100, (progress.globalTime / book.totalDuration) * 100)
}

export default function Library({
  books,
  progressMap,
  themeId,
  onThemeChange,
  onShowHelp,
  onOpen,
  onDelete,
  onImported,
  onUpdated,
}) {
  const fileInputRef = useRef(null)
  const folderInputRef = useRef(null)
  const [importing, setImporting] = useState(null) // { current, total, label }
  const [error, setError] = useState(null)
  const [dragging, setDragging] = useState(false)
  const [editing, setEditing] = useState(null)
  const [query, setQuery] = useState('')
  const [sort, setSort] = useState('reciente')
  const [themeOpen, setThemeOpen] = useState(false)
  const [statsOpen, setStatsOpen] = useState(false)
  const [syncOpen, setSyncOpen] = useState(false)

  useEffect(() => {
    getSettings().then((s) => setSort(s.librarySort || 'reciente'))
  }, [])

  const changeSort = (value) => {
    setSort(value)
    putSettings({ librarySort: value })
  }

  const handleFiles = async (fileList) => {
    setError(null)
    if (!fileList || !fileList.length) return
    setImporting({ current: 0, total: fileList.length, label: 'Preparando…' })
    try {
      const books = await buildBooksFromFiles(fileList, (p) => setImporting(p))
      onImported(books)
    } catch (err) {
      setError(err.message || 'No se pudo importar.')
    } finally {
      setImporting(null)
    }
  }

  // Recursively resolve dropped items: dropping a folder only yields its files
  // through the webkitGetAsEntry tree, never through dataTransfer.files.
  const resolveEntry = async (entry, path, out) => {
    if (entry.isFile) {
      const file = await new Promise((res, rej) => entry.file(res, rej))
      try {
        Object.defineProperty(file, '_relPath', { value: path ? `${path}/${file.name}` : file.name })
      } catch {
        /* ignore */
      }
      out.push(file)
    } else if (entry.isDirectory) {
      const dirPath = path ? `${path}/${entry.name}` : entry.name
      const reader = entry.createReader()
      let batch
      do {
        batch = await new Promise((res, rej) => reader.readEntries(res, rej))
        for (const child of batch) await resolveEntry(child, dirPath, out)
      } while (batch.length)
    }
  }

  const onDrop = (e) => {
    e.preventDefault()
    setDragging(false)
    const dt = e.dataTransfer
    if (!dt) return
    // Grab entries synchronously: DataTransferItems die once the handler yields.
    const entries = []
    const plain = []
    for (const item of dt.items || []) {
      if (item.kind !== 'file') continue
      const entry = typeof item.webkitGetAsEntry === 'function' ? item.webkitGetAsEntry() : null
      if (entry) entries.push(entry)
      else {
        const f = item.getAsFile?.()
        if (f) plain.push(f)
      }
    }
    if (!entries.length) {
      const files = plain.length ? plain : Array.from(dt.files || [])
      if (files.length) handleFiles(files)
      return
    }
    ;(async () => {
      const out = [...plain]
      try {
        for (const entry of entries) await resolveEntry(entry, '', out)
      } catch (err) {
        console.warn('No se pudo leer una carpeta arrastrada', err)
      }
      handleFiles(out)
    })()
  }

  /* ---------- Continuar escuchando ---------- */
  const continueBook = useMemo(() => {
    let best = null
    let bestAt = 0
    for (const book of books) {
      const p = progressMap[book.id]
      if (!p || p.finished || !(p.globalTime > 0)) continue
      if ((p.updatedAt || 0) > bestAt) {
        bestAt = p.updatedAt || 0
        best = book
      }
    }
    return best
  }, [books, progressMap])

  /* ---------- Búsqueda y ordenación ---------- */
  const visible = useMemo(() => {
    const q = query.trim().toLowerCase()
    let list = books
    if (q) {
      list = books.filter((b) =>
        `${b.title} ${b.author} ${b.series || ''}`.toLowerCase().includes(q)
      )
    }
    const arr = [...list]
    switch (sort) {
      case 'titulo':
        arr.sort((a, b) => collator.compare(a.title, b.title))
        break
      case 'autor':
        arr.sort((a, b) => collator.compare(a.author || '￿', b.author || '￿') || collator.compare(a.title, b.title))
        break
      case 'progreso':
        arr.sort((a, b) => pctOf(b, progressMap[b.id]) - pctOf(a, progressMap[a.id]))
        break
      default:
        break // 'reciente' y 'serie' respetan el orden de llegada (lastOpened desc)
    }
    return arr
  }, [books, query, sort, progressMap])

  // Agrupación por serie (solo con orden "serie")
  const seriesGroups = useMemo(() => {
    if (sort !== 'serie') return null
    const groups = new Map()
    for (const b of visible) {
      const key = (b.series || '').trim() || 'Sin serie'
      if (!groups.has(key)) groups.set(key, [])
      groups.get(key).push(b)
    }
    const named = [...groups.entries()]
      .filter(([k]) => k !== 'Sin serie')
      .sort((a, b) => collator.compare(a[0], b[0]))
    for (const [, arr] of named) {
      arr.sort(
        (a, b) => (a.seriesIndex ?? Infinity) - (b.seriesIndex ?? Infinity) || collator.compare(a.title, b.title)
      )
    }
    const rest = groups.get('Sin serie')
    if (rest) {
      rest.sort((a, b) => collator.compare(a.title, b.title))
      named.push(['Sin serie', rest])
    }
    return named
  }, [visible, sort])

  const showHero = continueBook && !query.trim()
  const heroProgress = showHero ? progressMap[continueBook.id] : null
  const heroPct = showHero ? pctOf(continueBook, heroProgress) : 0
  const heroRemaining = showHero
    ? Math.max(0, (continueBook.totalDuration || 0) - (heroProgress?.globalTime || 0))
    : 0

  const renderCards = (list, offset = 0) =>
    list.map((book, i) => (
      <BookCard
        key={book.id}
        index={i + offset}
        book={book}
        progress={progressMap[book.id]}
        onOpen={onOpen}
        onDelete={onDelete}
        onEdit={setEditing}
      />
    ))

  return (
    <div
      className="library"
      onDragOver={(e) => {
        e.preventDefault()
        setDragging(true)
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={onDrop}
    >
      <header className="library-header">
        <div className="brand">
          <span className="brand-mark">🎧</span>
          <div>
            <h1>Lumina</h1>
            <p>Tu biblioteca de audiolibros</p>
          </div>
        </div>

        <div className="library-actions">
          <div className="search-box">
            <SearchIcon size={16} />
            <input
              type="text"
              placeholder="Buscar…"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={(e) => e.key === 'Escape' && setQuery('')}
            />
          </div>

          <select
            className="sort-select"
            value={sort}
            onChange={(e) => changeSort(e.target.value)}
            title="Ordenar biblioteca"
          >
            {SORTS.map((s) => (
              <option key={s.id} value={s.id}>
                {s.label}
              </option>
            ))}
          </select>

          <div className="theme-picker">
            <button
              className={`icon-btn ghost ${themeOpen ? 'active' : ''}`}
              title="Tema de color"
              onClick={() => setThemeOpen((v) => !v)}
            >
              <PaletteIcon size={19} />
            </button>
            <AnimatePresence>
              {themeOpen && (
                <motion.div
                  className="theme-menu"
                  initial={{ opacity: 0, y: 6, scale: 0.96 }}
                  animate={{ opacity: 1, y: 0, scale: 1 }}
                  exit={{ opacity: 0, y: 6, scale: 0.96, pointerEvents: 'none' }}
                >
                  {THEMES.map((t) => (
                    <button
                      key={t.id}
                      className={`theme-option ${t.id === themeId ? 'active' : ''}`}
                      onClick={() => {
                        onThemeChange(t.id)
                        setThemeOpen(false)
                      }}
                    >
                      <span className={`theme-dot theme-dot-${t.id}`} />
                      {t.name}
                    </button>
                  ))}
                </motion.div>
              )}
            </AnimatePresence>
          </div>

          <button className="icon-btn ghost" title="Estadísticas de escucha" onClick={() => setStatsOpen(true)}>
            <ChartIcon size={19} />
          </button>
          <button className="icon-btn ghost" title="Sincronización entre dispositivos" onClick={() => setSyncOpen(true)}>
            <CloudIcon size={19} />
          </button>
          <button className="icon-btn ghost" title="Atajos de teclado (?)" onClick={onShowHelp}>
            <HelpIcon size={19} />
          </button>

          <button className="btn primary" onClick={() => fileInputRef.current?.click()}>
            <PlusIcon size={18} /> Archivos
          </button>
          <button className="btn ghost" onClick={() => folderInputRef.current?.click()}>
            <BookIcon size={18} /> Carpeta
          </button>
        </div>

        <input
          ref={fileInputRef}
          type="file"
          accept="audio/*,.m4b,.m4a,.mp4,.mp3,.aac,.ogg,.oga,.opus,.wav,.flac,.webm,.mka,.cue"
          multiple
          hidden
          onChange={(e) => handleFiles(e.target.files)}
        />
        <input
          ref={folderInputRef}
          type="file"
          webkitdirectory=""
          directory=""
          multiple
          hidden
          onChange={(e) => handleFiles(e.target.files)}
        />
      </header>

      {error && <div className="library-error">{error}</div>}

      {showHero && (
        <motion.div
          className="continue-hero"
          initial={{ opacity: 0, y: 18 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.05, type: 'spring', stiffness: 200, damping: 24 }}
        >
          <button className="continue-cover" onClick={() => onOpen(continueBook, { autoplay: true })}>
            {continueBook.coverBlob ? (
              <HeroCover blob={continueBook.coverBlob} title={continueBook.title} />
            ) : (
              <div className="continue-cover-fallback">
                <BookIcon size={26} />
              </div>
            )}
            <span className="continue-cover-play">
              <PlayIcon size={22} />
            </span>
          </button>
          <div className="continue-info">
            <span className="continue-tag">Continuar escuchando</span>
            <h2>{continueBook.title}</h2>
            {continueBook.author && <p>{continueBook.author}</p>}
            <div className="continue-bar">
              <div className="continue-bar-fill" style={{ width: `${heroPct}%` }} />
            </div>
            <span className="continue-meta">
              {Math.round(heroPct)}% · quedan {formatTime(heroRemaining)}
            </span>
          </div>
          <button className="btn primary big continue-btn" onClick={() => onOpen(continueBook, { autoplay: true })}>
            <PlayIcon size={20} /> Continuar
          </button>
        </motion.div>
      )}

      {books.length === 0 && !importing ? (
        <motion.div
          className="empty-state"
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.1 }}
        >
          <div className="empty-illustration">
            <span>📚</span>
          </div>
          <h2>Empieza tu biblioteca</h2>
          <p>
            Arrastra aquí un MP3, M4B o una carpeta con capítulos. Lumina leerá la portada, recordará dónde lo
            dejaste y te dejará escucharlo con estilo.
          </p>
          <button className="btn primary big" onClick={() => fileInputRef.current?.click()}>
            <PlusIcon size={20} /> Añadir mi primer audiolibro
          </button>
        </motion.div>
      ) : visible.length === 0 ? (
        <div className="empty-search">
          <p>Nada que coincida con «{query}».</p>
        </div>
      ) : seriesGroups ? (
        <div className="series-sections">
          {seriesGroups.map(([name, list]) => (
            <section key={name}>
              <h3 className="series-header">
                {name} <span className="series-count">{list.length}</span>
              </h3>
              <motion.div layout className="book-grid">
                <AnimatePresence mode="popLayout">{renderCards(list)}</AnimatePresence>
              </motion.div>
            </section>
          ))}
        </div>
      ) : (
        <motion.div layout className="book-grid">
          <AnimatePresence mode="popLayout">{renderCards(visible)}</AnimatePresence>
        </motion.div>
      )}

      <AnimatePresence>
        {dragging && (
          <motion.div
            className="drop-overlay"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
          >
            <div className="drop-inner">
              <PlusIcon size={48} />
              <p>Suelta los archivos para añadirlos</p>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      <AnimatePresence>
        {importing && (
          <motion.div
            className="import-overlay"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0, pointerEvents: 'none' }}
          >
            <motion.div
              className="import-card"
              initial={{ scale: 0.92, y: 12 }}
              animate={{ scale: 1, y: 0 }}
            >
              <div className="import-spinner" />
              <h3>Importando audiolibro</h3>
              <p className="import-label">{importing.label}</p>
              <div className="import-bar">
                <div
                  className="import-bar-fill"
                  style={{
                    width: `${importing.total ? (importing.current / importing.total) * 100 : 10}%`,
                  }}
                />
              </div>
              <span className="import-count">
                {importing.current} / {importing.total} pistas
              </span>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>

      <AnimatePresence>
        {editing && (
          <BookEditor
            book={editing}
            onClose={() => setEditing(null)}
            onSaved={(updated) => onUpdated?.(updated)}
          />
        )}
      </AnimatePresence>

      <AnimatePresence>
        {statsOpen && <StatsPanel books={books} onClose={() => setStatsOpen(false)} />}
      </AnimatePresence>

      <AnimatePresence>{syncOpen && <SyncPanel onClose={() => setSyncOpen(false)} />}</AnimatePresence>
    </div>
  )
}

/** Small helper: object URL lifecycle for the hero cover. */
function HeroCover({ blob, title }) {
  const [url, setUrl] = useState(null)
  useEffect(() => {
    const u = URL.createObjectURL(blob)
    setUrl(u)
    return () => URL.revokeObjectURL(u)
  }, [blob])
  return url ? <img src={url} alt={title} draggable={false} /> : null
}
