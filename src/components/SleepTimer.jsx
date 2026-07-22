import { usePlayer } from '../player/PlayerContext.jsx'
import { formatTime } from '../lib/format.js'
import { MoonIcon } from './Icons.jsx'

const OPTIONS = [5, 10, 15, 30, 45, 60]

export default function SleepTimer({ onClose }) {
  const { sleep, startSleepTimer, startSleepEndOfChapter, cancelSleep, book } = usePlayer()
  const active = sleep.mode != null

  return (
    <div className="panel sleep-panel">
      <div className="panel-head">
        <h3>
          <MoonIcon size={20} /> Temporizador de sueño
        </h3>
      </div>

      {active ? (
        <div className="sleep-active">
          {sleep.mode === 'timer' ? (
            <>
              <div className="sleep-countdown">{formatTime(sleep.remaining)}</div>
              <p>Se pausará suavemente al llegar a cero.</p>
            </>
          ) : (
            <>
              <div className="sleep-countdown small">Fin del capítulo</div>
              <p>Se pausará cuando termine la pista actual.</p>
            </>
          )}
          <button className="btn ghost" onClick={cancelSleep}>
            Cancelar temporizador
          </button>
        </div>
      ) : (
        <>
          <div className="sleep-grid">
            {OPTIONS.map((min) => (
              <button
                key={min}
                className="sleep-option"
                onClick={() => {
                  startSleepTimer(min)
                  onClose?.()
                }}
              >
                {min}
                <span>min</span>
              </button>
            ))}
          </div>
          {book && book.tracks.length > 1 && (
            <button
              className="btn ghost full"
              onClick={() => {
                startSleepEndOfChapter()
                onClose?.()
              }}
            >
              Hasta el fin del capítulo
            </button>
          )}
        </>
      )}
    </div>
  )
}
