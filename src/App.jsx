import { useEffect, useMemo, useState } from 'react'
import { AnimatePresence } from 'framer-motion'
import { usePlayer } from './player/PlayerContext.jsx'
import { useKeyboardShortcuts } from './player/useKeyboardShortcuts.js'
import Library from './components/Library.jsx'
import Player from './components/Player.jsx'
import ReactiveBackground from './components/ReactiveBackground.jsx'
import MiniPlayer from './components/MiniPlayer.jsx'
import HelpOverlay from './components/HelpOverlay.jsx'
import { getAllBooks, getAllProgress, deleteBook as dbDeleteBook, getSettings, putSettings } from './lib/db.js'
import { paletteToVars, DEFAULT_PALETTE, getTheme } from './lib/theme.js'

export default function App() {
  const { loadBook, book: activeBook } = usePlayer()
  useKeyboardShortcuts()
  const [books, setBooks] = useState([])
  const [progressMap, setProgressMap] = useState({})
  const [view, setView] = useState('library')
  const [loading, setLoading] = useState(true)
  const [themeId, setThemeId] = useState('noche')
  const [showHelp, setShowHelp] = useState(false)

  useEffect(() => {
    Promise.all([getAllBooks(), getAllProgress(), getSettings()]).then(([b, p, s]) => {
      setBooks(b)
      setProgressMap(p)
      setThemeId(s.theme || 'noche')
      setLoading(false)
    })
  }, [])

  // Atajo global de ayuda: ? abre, Esc cierra (funciona también sin libro cargado)
  useEffect(() => {
    const onKey = (e) => {
      const t = e.target
      if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return
      if (e.key === '?') {
        e.preventDefault()
        setShowHelp((v) => !v)
      } else if (e.key === 'Escape') {
        setShowHelp(false)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  const changeTheme = (id) => {
    setThemeId(id)
    putSettings({ theme: id })
  }

  const refreshProgress = () => getAllProgress().then(setProgressMap)

  const openBook = async (rawBook, { autoplay = false } = {}) => {
    await loadBook(rawBook, { autoplay })
    setView('player')
  }

  const goBack = () => {
    setView('library')
    refreshProgress()
    getAllBooks().then(setBooks)
  }

  const onImported = (imported) => {
    const arr = Array.isArray(imported) ? imported : [imported]
    if (!arr.length) return
    const ids = new Set(arr.map((b) => b.id))
    setBooks((prev) => [...arr, ...prev.filter((b) => !ids.has(b.id))])
  }

  const onUpdated = (updated) => {
    if (!updated) return
    setBooks((prev) => prev.map((b) => (b.id === updated.id ? updated : b)))
  }

  const onDelete = async (book) => {
    await dbDeleteBook(book.id)
    setBooks((prev) => prev.filter((b) => b.id !== book.id))
    setProgressMap((prev) => {
      const next = { ...prev }
      delete next[book.id]
      return next
    })
    if (activeBook?.id === book.id) setView('library')
  }

  const palette = useMemo(() => {
    if (view === 'player' && activeBook?.palette) return activeBook.palette
    return DEFAULT_PALETTE
  }, [view, activeBook])

  const theme = getTheme(themeId)

  return (
    <div className="app" data-theme={themeId} style={paletteToVars(palette)}>
      <ReactiveBackground palette={palette} theme={theme} />

      <AnimatePresence mode="wait">
        {view === 'library' ? (
          <div key="library" className="view-wrap">
            {!loading && (
              <Library
                books={books}
                progressMap={progressMap}
                themeId={themeId}
                onThemeChange={changeTheme}
                onShowHelp={() => setShowHelp(true)}
                onOpen={openBook}
                onDelete={onDelete}
                onImported={onImported}
                onUpdated={onUpdated}
              />
            )}
          </div>
        ) : (
          <Player key="player" onBack={goBack} />
        )}
      </AnimatePresence>

      {view === 'library' && activeBook && (
        <MiniPlayer onExpand={() => setView('player')} />
      )}

      <AnimatePresence>{showHelp && <HelpOverlay onClose={() => setShowHelp(false)} />}</AnimatePresence>
    </div>
  )
}
