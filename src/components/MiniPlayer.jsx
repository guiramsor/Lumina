import { motion } from 'framer-motion'
import { usePlayer } from '../player/PlayerContext.jsx'
import { PlayIcon, PauseIcon, BookIcon } from './Icons.jsx'

export default function MiniPlayer({ onExpand }) {
  const { book, chapters, currentChapter, isPlaying, togglePlay, globalTime, totalDuration } = usePlayer()
  if (!book) return null
  const pct = totalDuration ? (globalTime / totalDuration) * 100 : 0

  return (
    <motion.div
      className="mini-player"
      initial={{ y: 80, opacity: 0 }}
      animate={{ y: 0, opacity: 1 }}
      exit={{ y: 80, opacity: 0 }}
      transition={{ type: 'spring', stiffness: 280, damping: 30 }}
    >
      <div className="mini-progress" style={{ width: `${pct}%` }} />
      <button className="mini-cover" onClick={onExpand} title="Abrir reproductor">
        {book.coverUrl ? (
          <img src={book.coverUrl} alt={book.title} />
        ) : (
          <div className="mini-cover-fallback">
            <BookIcon size={20} />
          </div>
        )}
        <div
          className={`mini-cover-disc ${isPlaying ? 'spin' : ''}`}
          style={{ animationPlayState: isPlaying ? 'running' : 'paused' }}
        />
      </button>
      <div className="mini-info" onClick={onExpand}>
        <span className="mini-title">{book.title}</span>
        <span className="mini-sub">
          {chapters.length > 1 && currentChapter ? currentChapter.title : book.author}
        </span>
      </div>
      <button className="mini-play" onClick={togglePlay}>
        {isPlaying ? <PauseIcon size={24} /> : <PlayIcon size={24} />}
      </button>
    </motion.div>
  )
}
