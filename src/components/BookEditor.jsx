import { useEffect, useRef, useState } from 'react'
import { motion } from 'framer-motion'
import { updateBook } from '../lib/db.js'
import { extractPalette } from '../lib/metadata.js'
import { CloseIcon, BookIcon } from './Icons.jsx'

export default function BookEditor({ book, onClose, onSaved }) {
  const [title, setTitle] = useState(book.title || '')
  const [author, setAuthor] = useState(book.author || '')
  const [series, setSeries] = useState(book.series || '')
  const [seriesIndex, setSeriesIndex] = useState(book.seriesIndex != null ? String(book.seriesIndex) : '')
  const [coverBlob, setCoverBlob] = useState(null)
  const [coverUrl, setCoverUrl] = useState(null)
  const [coverFit, setCoverFit] = useState(book.coverFit || 'cover')
  const [saving, setSaving] = useState(false)
  const fileRef = useRef(null)

  const FITS = [
    { id: 'cover', label: 'Recortar', hint: 'Rellena el libro recortando los bordes.' },
    { id: 'fill', label: 'Estirar', hint: 'Muestra la imagen entera, estirada al libro.' },
    { id: 'contain', label: 'Completa', hint: 'Muestra la imagen entera sin recortar ni deformar.' },
  ]

  useEffect(() => {
    const blob = coverBlob || book.coverBlob
    if (!blob) {
      setCoverUrl(null)
      return
    }
    const url = URL.createObjectURL(blob)
    setCoverUrl(url)
    return () => URL.revokeObjectURL(url)
  }, [coverBlob, book.coverBlob])

  const pickCover = (e) => {
    const f = e.target.files?.[0]
    if (f) setCoverBlob(f)
  }

  const save = async () => {
    setSaving(true)
    const idx = parseFloat(seriesIndex.replace(',', '.'))
    const patch = {
      title: title.trim() || book.title,
      author: author.trim(),
      coverFit,
      series: series.trim(),
      seriesIndex: isNaN(idx) ? null : idx,
    }
    if (coverBlob) {
      patch.coverBlob = coverBlob
      patch.palette = await extractPalette(coverBlob)
    }
    const updated = await updateBook(book.id, patch)
    setSaving(false)
    if (updated) onSaved?.(updated)
    onClose?.()
  }

  return (
    <motion.div
      className="editor-overlay"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0, pointerEvents: 'none' }}
      onClick={onClose}
    >
      <motion.div
        className="editor-card"
        onClick={(e) => e.stopPropagation()}
        initial={{ scale: 0.94, y: 16, opacity: 0 }}
        animate={{ scale: 1, y: 0, opacity: 1 }}
        exit={{ scale: 0.94, y: 16, opacity: 0 }}
        transition={{ type: 'spring', stiffness: 260, damping: 26 }}
      >
        <div className="editor-head">
          <h3>Editar audiolibro</h3>
          <button className="icon-btn ghost" onClick={onClose} title="Cerrar">
            <CloseIcon size={20} />
          </button>
        </div>

        <div className="editor-body">
          <button className="editor-cover" onClick={() => fileRef.current?.click()} title="Cambiar portada">
            {coverUrl ? (
              <img src={coverUrl} alt={title} style={{ objectFit: coverFit }} />
            ) : (
              <div className="editor-cover-empty">
                <BookIcon size={30} />
              </div>
            )}
            <span className="editor-cover-overlay">Cambiar portada</span>
          </button>
          <input ref={fileRef} type="file" accept="image/*" hidden onChange={pickCover} />

          <div className="editor-fields">
            <label>
              <span>Título</span>
              <input value={title} onChange={(e) => setTitle(e.target.value)} autoFocus />
            </label>
            <label>
              <span>Autor</span>
              <input
                value={author}
                onChange={(e) => setAuthor(e.target.value)}
                placeholder="Autor desconocido"
              />
            </label>
            <div className="editor-series-row">
              <label>
                <span>Serie</span>
                <input
                  value={series}
                  onChange={(e) => setSeries(e.target.value)}
                  placeholder="p. ej. Nacidos de la Bruma"
                />
              </label>
              <label className="editor-series-index">
                <span>N.º</span>
                <input
                  value={seriesIndex}
                  onChange={(e) => setSeriesIndex(e.target.value)}
                  placeholder="—"
                  inputMode="decimal"
                />
              </label>
            </div>
          </div>
        </div>

        <div className="editor-fit">
          <span className="editor-fit-label">Ajuste de la imagen en el libro 3D</span>
          <div className="fit-toggle">
            {FITS.map((f) => (
              <button
                key={f.id}
                type="button"
                title={f.hint}
                className={coverFit === f.id ? 'active' : ''}
                onClick={() => setCoverFit(f.id)}
              >
                {f.label}
              </button>
            ))}
          </div>
        </div>

        <div className="editor-foot">
          <button className="btn ghost" onClick={onClose}>
            Cancelar
          </button>
          <button className="btn primary" onClick={save} disabled={saving}>
            {saving ? 'Guardando…' : 'Guardar'}
          </button>
        </div>
      </motion.div>
    </motion.div>
  )
}
