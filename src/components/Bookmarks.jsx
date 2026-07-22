import { useEffect, useState } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { usePlayer } from '../player/PlayerContext.jsx'
import { getBookmarks, addBookmark, deleteBookmark } from '../lib/db.js'
import { formatTime } from '../lib/format.js'
import { BookmarkFilledIcon, PlusIcon, TrashIcon, PlayCircleIcon } from './Icons.jsx'

export default function Bookmarks() {
  const { book, globalTime, trackIndex, currentChapter, seekGlobal, play } = usePlayer()
  const [list, setList] = useState([])
  const [note, setNote] = useState('')
  const [adding, setAdding] = useState(false)

  useEffect(() => {
    if (!book) return
    getBookmarks(book.id).then(setList)
  }, [book])

  const save = async () => {
    const record = await addBookmark({
      bookId: book.id,
      globalTime,
      trackIndex,
      trackTitle: currentChapter?.title || '',
      label: note.trim() || `Marcador en ${formatTime(globalTime)}`,
    })
    setList((l) => [...l, record].sort((a, b) => a.globalTime - b.globalTime))
    setNote('')
    setAdding(false)
  }

  const remove = async (id) => {
    await deleteBookmark(id)
    setList((l) => l.filter((b) => b.id !== id))
  }

  const jump = (bm) => {
    seekGlobal(bm.globalTime)
    play()
  }

  return (
    <div className="panel bookmarks-panel">
      <div className="panel-head">
        <h3>
          <BookmarkFilledIcon size={20} /> Marcadores
        </h3>
        <button className="btn small primary" onClick={() => setAdding((v) => !v)}>
          <PlusIcon size={16} /> Aquí
        </button>
      </div>

      <AnimatePresence>
        {adding && (
          <motion.div
            className="bookmark-add"
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: 'auto' }}
            exit={{ opacity: 0, height: 0 }}
          >
            <span className="bookmark-add-time">{formatTime(globalTime)}</span>
            <input
              autoFocus
              type="text"
              placeholder="Nota (opcional)…"
              value={note}
              onChange={(e) => setNote(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && save()}
            />
            <button className="btn small primary" onClick={save}>
              Guardar
            </button>
          </motion.div>
        )}
      </AnimatePresence>

      <div className="bookmark-list">
        {list.length === 0 && <p className="panel-empty">Aún no hay marcadores. Pulsa “Aquí” para guardar el momento.</p>}
        <AnimatePresence initial={false}>
          {list.map((bm) => (
            <motion.div
              key={bm.id}
              className="bookmark-item"
              layout
              initial={{ opacity: 0, x: 20 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: -20 }}
            >
              <button className="bookmark-jump" onClick={() => jump(bm)} title="Ir a este punto">
                <PlayCircleIcon size={22} />
              </button>
              <div className="bookmark-info" onClick={() => jump(bm)}>
                <span className="bookmark-label">{bm.label}</span>
                <span className="bookmark-meta">
                  {bm.trackTitle ? `${bm.trackTitle} · ` : ''}
                  {formatTime(bm.globalTime)}
                </span>
              </div>
              <button className="icon-btn ghost tiny" onClick={() => remove(bm.id)} title="Eliminar">
                <TrashIcon size={16} />
              </button>
            </motion.div>
          ))}
        </AnimatePresence>
      </div>
    </div>
  )
}
