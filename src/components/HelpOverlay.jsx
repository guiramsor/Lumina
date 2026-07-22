import { motion } from 'framer-motion'
import { CloseIcon, HelpIcon } from './Icons.jsx'

const GROUPS = [
  {
    title: 'Reproducción',
    rows: [
      { keys: ['Espacio', 'K'], label: 'Reproducir / pausar' },
      { keys: ['←', 'J'], label: 'Retroceder 15 s' },
      { keys: ['→', 'L'], label: 'Avanzar 30 s' },
      { keys: [','], label: 'Más despacio' },
      { keys: ['.'], label: 'Más deprisa' },
    ],
  },
  {
    title: 'Navegación',
    rows: [
      { keys: ['['], label: 'Capítulo anterior' },
      { keys: [']'], label: 'Capítulo siguiente' },
      { keys: ['↑', '↓'], label: 'Subir / bajar volumen' },
    ],
  },
  {
    title: 'General',
    rows: [
      { keys: ['?'], label: 'Mostrar esta ayuda' },
      { keys: ['Esc'], label: 'Cerrar paneles' },
    ],
  },
]

export default function HelpOverlay({ onClose }) {
  return (
    <motion.div
      className="help-overlay"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0, pointerEvents: 'none' }}
      onClick={onClose}
    >
      <motion.div
        className="help-card"
        onClick={(e) => e.stopPropagation()}
        initial={{ scale: 0.94, y: 18, opacity: 0 }}
        animate={{ scale: 1, y: 0, opacity: 1 }}
        exit={{ scale: 0.94, y: 18, opacity: 0 }}
        transition={{ type: 'spring', stiffness: 260, damping: 26 }}
      >
        <div className="help-head">
          <h3>
            <HelpIcon size={20} /> Atajos de teclado
          </h3>
          <button className="icon-btn ghost" onClick={onClose} title="Cerrar">
            <CloseIcon size={20} />
          </button>
        </div>
        <div className="help-groups">
          {GROUPS.map((g) => (
            <div key={g.title} className="help-group">
              <h4>{g.title}</h4>
              {g.rows.map((r) => (
                <div key={r.label} className="help-row">
                  <span className="help-keys">
                    {r.keys.map((k) => (
                      <kbd key={k}>{k}</kbd>
                    ))}
                  </span>
                  <span className="help-label">{r.label}</span>
                </div>
              ))}
            </div>
          ))}
        </div>
        <p className="help-note">Los atajos de reproducción funcionan con un libro cargado.</p>
      </motion.div>
    </motion.div>
  )
}
