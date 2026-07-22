import { useState } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { usePlayer } from '../player/PlayerContext.jsx'
import { formatTime } from '../lib/format.js'
import {
  PlayIcon,
  PauseIcon,
  Back15Icon,
  Fwd30Icon,
  PrevIcon,
  NextIcon,
} from './Icons.jsx'

const SPEEDS = [0.75, 1, 1.25, 1.5, 1.75, 2, 2.5]

export default function Controls() {
  const {
    isPlaying,
    isBuffering,
    globalTime,
    totalDuration,
    togglePlay,
    skip,
    nextChapter,
    prevChapter,
    seekGlobal,
    speed,
    setSpeed,
    chapters,
    currentChapterIndex,
  } = usePlayer()

  const [scrubbing, setScrubbing] = useState(false)
  const [scrubValue, setScrubValue] = useState(0)
  const [speedOpen, setSpeedOpen] = useState(false)

  const display = scrubbing ? scrubValue : globalTime
  const multiTrack = chapters.length > 1

  return (
    <div className="controls">
      <div className="scrubber">
        <span className="time-label">{formatTime(display)}</span>
        <div className="scrubber-track-wrap">
          <div className="scrubber-track">
            <div
              className="scrubber-fill"
              style={{ width: `${totalDuration ? (display / totalDuration) * 100 : 0}%` }}
            />
            <div
              className="scrubber-thumb"
              style={{ left: `${totalDuration ? (display / totalDuration) * 100 : 0}%` }}
            />
          </div>
          <input
            type="range"
            min={0}
            max={totalDuration || 0}
            step={1}
            value={display}
            aria-label="Posición"
            onPointerDown={() => {
              setScrubbing(true)
              setScrubValue(globalTime)
            }}
            onChange={(e) => {
              const v = Number(e.target.value)
              setScrubValue(v)
              // Keyboard changes arrive without pointer events: apply directly.
              if (!scrubbing) seekGlobal(v)
            }}
            onPointerUp={() => {
              seekGlobal(scrubValue)
              setScrubbing(false)
            }}
          />
        </div>
        <span className="time-label">{formatTime(totalDuration)}</span>
      </div>

      <div className="transport">
        {multiTrack && (
          <button className="icon-btn" title="Capítulo anterior" onClick={prevChapter}>
            <PrevIcon size={26} />
          </button>
        )}
        <button className="icon-btn" title="Retroceder 15s" onClick={() => skip(-15)}>
          <Back15Icon size={28} />
        </button>

        <motion.button
          className="play-btn"
          onClick={togglePlay}
          whileTap={{ scale: 0.92 }}
          whileHover={{ scale: 1.04 }}
          title={isPlaying ? 'Pausar' : 'Reproducir'}
        >
          <AnimatePresence mode="popLayout" initial={false}>
            <motion.span
              key={isPlaying ? 'pause' : 'play'}
              initial={{ opacity: 0, scale: 0.5, rotate: -30 }}
              animate={{ opacity: 1, scale: 1, rotate: 0 }}
              exit={{ opacity: 0, scale: 0.5, rotate: 30 }}
              transition={{ duration: 0.18 }}
              className="play-btn-icon"
            >
              {isPlaying ? <PauseIcon size={34} /> : <PlayIcon size={34} />}
            </motion.span>
          </AnimatePresence>
          {isBuffering && <span className="play-btn-ring" />}
        </motion.button>

        <button className="icon-btn" title="Avanzar 30s" onClick={() => skip(30)}>
          <Fwd30Icon size={28} />
        </button>
        {multiTrack && (
          <button
            className="icon-btn"
            title="Capítulo siguiente"
            onClick={nextChapter}
            disabled={currentChapterIndex >= chapters.length - 1}
          >
            <NextIcon size={26} />
          </button>
        )}
      </div>

      <div className="speed-control">
        <button className="speed-chip" onClick={() => setSpeedOpen((v) => !v)}>
          {speed}×<span className="speed-chip-label">velocidad</span>
        </button>
        <AnimatePresence>
          {speedOpen && (
            <motion.div
              className="speed-menu"
              initial={{ opacity: 0, y: 8, scale: 0.96 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={{ opacity: 0, y: 8, scale: 0.96 }}
            >
              {SPEEDS.map((s) => (
                <button
                  key={s}
                  className={`speed-option ${s === speed ? 'active' : ''}`}
                  onClick={() => {
                    setSpeed(s)
                    setSpeedOpen(false)
                  }}
                >
                  {s}×
                </button>
              ))}
            </motion.div>
          )}
        </AnimatePresence>
      </div>
    </div>
  )
}
