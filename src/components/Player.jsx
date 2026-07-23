import { useState } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { usePlayer } from '../player/PlayerContext.jsx'
import Vinyl from './Vinyl.jsx'
import Book3D from './Book3D.jsx'
import Controls from './Controls.jsx'
import SleepTimer from './SleepTimer.jsx'
import Bookmarks from './Bookmarks.jsx'
import ChapterList from './ChapterList.jsx'
import { formatTime } from '../lib/format.js'
import {
  ChevronLeftIcon,
  DiscIcon,
  BookIcon,
  MoonIcon,
  BookmarkIcon,
  ListIcon,
  CloudIcon,
} from './Icons.jsx'
import { isConfigured } from '../lib/sync.js'

/**
 * Estado de la sincronización, en la esquina de la barra superior.
 *
 * Deliberadamente pequeño: es información de fondo. El detalle vive en el
 * tooltip, que en escritorio es lo natural, en vez de ocupar sitio en la
 * pantalla del libro.
 */
function SyncBadge() {
  const { syncState, syncedAt } = usePlayer()
  if (!isConfigured()) return null

  const hace = syncedAt ? Math.round((Date.now() - syncedAt) / 1000) : null
  const titulo = {
    subiendo: 'Guardando la posición en la nube…',
    hecho:
      hace == null
        ? 'Posición sincronizada'
        : hace < 60
          ? `Posición sincronizada hace ${hace} s`
          : `Posición sincronizada hace ${Math.round(hace / 60)} min`,
    fallo: 'No se pudo contactar con la nube. La posición está guardada en este equipo.',
    inactivo: 'La posición se sincroniza al pausar y cada 30 segundos.',
  }[syncState]

  return (
    <span className={`sync-badge ${syncState}`} title={titulo}>
      <CloudIcon size={18} />
    </span>
  )
}

export default function Player({ onBack }) {
  const { book, chapters, currentChapter, visualMode, setVisualMode, isPlaying, sleep, globalTime, totalDuration } =
    usePlayer()
  const [panel, setPanel] = useState(null)

  if (!book) return null

  const togglePanel = (name) => setPanel((p) => (p === name ? null : name))
  const remainingTotal = Math.max(0, totalDuration - globalTime)

  return (
    <motion.div
      className="player"
      initial={{ opacity: 0, scale: 1.04 }}
      animate={{ opacity: 1, scale: 1 }}
      exit={{ opacity: 0, scale: 1.04 }}
      transition={{ duration: 0.4, ease: [0.22, 1, 0.36, 1] }}
    >
      <div className="player-topbar">
        <button className="icon-btn ghost" onClick={onBack} title="Volver a la biblioteca">
          <ChevronLeftIcon size={24} />
        </button>

        <div className="visual-toggle" role="tablist">
          <button
            className={visualMode === 'vinyl' ? 'active' : ''}
            onClick={() => setVisualMode('vinyl')}
            title="Vinilo"
          >
            <DiscIcon size={18} /> Vinilo
          </button>
          <button
            className={visualMode === 'book' ? 'active' : ''}
            onClick={() => setVisualMode('book')}
            title="Libro"
          >
            <BookIcon size={18} /> Libro
          </button>
          <motion.span
            className="visual-toggle-pill"
            animate={{ x: visualMode === 'vinyl' ? 0 : '100%' }}
            transition={{ type: 'spring', stiffness: 320, damping: 30 }}
          />
        </div>

        <div className="topbar-spacer">
          <SyncBadge />
        </div>
      </div>

      <div className="player-main">
        <AnimatePresence mode="wait">
          {visualMode === 'vinyl' ? (
            <motion.div key="vinyl" className="visual-holder">
              <Vinyl
                coverUrl={book.coverUrl}
                title={book.title}
                isPlaying={isPlaying}
                bookId={book.id}
                palette={book.palette}
              />
            </motion.div>
          ) : (
            <motion.div key="book" className="visual-holder">
              <Book3D
                coverUrl={book.coverUrl}
                title={book.title}
                author={book.author}
                isPlaying={isPlaying}
                coverFit={book.coverFit}
                bookId={book.id}
              />
            </motion.div>
          )}
        </AnimatePresence>
      </div>

      <div className="player-info">
        <h2 className="player-title">{book.title}</h2>
        {book.author && <p className="player-author">{book.author}</p>}
        {chapters.length > 1 && currentChapter && (
          <p className="player-chapter">{currentChapter.title}</p>
        )}
        <p className="player-remaining">Quedan {formatTime(remainingTotal)}</p>
      </div>

      <Controls />

      <div className="player-toolbar">
        <button
          className={`tool-btn ${panel === 'sleep' ? 'active' : ''} ${sleep.mode ? 'lit' : ''}`}
          onClick={() => togglePanel('sleep')}
        >
          <MoonIcon size={20} />
          <span>{sleep.mode === 'timer' ? formatTime(sleep.remaining) : 'Sueño'}</span>
        </button>
        <button
          className={`tool-btn ${panel === 'bookmarks' ? 'active' : ''}`}
          onClick={() => togglePanel('bookmarks')}
        >
          <BookmarkIcon size={20} />
          <span>Marcadores</span>
        </button>
        <button
          className={`tool-btn ${panel === 'chapters' ? 'active' : ''}`}
          onClick={() => togglePanel('chapters')}
        >
          <ListIcon size={20} />
          <span>{chapters.length > 1 ? 'Capítulos' : 'Pista'}</span>
        </button>
      </div>

      <AnimatePresence>
        {panel && (
          <>
            <motion.div
              className="panel-scrim"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0, pointerEvents: 'none' }}
              onClick={() => setPanel(null)}
            />
            <motion.div
              className="panel-dock"
              initial={{ opacity: 0, y: 40, x: 0 }}
              animate={{ opacity: 1, y: 0, x: 0 }}
              exit={{ opacity: 0, y: 40 }}
              transition={{ type: 'spring', stiffness: 260, damping: 28 }}
            >
              {panel === 'sleep' && <SleepTimer onClose={() => setPanel(null)} />}
              {panel === 'bookmarks' && <Bookmarks />}
              {panel === 'chapters' && <ChapterList />}
            </motion.div>
          </>
        )}
      </AnimatePresence>
    </motion.div>
  )
}
