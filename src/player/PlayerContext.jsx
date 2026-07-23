import { createContext, useContext, useEffect, useMemo, useRef, useState, useCallback } from 'react'
import {
  getProgress,
  putProgress,
  putBookSpeed,
  touchBook,
  getSettings,
  putSettings,
  addListeningTime,
} from '../lib/db.js'
import { ensureFingerprints, trackIndexByFingerprint } from '../lib/bookIdentity.js'
import { pullProgress, pushProgress, resolveProgress, haySesion } from '../lib/sync.js'

/**
 * Smart rewind (estilo Audible): cuanto más tiempo lleves sin escuchar, más
 * retrocede al reanudar para que recuperes el hilo de la narración.
 */
function smartRewindSeconds(pausedMs) {
  const s = pausedMs / 1000
  if (s < 30) return 0
  if (s < 5 * 60) return 5
  if (s < 30 * 60) return 10
  if (s < 2 * 3600) return 15
  if (s < 24 * 3600) return 25
  return 30
}

const PlayerContext = createContext(null)

export function usePlayer() {
  const ctx = useContext(PlayerContext)
  if (!ctx) throw new Error('usePlayer debe usarse dentro de PlayerProvider')
  return ctx
}

/** Convierte un segundo global del libro en { index, local } de pista. */
function locateGlobal(tracks, globalSeconds) {
  let acc = 0
  for (let i = 0; i < tracks.length; i++) {
    const dur = tracks[i].duration || 0
    if (globalSeconds < acc + dur || i === tracks.length - 1) {
      return { index: i, local: Math.max(0, globalSeconds - acc) }
    }
    acc += dur
  }
  return { index: 0, local: 0 }
}

function makeBookView(rawBook) {
  const coverUrl = rawBook.coverBlob ? URL.createObjectURL(rawBook.coverBlob) : null
  const tracks = rawBook.tracks.map((t) => ({
    ...t,
    url: URL.createObjectURL(t.blob),
  }))
  return { ...rawBook, coverUrl, tracks }
}

function revokeBookView(view) {
  if (!view) return
  if (view.coverUrl) URL.revokeObjectURL(view.coverUrl)
  view.tracks?.forEach((t) => t.url && URL.revokeObjectURL(t.url))
}

