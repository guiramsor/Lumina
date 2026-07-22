import { useEffect, useMemo, useRef } from 'react'
import { usePlayer } from '../player/PlayerContext.jsx'
import { formatTime } from '../lib/format.js'
import { ListIcon } from './Icons.jsx'

export default function ChapterList() {
  const { book, chapters, currentChapterIndex, jumpToChapter, totalDuration, globalTime } = usePlayer()
  const activeRef = useRef(null)

  useEffect(() => {
    activeRef.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }, [currentChapterIndex])

  const durations = useMemo(() => {
    return chapters.map((ch, i) => {
      const end = i < chapters.length - 1 ? chapters[i + 1].globalStart : totalDuration
      return Math.max(0, end - ch.globalStart)
    })
  }, [chapters, totalDuration])

  if (!book) return null
  const single = chapters.length <= 1

  const activeDur = durations[currentChapterIndex] || 0
  const activeElapsed = Math.min(activeDur, Math.max(0, globalTime - (chapters[currentChapterIndex]?.globalStart || 0)))
  const activePct = activeDur ? (activeElapsed / activeDur) * 100 : 0

  return (
    <div className="panel chapter-panel">
      <div className="panel-head">
        <h3>
          <ListIcon size={20} /> {single ? 'Pista' : 'Capítulos'}
        </h3>
        <span className="panel-count">{chapters.length}</span>
      </div>
      <div className="chapter-list">
        {chapters.map((ch, i) => {
          const active = i === currentChapterIndex
          return (
            <button
              key={i}
              ref={active ? activeRef : null}
              className={`chapter-item ${active ? 'active' : ''}`}
              onClick={() => jumpToChapter(i)}
            >
              <span className="chapter-index">
                {active ? <span className="chapter-eq" /> : i + 1}
              </span>
              <span className="chapter-main">
                <span className="chapter-title">{ch.title}</span>
                {active && (
                  <span className="chapter-progress">
                    <span className="chapter-progress-fill" style={{ width: `${activePct}%` }} />
                  </span>
                )}
              </span>
              <span className="chapter-dur">{formatTime(durations[i])}</span>
            </button>
          )
        })}
      </div>
    </div>
  )
}
