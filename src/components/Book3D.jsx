import { useState } from 'react'
import { motion, useMotionValue, useSpring, useTransform } from 'framer-motion'

const SPRING = { stiffness: 140, damping: 20, mass: 0.7 }

export default function Book3D({ coverUrl, title, author, isPlaying, coverFit = 'cover', bookId }) {
  const [hovering, setHovering] = useState(false)

  // Normalised pointer position over the stage (-0.5 … 0.5), 0 when not hovering.
  const px = useMotionValue(0)
  const py = useMotionValue(0)
  const sx = useSpring(px, SPRING)
  const sy = useSpring(py, SPRING)

  // Subtle tilt: the book always faces us (rotateY stays negative so the cover
  // and the right-hand page block stay visible) and leans gently toward the cursor.
  const rotateY = useTransform(sx, [-0.5, 0.5], [-26, -8])
  const rotateX = useTransform(sy, [-0.5, 0.5], [7, -7])
  const glossX = useTransform(sx, [-0.5, 0.5], ['-25%', '125%'])
  const shadowShift = useTransform(sx, [-0.5, 0.5], ['14%', '-14%'])

  const handleMove = (e) => {
    const r = e.currentTarget.getBoundingClientRect()
    px.set((e.clientX - r.left) / r.width - 0.5)
    py.set((e.clientY - r.top) / r.height - 0.5)
  }
  const handleLeave = () => {
    setHovering(false)
    px.set(0)
    py.set(0)
  }

  return (
    <div
      className={`book-stage ${isPlaying ? 'playing' : ''}`}
      onMouseEnter={() => setHovering(true)}
      onMouseMove={handleMove}
      onMouseLeave={handleLeave}
    >
      <motion.div className="book-shadow" style={{ x: shadowShift }} />
      <motion.div
        className="book-scene"
        layoutId={bookId ? `cover-${bookId}` : undefined}
        style={{ borderRadius: 18 }}
      >
        <div className={`book-float ${isPlaying ? 'playing' : ''}`}>
          <motion.div
            className="book-3d"
            style={{ rotateX, rotateY }}
            initial={{ opacity: 0, scale: 0.9 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={{ duration: 0.55, ease: [0.22, 1, 0.36, 1] }}
          >
            <div className="book-face book-front">
              {coverUrl ? (
                <img src={coverUrl} alt={title} draggable={false} style={{ objectFit: coverFit }} />
              ) : (
                <div className="book-front-fallback">
                  <span className="book-front-title">{title}</span>
                  {author && <span className="book-front-author">{author}</span>}
                </div>
              )}
              <div className="book-front-edge" />
              <div className="book-front-sheen" />
              <motion.div
                className="book-gloss book-gloss-follow"
                style={{ x: glossX }}
                initial={{ opacity: 0 }}
                animate={{ opacity: hovering ? 1 : 0 }}
                transition={{ duration: 0.45 }}
              />
            </div>
            <div className="book-face book-back" />
            <div className="book-face book-spine">
              <span>{title}</span>
            </div>
            <div className="book-face book-pages book-pages-right" />
            <div className="book-face book-pages book-pages-top" />
            <div className="book-face book-pages book-pages-bottom" />
          </motion.div>
        </div>
      </motion.div>
    </div>
  )
}
