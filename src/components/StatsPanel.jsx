import { useEffect, useMemo, useState } from 'react'
import { motion } from 'framer-motion'
import { getAllStats, todayKey } from '../lib/db.js'
import { CloseIcon, ChartIcon, FlameIcon } from './Icons.jsx'

const DAY_LABELS = ['D', 'L', 'M', 'X', 'J', 'V', 'S']

function fmtHours(seconds) {
  if (!seconds) return '0 min'
  const totalMinutes = Math.round(seconds / 60)
  const h = Math.floor(totalMinutes / 60)
  const m = totalMinutes % 60
  if (h > 0) return `${h} h ${m} min`
  return `${m} min`
}

export default function StatsPanel({ books, onClose }) {
  const [rows, setRows] = useState(null)

  useEffect(() => {
    getAllStats().then(setRows)
  }, [])

  const stats = useMemo(() => {
    if (!rows) return null
    const byDay = new Map(rows.map((r) => [r.day, r]))

    // Últimos 7 días (incluye hoy)
    const days = []
    for (let i = 6; i >= 0; i--) {
      const d = new Date()
      d.setDate(d.getDate() - i)
      const key = todayKey(d)
      days.push({
        key,
        label: DAY_LABELS[d.getDay()],
        seconds: byDay.get(key)?.seconds || 0,
        isToday: i === 0,
      })
    }

    const today = days[6].seconds
    const week = days.reduce((sum, d) => sum + d.seconds, 0)
    const total = rows.reduce((sum, r) => sum + r.seconds, 0)

    // Racha: días consecutivos con al menos 1 minuto (hoy puede estar aún a 0)
    let streak = 0
    const probe = new Date()
    if ((byDay.get(todayKey(probe))?.seconds || 0) >= 60) streak++
    probe.setDate(probe.getDate() - 1)
    while ((byDay.get(todayKey(probe))?.seconds || 0) >= 60) {
      streak++
      probe.setDate(probe.getDate() - 1)
    }

    // Top libros por tiempo escuchado
    const perBook = new Map()
    for (const r of rows) {
      for (const [id, secs] of Object.entries(r.perBook || {})) {
        perBook.set(id, (perBook.get(id) || 0) + secs)
      }
    }
    const titleOf = (id) => books.find((b) => b.id === id)?.title
    const top = [...perBook.entries()]
      .map(([id, secs]) => ({ id, secs, title: titleOf(id) }))
      .filter((x) => x.title)
      .sort((a, b) => b.secs - a.secs)
      .slice(0, 5)

    const maxDay = Math.max(1, ...days.map((d) => d.seconds))
    return { days, today, week, total, streak, top, maxDay }
  }, [rows, books])

  return (
    <motion.div
      className="stats-overlay"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0, pointerEvents: 'none' }}
      onClick={onClose}
    >
      <motion.div
        className="stats-card"
        onClick={(e) => e.stopPropagation()}
        initial={{ scale: 0.94, y: 18, opacity: 0 }}
        animate={{ scale: 1, y: 0, opacity: 1 }}
        exit={{ scale: 0.94, y: 18, opacity: 0 }}
        transition={{ type: 'spring', stiffness: 260, damping: 26 }}
      >
        <div className="stats-head">
          <h3>
            <ChartIcon size={20} /> Tu escucha
          </h3>
          <button className="icon-btn ghost" onClick={onClose} title="Cerrar">
            <CloseIcon size={20} />
          </button>
        </div>

        {!stats ? (
          <p className="panel-empty">Cargando…</p>
        ) : (
          <>
            <div className="stats-tiles">
              <div className="stat-tile">
                <span className="stat-value">{fmtHours(stats.today)}</span>
                <span className="stat-label">Hoy</span>
              </div>
              <div className="stat-tile">
                <span className="stat-value">{fmtHours(stats.week)}</span>
                <span className="stat-label">Últimos 7 días</span>
              </div>
              <div className="stat-tile">
                <span className="stat-value">
                  <FlameIcon size={17} /> {stats.streak}
                </span>
                <span className="stat-label">{stats.streak === 1 ? 'día de racha' : 'días de racha'}</span>
              </div>
              <div className="stat-tile">
                <span className="stat-value">{fmtHours(stats.total)}</span>
                <span className="stat-label">Total</span>
              </div>
            </div>

            <div className="stats-week">
              {stats.days.map((d) => (
                <div key={d.key} className={`stats-day ${d.isToday ? 'today' : ''}`} title={fmtHours(d.seconds)}>
                  <div className="stats-bar-track">
                    <motion.div
                      className="stats-bar"
                      initial={{ height: 0 }}
                      animate={{ height: `${Math.max(d.seconds > 0 ? 6 : 0, (d.seconds / stats.maxDay) * 100)}%` }}
                      transition={{ duration: 0.6, ease: [0.22, 1, 0.36, 1] }}
                    />
                  </div>
                  <span>{d.label}</span>
                </div>
              ))}
            </div>

            {stats.top.length > 0 && (
              <div className="stats-top">
                <h4>Libros más escuchados</h4>
                {stats.top.map((t) => (
                  <div key={t.id} className="stats-top-row">
                    <span className="stats-top-title">{t.title}</span>
                    <span className="stats-top-time">{fmtHours(t.secs)}</span>
                  </div>
                ))}
              </div>
            )}

            {stats.total === 0 && (
              <p className="panel-empty">Todavía no hay datos: dale al play y vuelve por aquí.</p>
            )}
          </>
        )}
      </motion.div>
    </motion.div>
  )
}
