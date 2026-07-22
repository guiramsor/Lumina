import { useEffect } from 'react'
import { usePlayer } from './PlayerContext.jsx'

const round = (n) => Math.round(n * 100) / 100

/**
 * Global keyboard shortcuts, active only while a book is loaded.
 * Space/K play-pause · ←/J back 15s · →/L forward 30s · ↑/↓ volume
 * [ / ] previous / next chapter · , / . slower / faster
 */
export function useKeyboardShortcuts() {
  const {
    book,
    togglePlay,
    skip,
    volume,
    setVolume,
    chapters,
    nextChapter,
    prevChapter,
    speed,
    setSpeed,
  } = usePlayer()

  useEffect(() => {
    if (!book) return

    const onKey = (e) => {
      const t = e.target
      if (
        t &&
        (t.tagName === 'INPUT' ||
          t.tagName === 'TEXTAREA' ||
          t.tagName === 'SELECT' ||
          t.isContentEditable)
      ) {
        return
      }
      if (e.metaKey || e.ctrlKey || e.altKey) return

      switch (e.key) {
        case ' ':
        case 'k':
        case 'K':
          e.preventDefault()
          togglePlay()
          break
        case 'ArrowLeft':
        case 'j':
        case 'J':
          e.preventDefault()
          skip(-15)
          break
        case 'ArrowRight':
        case 'l':
        case 'L':
          e.preventDefault()
          skip(30)
          break
        case 'ArrowUp':
          e.preventDefault()
          setVolume(Math.min(1, round(volume + 0.05)))
          break
        case 'ArrowDown':
          e.preventDefault()
          setVolume(Math.max(0, round(volume - 0.05)))
          break
        case '[':
          if (chapters.length > 1) {
            e.preventDefault()
            prevChapter()
          }
          break
        case ']':
          if (chapters.length > 1) {
            e.preventDefault()
            nextChapter()
          }
          break
        case ',':
          e.preventDefault()
          setSpeed(Math.max(0.5, round(speed - 0.25)))
          break
        case '.':
          e.preventDefault()
          setSpeed(Math.min(3, round(speed + 0.25)))
          break
        default:
          break
      }
    }

    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [book, togglePlay, skip, volume, setVolume, chapters.length, nextChapter, prevChapter, speed, setSpeed])
}
