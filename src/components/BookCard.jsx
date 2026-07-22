import { useEffect, useMemo, useState } from 'react'
import { motion } from 'framer-motion'
import { formatDurationWords } from '../lib/format.js'
import { TrashIcon, PlayIcon, BookIcon, EditIcon } from './Icons.jsx'

function initials(title) {
  return (title || '?')
    .split(/\s+/)
    .slice(0, 2)
    .map((w) => w[0])
    .join('')
    .toUpperCase()
}

function ProgressRing({ pct }) {
  const r = 15.5
  const c = 2 * Math.PI * r
  const offset = c * (1 - Math.min(100, Math.max(0, pct)) / 100)
  return (
    <div className="cover-ring" aria-hidden>
      <svg viewBox="0 0 36 36">
        <circle className="cover-ring-track" cx="18" cy="18" r={r} />
        <motion.circle
          className="cover-ring-bar"
          cx="18"
          cy="18"
          r={r}
          transform="rotate(-90 18 18)"
          strokeDasharray={c}
          initial={{ strokeDashoffset: c }}
          animate={{ strokeDashoffset: offset }}
          transition={{ duration: 0.9, ease: [0.22, 1, 0.36, 1] }}
        />
      </svg>
      <span className="cover-ring-pct">{Math.round(pct)}%</span>
    </div>
  )
}

export default function BookCard({ book, progress, onOpen, onDelete, onEdit, index }) {
  const [coverUrl, setCoverUrl] = useState(null)
  const [confirming, setConfirming] = useState(false)

  useEffect(() => {
    if (!book.coverBlob) return
    const url = URL.createObjectURL(book.coverBlob)
    setCoverUrl(url)
    return () => URL.revokeObjectURL(url)
  }, [book.coverBlob])

  const pct = useMemo(() => {
    if (!progress || !book.totalDuration) return 0
    if (progress.finished) return 100
    return Math.min(100, Math.round((progress.globalTime / book.totalDuration) * 100))
  }, [progress, book.totalDuration])

  const accent = `hsl(${book.palette?.hue ?? 265} ${book.palette?.sat ?? 60}% ${book.palette?.light ?? 60}%)`

  return (
    <motion.div
      layout
      className="book-card"
      style={{ '--card-accent': accent }}
      initial={{ opacity: 0, y: 28, scale: 0.94 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      exit={{ opacity: 0, scale: 0.9, transition: { duration: 0.2 } }}
      transition={{ delay: Math.min(index * 0.05, 0.4), type: 'spring', stiffness: 220, damping: 24 }}
      whileHover={{ y: -8 }}
    >
      <motion.button
        className="book-card-cover"
        onClick={() => onOpen(book)}
        layoutId={`cover-${book.id}`}
        style={{ borderRadius: 18 }}
        whileTap={{ scale: 0.97 }}
      >
        {coverUrl ? (
          <img src={coverUrl} alt={book.title} draggable={false} />
        ) : (
          <div className="book-card-placeholder">
            <BookIcon size={34} />
            <span>{initials(book.title)}</span>
          </div>
        )}
        {(book.series || book.seriesIndex != null) && (
          <span className="book-card-series">
            {book.series
              ? book.seriesIndex != null
                ? `${book.series} · ${book.seriesIndex}`
                : book.series
              : `N.º ${book.seriesIndex}`}
          </span>
        )}
        <div className="book-card-shine" />
        <div className="book-card-play">
          <PlayIcon size={26} />
        </div>
        {progress?.finished ? (
          <div className="book-card-done">✓ Terminado</div>
        ) : (
          pct > 0 && <ProgressRing pct={pct} />
        )}
      </motion.button>

      <div className="book-card-meta">
        <div className="book-card-title" title={book.title}>
          {book.title}
        </div>
        <div className="book-card-sub">
          {book.author || 'Autor desconocido'}
          {book.tracks.length > 1 ? ` · ${book.tracks.length} cap.` : ''}
        </div>
        <div className="book-card-foot">
          <span>{formatDurationWords(book.totalDuration)}</span>
        </div>
      </div>

      <div className={`book-card-actions ${confirming ? 'confirm' : ''}`}>
        {confirming ? (
          <>
            <button className="mini danger" onClick={() => onDelete(book)}>
              Eliminar
            </button>
            <button className="mini" onClick={() => setConfirming(false)}>
              No
            </button>
          </>
        ) : (
          <>
            <button className="icon-btn ghost" title="Editar" onClick={() => onEdit?.(book)}>
              <EditIcon size={17} />
            </button>
            <button className="icon-btn ghost" title="Eliminar" onClick={() => setConfirming(true)}>
              <TrashIcon size={18} />
            </button>
          </>
        )}
      </div>
    </motion.div>
  )
}