export function PlayerProvider({ children }) {
  const audioRef = useRef(null)
  if (!audioRef.current && typeof Audio !== 'undefined') {
    audioRef.current = new Audio()
    audioRef.current.preload = 'metadata'
  }

  const audioCtxRef = useRef(null)
  const analyserRef = useRef(null)
  const sourceRef = useRef(null)

  const bookViewRef = useRef(null)
  const pendingSeekRef = useRef(null)
  const autoplayRef = useRef(false)
  const lastSaveRef = useRef(0)
  const playbackRef = useRef({ trackIndex: 0, tracks: [] })
  const speedRef = useRef(1)
  const lastPauseAtRef = useRef(null) // para el rebobinado inteligente
  const lastPushRef = useRef(0) // última subida a la nube
  const statsRef = useRef({ pending: 0, lastTick: 0, lastFlush: 0 })

  const [book, setBook] = useState(null)
  const [trackIndex, setTrackIndex] = useState(0)
  const [currentTime, setCurrentTime] = useState(0)
  const [trackDuration, setTrackDuration] = useState(0)
  const [isPlaying, setIsPlaying] = useState(false)
  const [isBuffering, setIsBuffering] = useState(false)
  const [speed, setSpeedState] = useState(1)
  const [volume, setVolumeState] = useState(1)
  const [visualMode, setVisualModeState] = useState('vinyl')
  const [sleep, setSleep] = useState({ mode: null, remaining: null })
  const [settingsLoaded, setSettingsLoaded] = useState(false)
  // 'inactivo' | 'subiendo' | 'hecho' | 'fallo'
  const [syncState, setSyncState] = useState('inactivo')
  const [syncedAt, setSyncedAt] = useState(null)

  const trackOffsets = useMemo(() => {
    if (!book) return []
    const offsets = []
    let acc = 0
    for (const t of book.tracks) {
      offsets.push(acc)
      acc += t.duration || 0
    }
    return offsets
  }, [book])

  const totalDuration = book?.totalDuration || 0
  const globalTime = (trackOffsets[trackIndex] || 0) + currentTime

  const chapters = useMemo(() => {
    if (!book) return []
    if (book.chapters && book.chapters.length) return book.chapters
    let acc = 0
    return book.tracks.map((t, i) => {
      const ch = { title: t.title || t.name, trackIndex: i, start: 0, globalStart: acc }
      acc += t.duration || 0
      return ch
    })
  }, [book])

  const currentChapterIndex = useMemo(() => {
    if (!chapters.length) return 0
    let idx = 0
    for (let i = 0; i < chapters.length; i++) {
      if (globalTime + 0.4 >= chapters[i].globalStart) idx = i
      else break
    }
    return idx
  }, [chapters, globalTime])

  const currentChapter = chapters[currentChapterIndex] || null

  // keep latest values for event handlers
  useEffect(() => {
    playbackRef.current = { trackIndex, tracks: book?.tracks || [] }
  }, [trackIndex, book])

  useEffect(() => {
    speedRef.current = speed
  }, [speed])

  /* ---------- Load persisted settings ---------- */
  useEffect(() => {
    let alive = true
    getSettings().then((s) => {
      if (!alive) return
      setSpeedState(s.speed)
      setVolumeState(s.volume)
      setVisualModeState(s.visualMode)
      if (audioRef.current) {
        audioRef.current.playbackRate = s.speed
        audioRef.current.volume = s.volume
      }
      setSettingsLoaded(true)
    })
    return () => {
      alive = false
    }
  }, [])

  /* ---------- Audio element listeners (bound once) ---------- */
  useEffect(() => {
    const audio = audioRef.current
    if (!audio) return

    const onLoadedMeta = () => {
      setTrackDuration(isFinite(audio.duration) ? audio.duration : 0)
      if (pendingSeekRef.current != null) {
        try {
          audio.currentTime = pendingSeekRef.current
        } catch {
          /* ignore */
        }
        setCurrentTime(pendingSeekRef.current)
        pendingSeekRef.current = null
      }
      audio.playbackRate = speedRef.current
      if (autoplayRef.current) {
        autoplayRef.current = false
        audio.play().catch(() => {})
      }
    }
    const onTimeUpdate = () => {
      setCurrentTime(audio.currentTime)
      const now = Date.now()
      if (now - lastSaveRef.current > 4000) {
        lastSaveRef.current = now
        persistProgress(audio.currentTime)
      }
      // Estadísticas: acumular tiempo real de escucha (reloj de pared)
      const s = statsRef.current
      const tick = performance.now()
      if (s.lastTick) {
        const delta = (tick - s.lastTick) / 1000
        if (delta > 0 && delta < 2) s.pending += delta
      }
      s.lastTick = tick
      if (tick - s.lastFlush > 20000) flushStats()
    }
    const onPlay = () => {
      setIsPlaying(true)
      statsRef.current.lastTick = performance.now()
    }
    const onPause = () => {
      setIsPlaying(false)
      lastPauseAtRef.current = Date.now()
      statsRef.current.lastTick = 0
      flushStats()
    }
    const onWaiting = () => setIsBuffering(true)
    const onPlaying = () => setIsBuffering(false)
    const onEnded = () => {
      const { trackIndex: idx, tracks } = playbackRef.current
      if (idx < tracks.length - 1) {
        goToTrack(idx + 1, 0, true)
      } else {
        setIsPlaying(false)
        persistProgress(audio.duration || 0, true, { force: true })
      }
    }

    audio.addEventListener('loadedmetadata', onLoadedMeta)
    audio.addEventListener('timeupdate', onTimeUpdate)
    audio.addEventListener('play', onPlay)
    audio.addEventListener('pause', onPause)
    audio.addEventListener('waiting', onWaiting)
    audio.addEventListener('playing', onPlaying)
    audio.addEventListener('ended', onEnded)
    return () => {
      audio.removeEventListener('loadedmetadata', onLoadedMeta)
      audio.removeEventListener('timeupdate', onTimeUpdate)
      audio.removeEventListener('play', onPlay)
      audio.removeEventListener('pause', onPause)
      audio.removeEventListener('waiting', onWaiting)
      audio.removeEventListener('playing', onPlaying)
      audio.removeEventListener('ended', onEnded)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const flushStats = useCallback(() => {
    const s = statsRef.current
    if (s.pending >= 1) {
      addListeningTime(bookViewRef.current?.id, Math.round(s.pending))
      s.pending = 0
    }
    s.lastFlush = performance.now()
  }, [])

  const persistProgress = useCallback(
    (time, finished = false, { force = false } = {}) => {
      const view = bookViewRef.current
      if (!view) return
      const trackIndex = playbackRef.current.trackIndex
      const globalTime = trackOffsetsFor(view, trackIndex) + time
      const updatedAt = Date.now()

      putProgress({
        bookId: view.id,
        trackIndex,
        time,
        globalTime,
        speed: speedRef.current,
        finished,
      })

      // La nube se actualiza mucho menos a menudo que el guardado local: cada
      // 30 s mientras se escucha, y siempre al pausar o cerrar.
      if (!view.fingerprint) return
      if (!force && updatedAt - lastPushRef.current < 30_000) return
      // Sin sesión no hay nada que subir, y marcarlo como fallo sería mentir.
      if (!haySesion()) return
      lastPushRef.current = updatedAt
      setSyncState('subiendo')
      pushProgress({
        bookId: view.fingerprint,
        trackId: view.tracks[trackIndex]?.fingerprint,
        position: time,
        globalPosition: globalTime,
        duration: view.totalDuration,
        finished,
        title: view.title,
        author: view.author,
        updatedAt,
      }).then((bien) => {
        setSyncState(bien ? 'hecho' : 'fallo')
        if (bien) setSyncedAt(Date.now())
      })
    },
    []
  )

  function trackOffsetsFor(view, idx) {
    let acc = 0
    for (let i = 0; i < idx; i++) acc += view.tracks[i].duration || 0
    return acc
  }

  /* ---------- Audio graph (analyser) ---------- */
  const initAudioGraph = useCallback(() => {
    if (sourceRef.current || !audioRef.current) return
    const Ctx = window.AudioContext || window.webkitAudioContext
    if (!Ctx) return
    try {
      const ctx = new Ctx()
      const source = ctx.createMediaElementSource(audioRef.current)
      const analyser = ctx.createAnalyser()
      analyser.fftSize = 256
      analyser.smoothingTimeConstant = 0.82
      source.connect(analyser)
      analyser.connect(ctx.destination)
      audioCtxRef.current = ctx
      analyserRef.current = analyser
      sourceRef.current = source
    } catch (err) {
      console.warn('No se pudo iniciar el análisis de audio', err)
    }
  }, [])

  const getAnalyser = useCallback(() => analyserRef.current, [])

  /* ---------- Track control ---------- */
  const goToTrack = useCallback((index, seekTime = 0, autoplay = false) => {
    const view = bookViewRef.current
    if (!view) return
    const clamped = Math.max(0, Math.min(index, view.tracks.length - 1))
    const audio = audioRef.current
    lastPauseAtRef.current = null // salto explícito: no aplicar rebobinado
    pendingSeekRef.current = seekTime
    autoplayRef.current = autoplay
    // Update synchronously so progress saved right after a seek uses the new track.
    playbackRef.current = { trackIndex: clamped, tracks: view.tracks }
    setTrackIndex(clamped)
    setCurrentTime(seekTime)
    audio.src = view.tracks[clamped].url
    audio.load()
  }, [])

  /* ---------- Load a book ---------- */
  const loadBook = useCallback(
    async (rawBook, { autoplay = false } = {}) => {
      // cleanup previous
      const prev = bookViewRef.current
      const audio = audioRef.current
      audio.pause()

      // La huella identifica el libro en la nube; se calcula una sola vez.
      const identified = await ensureFingerprints(rawBook)
      const view = makeBookView(identified)
      bookViewRef.current = view
      setBook(view)

      const local = await getProgress(identified.id)
      // Si el otro dispositivo escuchó más tarde, su posición manda.
      const remote = await pullProgress(view.fingerprint)
      const { winner } = resolveProgress(local, remote)

      let startTrack = 0
      let startTime = 0
      let finished = false
      let lastListenedAt = 0

      if (winner === 'remote' && remote) {
        finished = Boolean(remote.finished)
        lastListenedAt = new Date(remote.updated_at).getTime()
        // La pista se busca por huella: así la posición es exacta aunque cada
        // dispositivo ordene los archivos de otra forma.
        const idx = trackIndexByFingerprint(view, remote.track_id)
        if (idx >= 0) {
          startTrack = idx
          startTime = remote.position || 0
        } else {
          const loc = locateGlobal(view.tracks, remote.global_position || 0)
          startTrack = loc.index
          startTime = loc.local
        }
      } else if (local) {
        finished = Boolean(local.finished)
        lastListenedAt = local.updatedAt || 0
        startTrack = local.trackIndex ?? 0
        startTime = local.time ?? 0
      }

      // Un libro terminado se reabre desde el principio: no basta con poner el
      // tiempo a 0, hay que volver también a la primera pista.
      if (finished) {
        startTrack = 0
        startTime = 0
      }

      // Rebobinado inteligente entre sesiones: retomar un poco antes de donde
      // se dejó, según cuánto tiempo haya pasado desde la última escucha.
      if (startTime > 0 && lastListenedAt) {
        startTime = Math.max(0, startTime - smartRewindSeconds(Date.now() - lastListenedAt))
      }
      const safeTrack = Math.max(0, Math.min(startTrack, view.tracks.length - 1))
      lastPauseAtRef.current = null

      // Per-book playback speed: use the book's saved speed, else the last-used default.
      const startSpeed = local?.speed ?? speedRef.current
      setSpeedState(startSpeed)
      speedRef.current = startSpeed

      playbackRef.current = { trackIndex: safeTrack, tracks: view.tracks }
      setTrackIndex(safeTrack)
      setCurrentTime(startTime)
      pendingSeekRef.current = startTime
      autoplayRef.current = autoplay
      audio.src = view.tracks[safeTrack].url
      audio.playbackRate = startSpeed
      audio.volume = volume
      audio.load()

      touchBook(rawBook.id)
      if (prev) revokeBookView(prev)
    },
    [volume]
  )

  const unloadBook = useCallback(() => {
    const audio = audioRef.current
    audio.pause()
    if (bookViewRef.current) persistProgress(audio.currentTime, false, { force: true })
    setIsPlaying(false)
  }, [persistProgress])

  /* ---------- Playback controls ---------- */
  const play = useCallback(() => {
    const audio = audioRef.current
    if (!audio || !bookViewRef.current) return
    initAudioGraph()
    if (audioCtxRef.current?.state === 'suspended') audioCtxRef.current.resume()
    // Rebobinado inteligente: al reanudar tras una pausa, retroceder un poco.
    if (lastPauseAtRef.current) {
      const rewind = smartRewindSeconds(Date.now() - lastPauseAtRef.current)
      lastPauseAtRef.current = null
      if (rewind > 0 && audio.currentTime > 0) {
        const t = Math.max(0, audio.currentTime - rewind)
        try {
          audio.currentTime = t
          setCurrentTime(t)
        } catch {
          /* ignore */
        }
      }
    }
    audio.playbackRate = speed
    audio.play().catch(() => {})
  }, [initAudioGraph, speed])

  const pause = useCallback(() => {
    const audio = audioRef.current
    audio?.pause()
    persistProgress(audio?.currentTime || 0, false, { force: true })
  }, [persistProgress])

  const togglePlay = useCallback(() => {
    if (isPlaying) pause()
    else play()
  }, [isPlaying, play, pause])

  const seekGlobal = useCallback(
    (g) => {
      const view = bookViewRef.current
      if (!view) return
      const clampedG = Math.max(0, Math.min(g, view.totalDuration || 0))
      lastPauseAtRef.current = null // búsqueda explícita: no aplicar rebobinado
      let acc = 0
      let target = view.tracks.length - 1
      let local = 0
      for (let i = 0; i < view.tracks.length; i++) {
        const dur = view.tracks[i].duration || 0
        if (clampedG < acc + dur || i === view.tracks.length - 1) {
          target = i
          local = clampedG - acc
          break
        }
        acc += dur
      }
      if (target === playbackRef.current.trackIndex) {
        audioRef.current.currentTime = Math.max(0, local)
        setCurrentTime(local)
      } else {
        goToTrack(target, Math.max(0, local), isPlaying)
      }
      persistProgress(local)
    },
    [goToTrack, isPlaying, persistProgress]
  )

  const skip = useCallback(
    (delta) => {
      seekGlobal(globalTime + delta)
    },
    [seekGlobal, globalTime]
  )

  const nextTrack = useCallback(() => {
    goToTrack(playbackRef.current.trackIndex + 1, 0, isPlaying)
  }, [goToTrack, isPlaying])

  const prevTrack = useCallback(() => {
    // If more than 3s in, restart current track; else go to previous.
    if (audioRef.current.currentTime > 3) {
      audioRef.current.currentTime = 0
      setCurrentTime(0)
    } else {
      goToTrack(playbackRef.current.trackIndex - 1, 0, isPlaying)
    }
  }, [goToTrack, isPlaying])

  const jumpToTrack = useCallback(
    (index) => {
      goToTrack(index, 0, true)
    },
    [goToTrack]
  )

  const jumpToChapter = useCallback(
    (index) => {
      const ch = chapters[index]
      const view = bookViewRef.current
      if (!ch || !view) return
      if (ch.trackIndex === playbackRef.current.trackIndex) {
        lastPauseAtRef.current = null // salto explícito: no aplicar rebobinado
        audioRef.current.currentTime = Math.max(0, ch.start)
        setCurrentTime(ch.start)
        persistProgress(ch.start)
        play()
      } else {
        goToTrack(ch.trackIndex, Math.max(0, ch.start), true)
      }
    },
    [chapters, goToTrack, play, persistProgress]
  )

  const nextChapter = useCallback(() => {
    jumpToChapter(Math.min(currentChapterIndex + 1, chapters.length - 1))
  }, [jumpToChapter, currentChapterIndex, chapters.length])

  const prevChapter = useCallback(() => {
    const ch = chapters[currentChapterIndex]
    if (ch && globalTime - ch.globalStart > 3) {
      jumpToChapter(currentChapterIndex)
    } else {
      jumpToChapter(Math.max(currentChapterIndex - 1, 0))
    }
  }, [jumpToChapter, currentChapterIndex, chapters, globalTime])

  const setSpeed = useCallback((value) => {
    setSpeedState(value)
    speedRef.current = value
    if (audioRef.current) audioRef.current.playbackRate = value
    putSettings({ speed: value }) // last-used default for new books
    const view = bookViewRef.current
    if (view) putBookSpeed(view.id, value) // per-book override
  }, [])

  const setVolume = useCallback((value) => {
    setVolumeState(value)
    if (audioRef.current) audioRef.current.volume = value
    putSettings({ volume: value })
  }, [])

  const setVisualMode = useCallback((mode) => {
    setVisualModeState(mode)
    putSettings({ visualMode: mode })
  }, [])

  /* ---------- Sleep timer ---------- */
  const sleepRef = useRef(sleep)
  useEffect(() => {
    sleepRef.current = sleep
  }, [sleep])

  const startSleepTimer = useCallback((minutes) => {
    putSettings({ lastSleepMinutes: minutes })
    setSleep({ mode: 'timer', remaining: minutes * 60 })
  }, [])

  const startSleepEndOfChapter = useCallback(() => {
    setSleep({ mode: 'chapter', remaining: null })
  }, [])

  const cancelSleep = useCallback(() => {
    if (audioRef.current) audioRef.current.volume = volume
    setSleep({ mode: null, remaining: null })
  }, [volume])

  // Timer-mode countdown
  useEffect(() => {
    if (sleep.mode !== 'timer' || !isPlaying) return
    const id = setInterval(() => {
      setSleep((s) => {
        if (s.mode !== 'timer' || s.remaining == null) return s
        const remaining = s.remaining - 1
        const audio = audioRef.current
        if (remaining <= 0) {
          audio?.pause()
          if (audio) audio.volume = volume
          return { mode: null, remaining: null }
        }
        // gentle fade in the final 12s
        if (audio) {
          if (remaining <= 12) audio.volume = volume * (remaining / 12)
          else audio.volume = volume
        }
        return { mode: 'timer', remaining }
      })
    }, 1000)
    return () => clearInterval(id)
  }, [sleep.mode, isPlaying, volume])

  // Chapter-mode: pause when the chapter changes (covers embedded/cue chapters
  // inside a single file) or when the last track ends.
  const sleepChapterRef = useRef(null)
  useEffect(() => {
    if (sleep.mode !== 'chapter') {
      sleepChapterRef.current = null
      return
    }
    if (sleepChapterRef.current == null) {
      sleepChapterRef.current = currentChapterIndex
    } else if (currentChapterIndex !== sleepChapterRef.current) {
      audioRef.current?.pause()
      sleepChapterRef.current = null
      setSleep({ mode: null, remaining: null })
    }
  }, [sleep.mode, currentChapterIndex])

  useEffect(() => {
    if (sleep.mode !== 'chapter') return
    const audio = audioRef.current
    const onEnd = () => {
      audio.pause()
      setSleep({ mode: null, remaining: null })
    }
    audio.addEventListener('ended', onEnd)
    return () => audio.removeEventListener('ended', onEnd)
  }, [sleep.mode])

  /* ---------- Persist on unmount ---------- */
  useEffect(() => {
    const handler = () => {
      if (bookViewRef.current && audioRef.current) {
        persistProgress(audioRef.current.currentTime, false, { force: true })
      }
      flushStats()
    }
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') handler()
    }
    window.addEventListener('beforeunload', handler)
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      window.removeEventListener('beforeunload', handler)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [persistProgress])

  /* ---------- MediaSession (media keys + OS overlay) ---------- */
  useEffect(() => {
    if (!('mediaSession' in navigator) || !book) return
    const ms = navigator.mediaSession
    const artwork = book.coverUrl
      ? [{ src: book.coverUrl, sizes: '512x512', type: book.coverBlob?.type || 'image/jpeg' }]
      : []
    try {
      ms.metadata = new window.MediaMetadata({
        title: chapters.length > 1 && currentChapter ? currentChapter.title : book.title,
        artist: book.author || 'Audiolibro',
        album: book.title,
        artwork,
      })
    } catch {
      /* ignore */
    }
  }, [book, currentChapter, chapters.length])

  useEffect(() => {
    if (!('mediaSession' in navigator)) return
    const ms = navigator.mediaSession
    const set = (action, handler) => {
      try {
        ms.setActionHandler(action, handler)
      } catch {
        /* unsupported action */
      }
    }
    set('play', () => play())
    set('pause', () => pause())
    set('seekbackward', (d) => skip(-(d?.seekOffset || 15)))
    set('seekforward', (d) => skip(d?.seekOffset || 30))
    set('previoustrack', () => prevChapter())
    set('nexttrack', () => nextChapter())
    set('seekto', (d) => {
      if (d?.seekTime != null) seekGlobal(d.seekTime)
    })
    return () => {
      for (const a of ['play', 'pause', 'seekbackward', 'seekforward', 'previoustrack', 'nexttrack', 'seekto']) {
        set(a, null)
      }
    }
  }, [play, pause, skip, prevChapter, nextChapter, seekGlobal])

  useEffect(() => {
    if ('mediaSession' in navigator) {
      navigator.mediaSession.playbackState = isPlaying ? 'playing' : 'paused'
    }
  }, [isPlaying])

  useEffect(() => {
    if (!('mediaSession' in navigator) || !('setPositionState' in navigator.mediaSession)) return
    if (!totalDuration) return
    try {
      navigator.mediaSession.setPositionState({
        duration: totalDuration,
        playbackRate: speed || 1,
        position: Math.min(Math.max(0, globalTime), totalDuration),
      })
    } catch {
      /* ignore */
    }
  }, [globalTime, totalDuration, speed])

  const currentTrack = book?.tracks?.[trackIndex] || null

  const value = {
    // state
    book,
    trackIndex,
    currentTrack,
    chapters,
    currentChapter,
    currentChapterIndex,
    currentTime,
    trackDuration,
    globalTime,
    totalDuration,
    trackOffsets,
    isPlaying,
    isBuffering,
    speed,
    volume,
    visualMode,
    sleep,
    settingsLoaded,
    syncState,
    syncedAt,
    // controls
    loadBook,
    unloadBook,
    play,
    pause,
    togglePlay,
    seekGlobal,
    skip,
    nextTrack,
    prevTrack,
    jumpToTrack,
    jumpToChapter,
    nextChapter,
    prevChapter,
    setSpeed,
    setVolume,
    setVisualMode,
    startSleepTimer,
    startSleepEndOfChapter,
    cancelSleep,
    getAnalyser,
    persistProgress,
  }

  return <PlayerContext.Provider value={value}>{children}</PlayerContext.Provider>
}
