import { useEffect, useState } from 'react'
import { motion } from 'framer-motion'
import { isConfigured, signIn, signOut, currentUser } from '../lib/sync.js'
import { CloseIcon, CloudIcon } from './Icons.jsx'

export default function SyncPanel({ onClose }) {
  const [user, setUser] = useState(null)
  const [cargando, setCargando] = useState(true)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [entrando, setEntrando] = useState(false)

  useEffect(() => {
    currentUser()
      .then(setUser)
      .finally(() => setCargando(false))
  }, [])

  const entrar = async (e) => {
    e.preventDefault()
    setError(null)
    setEntrando(true)
    try {
      setUser(await signIn(email.trim(), password))
      setPassword('')
    } catch (err) {
      setError(err.message)
    } finally {
      setEntrando(false)
    }
  }

  const salir = async () => {
    await signOut()
    setUser(null)
  }

  return (
    <motion.div
      className="stats-overlay"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0, pointerEvents: 'none' }}
      onClick={onClose}
    >
      <motion.div
        className="stats-card sync-card"
        onClick={(e) => e.stopPropagation()}
        initial={{ scale: 0.94, y: 18, opacity: 0 }}
        animate={{ scale: 1, y: 0, opacity: 1 }}
        exit={{ scale: 0.94, y: 18, opacity: 0 }}
        transition={{ type: 'spring', stiffness: 260, damping: 26 }}
      >
        <div className="stats-head">
          <h3>
            <CloudIcon size={20} /> Sincronización
          </h3>
          <button className="icon-btn ghost" onClick={onClose} title="Cerrar">
            <CloseIcon size={20} />
          </button>
        </div>

        {!isConfigured() ? (
          <p className="panel-empty">
            Esta versión se compiló sin credenciales de sincronización. Rellena el archivo
            <code> .env.local</code> y vuelve a compilar con <code>npm run dist</code>.
          </p>
        ) : cargando ? (
          <p className="panel-empty">Comprobando…</p>
        ) : user ? (
          <div className="sync-active">
            <div className="sync-state">
              <span className="sync-dot" />
              <div>
                <strong>Sincronización activa</strong>
                <p>{user.email}</p>
              </div>
            </div>
            <p className="sync-hint">
              Tu posición de escucha se guarda en la nube al pausar y cada 30 segundos. Inicia sesión con
              esta misma cuenta en el móvil para retomar donde lo dejaste.
            </p>
            <p className="sync-hint sync-hint-quiet">
              Los audios nunca se suben: cada dispositivo usa su propia copia del archivo.
            </p>
            <div className="editor-foot">
              <button className="btn ghost" onClick={salir}>
                Cerrar sesión
              </button>
            </div>
          </div>
        ) : (
          <form className="sync-form" onSubmit={entrar}>
            <p className="sync-hint">
              Entra con la cuenta que creaste en Supabase. La misma en el PC y en el móvil.
            </p>
            <label>
              <span>Correo</span>
              <input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                autoFocus
                required
              />
            </label>
            <label>
              <span>Contraseña</span>
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
              />
            </label>
            {error && <p className="sync-error">{error}</p>}
            <div className="editor-foot">
              <button className="btn primary" type="submit" disabled={entrando}>
                {entrando ? 'Entrando…' : 'Iniciar sesión'}
              </button>
            </div>
          </form>
        )}
      </motion.div>
    </motion.div>
  )
}
