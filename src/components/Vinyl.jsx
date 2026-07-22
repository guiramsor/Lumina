import { useEffect, useRef } from 'react'
import { motion } from 'framer-motion'
import { usePlayer } from '../player/PlayerContext.jsx'
import { BookIcon } from './Icons.jsx'

const BARS = 72

/**
 * Anillo visualizador alrededor del vinilo: barras radiales que siguen la voz
 * del narrador (banda 100 Hz – 3 kHz del analizador ya existente).
 */
function VinylRing({ palette }) {
  const canvasRef = useRef(null)
  const { getAnalyser, isPlaying } = usePlayer()
  const playingRef = useRef(isPlaying)
  const paletteRef = useRef(palette)

  useEffect(() => {
    playingRef.current = isPlaying
  }, [isPlaying])
  useEffect(() => {
    paletteRef.current = palette
  }, [palette])

  useEffect(() => {
    const canvas = canvasRef.current
    const ctx = canvas.getContext('2d')
    const values = new Float32Array(BARS)
    let freqData = null
    let raf

    const resize = () => {
      const dpr = Math.min(window.devicePixelRatio || 1, 2)
      const rect = canvas.parentElement.getBoundingClientRect()
      const size = Math.min(rect.width, rect.height)
      canvas.width = size * dpr
      canvas.height = size * dpr
      canvas.style.width = `${size}px`
      canvas.style.height = `${size}px`
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    }
    resize()
    const ro = new ResizeObserver(resize)
    ro.observe(canvas.parentElement)

    const draw = () => {
      const size = canvas.clientWidth
      const c = size / 2
      ctx.clearRect(0, 0, size, size)

      const analyser = getAnalyser()
      if (analyser && playingRef.current) {
        if (!freqData || freqData.length !== analyser.frequencyBinCount) {
          freqData = new Uint8Array(analyser.frequencyBinCount)
        }
        analyser.getByteFrequencyData(freqData)
      }

      const { hue, sat, light } = paletteRef.current || { hue: 265, sat: 60, light: 60 }
      const inner = size * 0.395 // justo fuera del borde del disco
      const maxLen = size * 0.085

      for (let i = 0; i < BARS; i++) {
        // Muestrear la banda de voz de forma simétrica (espejo izquierda/derecha)
        let target = 0
        if (freqData && playingRef.current) {
          const half = i < BARS / 2 ? i : BARS - 1 - i
          const bin = 1 + Math.round((half / (BARS / 2)) * 20)
          target = (freqData[bin] || 0) / 255
        }
        values[i] += (target - values[i]) * (target > values[i] ? 0.4 : 0.12)

        const v = values[i]
        if (v < 0.01) continue
        const angle = (i / BARS) * Math.PI * 2 - Math.PI / 2
        const len = v * v * maxLen
        const x0 = c + Math.cos(angle) * inner
        const y0 = c + Math.sin(angle) * inner
        const x1 = c + Math.cos(angle) * (inner + len)
        const y1 = c + Math.sin(angle) * (inner + len)
        ctx.strokeStyle = `hsla(${hue} ${sat}% ${Math.min(75, light + 8)}% / ${0.25 + v * 0.55})`
        ctx.lineWidth = 2.4
        ctx.lineCap = 'round'
        ctx.beginPath()
        ctx.moveTo(x0, y0)
        ctx.lineTo(x1, y1)
        ctx.stroke()
      }
      raf = requestAnimationFrame(draw)
    }
    draw()

    return () => {
      cancelAnimationFrame(raf)
      ro.disconnect()
    }
  }, [getAnalyser])

  return <canvas ref={canvasRef} className="vinyl-ring" aria-hidden="true" />
}

export default function Vinyl({ coverUrl, title, isPlaying, bookId, palette }) {
  return (
    <div className="vinyl-stage">
      <motion.div
        className="vinyl-shadow"
        animate={{ scale: isPlaying ? 1 : 0.92, opacity: isPlaying ? 0.55 : 0.32 }}
        transition={{ type: 'spring', stiffness: 120, damping: 18 }}
      />

      <motion.div
        className="vinyl-wrap"
        layoutId={bookId ? `cover-${bookId}` : undefined}
        style={{ borderRadius: 26 }}
        exit={{ opacity: 0, scale: 0.9 }}
        transition={{ type: 'spring', stiffness: 160, damping: 20 }}
      >
        <VinylRing palette={palette} />
        <div
          className="vinyl-disc"
          style={{ animationPlayState: isPlaying ? 'running' : 'paused' }}
        >
          <div className="vinyl-grooves" />
          <div className="vinyl-sheen" />
          <div className="vinyl-label">
            {coverUrl ? (
              <img src={coverUrl} alt={title} draggable={false} />
            ) : (
              <div className="vinyl-label-fallback">
                <BookIcon size={30} />
              </div>
            )}
            <div className="vinyl-hole" />
          </div>
        </div>

        <motion.div
          className="tonearm"
          initial={false}
          animate={{ rotate: isPlaying ? 26 : 6 }}
          transition={{ type: 'spring', stiffness: 90, damping: 14 }}
        >
          <div className="tonearm-base" />
          <div className="tonearm-rod" />
          <div className="tonearm-head" />
        </motion.div>
      </motion.div>
    </div>
  )
}
