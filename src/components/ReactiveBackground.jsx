import { useEffect, useRef } from 'react'
import { usePlayer } from '../player/PlayerContext.jsx'

const DEFAULT_CANVAS = { baseL: [7, 5], baseS: 32, blobAlpha: 1, vignette: 0.55, light: false }

export default function ReactiveBackground({ palette, theme }) {
  const canvasRef = useRef(null)
  const { getAnalyser, isPlaying } = usePlayer()
  const stateRef = useRef({ energy: 0, voice: 0, t: 0 })
  const playingRef = useRef(isPlaying)
  const paletteRef = useRef(palette)
  const themeRef = useRef(theme)

  useEffect(() => {
    playingRef.current = isPlaying
  }, [isPlaying])
  useEffect(() => {
    paletteRef.current = palette
  }, [palette])
  useEffect(() => {
    themeRef.current = theme
  }, [theme])

  useEffect(() => {
    const canvas = canvasRef.current
    const ctx = canvas.getContext('2d')
    let raf
    let freqData = null

    const resize = () => {
      const dpr = Math.min(window.devicePixelRatio || 1, 2)
      canvas.width = window.innerWidth * dpr
      canvas.height = window.innerHeight * dpr
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    }
    resize()
    window.addEventListener('resize', resize)

    const blobs = [
      { x: 0.25, y: 0.3, r: 0.42, hShift: 0, phase: 0 },
      { x: 0.78, y: 0.25, r: 0.36, hShift: 28, phase: 2.1 },
      { x: 0.55, y: 0.82, r: 0.5, hShift: -34, phase: 4.2 },
    ]

    const draw = () => {
      const w = window.innerWidth
      const h = window.innerHeight
      const s = stateRef.current
      s.t += 0.0045

      // Audio energy: bass drives the pulse, the voice band drives the glow
      const analyser = getAnalyser()
      let target = 0
      let voiceTarget = 0
      if (analyser && playingRef.current) {
        if (!freqData || freqData.length !== analyser.frequencyBinCount) {
          freqData = new Uint8Array(analyser.frequencyBinCount)
        }
        analyser.getByteFrequencyData(freqData)
        let sum = 0
        const n = Math.min(48, freqData.length)
        for (let i = 0; i < n; i++) sum += freqData[i]
        target = sum / n / 255
        // Banda de voz (~100 Hz – 3 kHz con fftSize 256 @ 44.1/48 kHz)
        let vSum = 0
        const v0 = 1
        const v1 = Math.min(18, freqData.length)
        for (let i = v0; i < v1; i++) vSum += freqData[i]
        voiceTarget = vSum / (v1 - v0) / 255
      }
      s.energy += (target - s.energy) * 0.08
      s.voice += (voiceTarget - s.voice) * 0.14

      const { hue: rawHue, sat, light } = paletteRef.current || { hue: 265, sat: 60, light: 60 }
      const spec = themeRef.current?.canvas || DEFAULT_CANVAS
      const hue = (rawHue + (spec.warm || 0) + 360) % 360

      ctx.clearRect(0, 0, w, h)
      // base wash
      const baseGrad = ctx.createLinearGradient(0, 0, w, h)
      baseGrad.addColorStop(0, `hsl(${hue} ${spec.baseS}% ${spec.baseL[0]}%)`)
      baseGrad.addColorStop(1, `hsl(${(hue + 40) % 360} ${Math.max(0, spec.baseS - 2)}% ${spec.baseL[1]}%)`)
      ctx.fillStyle = baseGrad
      ctx.fillRect(0, 0, w, h)

      ctx.globalCompositeOperation = spec.light ? 'source-over' : 'lighter'
      const minSide = Math.min(w, h)
      blobs.forEach((b) => {
        const pulse = 1 + s.energy * 0.55 + s.voice * 0.25 + Math.sin(s.t * 1.6 + b.phase) * 0.06
        const driftX = Math.sin(s.t * 0.7 + b.phase) * 0.04
        const driftY = Math.cos(s.t * 0.6 + b.phase * 1.3) * 0.04
        const cx = (b.x + driftX) * w
        const cy = (b.y + driftY) * h
        const radius = b.r * minSide * pulse
        const g = ctx.createRadialGradient(cx, cy, 0, cx, cy, radius)
        const bh = (hue + b.hShift + 360) % 360
        const blobL = spec.light ? Math.min(78, light + 12) : light
        const alpha = (0.32 + s.energy * 0.22 + s.voice * 0.22) * spec.blobAlpha * (spec.light ? 0.45 : 1)
        g.addColorStop(0, `hsla(${bh} ${sat}% ${Math.min(80, blobL + 6)}% / ${alpha})`)
        g.addColorStop(0.55, `hsla(${bh} ${sat}% ${blobL - 6}% / ${alpha * 0.4})`)
        g.addColorStop(1, `hsla(${bh} ${sat}% ${blobL}% / 0)`)
        ctx.fillStyle = g
        ctx.beginPath()
        ctx.arc(cx, cy, radius, 0, Math.PI * 2)
        ctx.fill()
      })
      ctx.globalCompositeOperation = 'source-over'

      // subtle vignette (light themes brighten the edges instead)
      const vg = ctx.createRadialGradient(w / 2, h / 2, minSide * 0.3, w / 2, h / 2, minSide * 0.85)
      const vColor = spec.light ? '255,255,255' : '0,0,0'
      vg.addColorStop(0, `rgba(${vColor},0)`)
      vg.addColorStop(1, `rgba(${vColor},${spec.vignette})`)
      ctx.fillStyle = vg
      ctx.fillRect(0, 0, w, h)

      raf = requestAnimationFrame(draw)
    }
    draw()

    return () => {
      cancelAnimationFrame(raf)
      window.removeEventListener('resize', resize)
    }
  }, [getAnalyser])

  return <canvas ref={canvasRef} className="reactive-bg" aria-hidden="true" />
}
