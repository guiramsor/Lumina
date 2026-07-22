export const DEFAULT_PALETTE = { hue: 265, sat: 60, light: 60 }

export function paletteToVars(palette) {
  const { hue, sat, light } = palette || DEFAULT_PALETTE
  return {
    '--accent-h': hue,
    '--accent-s': `${sat}%`,
    '--accent-l': `${light}%`,
  }
}

/**
 * Temas de la interfaz. `canvas` controla el fondo reactivo:
 *  - baseL: luminosidad de la capa base (par [inicio, fin] del degradado)
 *  - baseS: saturación de la capa base
 *  - blobAlpha: intensidad de las manchas de color
 *  - vignette: opacidad del oscurecido/aclarado de esquinas
 *  - light: true => tema claro (el viñeteado usa blanco)
 */
export const THEMES = [
  {
    id: 'noche',
    name: 'Noche',
    canvas: { baseL: [7, 5], baseS: 32, blobAlpha: 1, vignette: 0.55, light: false },
  },
  {
    id: 'medianoche',
    name: 'Medianoche',
    canvas: { baseL: [3, 2], baseS: 18, blobAlpha: 0.55, vignette: 0.7, light: false },
  },
  {
    id: 'brasa',
    name: 'Brasa',
    canvas: { baseL: [9, 6], baseS: 40, blobAlpha: 1.25, vignette: 0.5, light: false, warm: 24 },
  },
  {
    id: 'claro',
    name: 'Claro',
    canvas: { baseL: [96, 90], baseS: 30, blobAlpha: 0.5, vignette: 0.16, light: true },
  },
]

export function getTheme(id) {
  return THEMES.find((t) => t.id === id) || THEMES[0]
}
