/**
 * Emparejar el mismo audiolibro cuando los archivos no son idénticos.
 *
 * La huella (lib/fingerprint.js) reconoce copias byte a byte. Pero el mismo
 * libro puede estar en cada dispositivo con otra codificación, o con las
 * etiquetas editadas a mano, y entonces las huellas no coinciden aunque sea
 * el mismo libro. Estas funciones son la segunda vía.
 *
 * Contrato compartido con la app de Android: cualquier cambio aquí debe
 * hacerse también en `EmparejarLibros.kt`. Ver docs/SYNC.md.
 */

/**
 * Normaliza un texto para compararlo: minúsculas, sin acentos y sin
 * puntuación. Así «El Ritmo de la Guerra» y «el ritmo de la guerra» son lo
 * mismo.
 */
export function normalizarTexto(texto) {
  return (texto || '')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ')
    .trim()
}

/** Clave con la que dos libros se consideran «el mismo» por sus etiquetas. */
export function claveBlanda(titulo, autor) {
  return `${normalizarTexto(titulo)}|${normalizarTexto(autor)}`
}

/**
 * Margen admitido al comparar duraciones. Dos codificaciones del mismo libro
 * rara vez difieren en más de unos segundos, pero un porcentaje pequeño evita
 * descartar libros muy largos por unos pocos.
 */
export function toleranciaDuracion(segundos) {
  return Math.max(10, (segundos || 0) * 0.002)
}

/**
 * Elige, entre las filas remotas, la que corresponde al mismo libro.
 *
 * Manda la duración, porque es el dato que no cambia aunque se editen las
 * etiquetas; la clave blanda solo desempata cuando hay varias candidatas.
 * Devuelve null si no hay ninguna coincidencia clara: es preferible no
 * sincronizar a emparejar dos libros distintos.
 */
export function elegirCoincidencia(filas, { duracion, titulo, autor }) {
  if (!filas?.length || !duracion) return null

  const tolerancia = toleranciaDuracion(duracion)
  const candidatas = filas.filter(
    (f) => f.duration != null && Math.abs(f.duration - duracion) <= tolerancia
  )
  if (!candidatas.length) return null
  if (candidatas.length === 1) return candidatas[0]

  // Varias duraciones parecidas: solo se acepta si además coincide el título.
  const clave = claveBlanda(titulo, autor)
  const porClave = candidatas.filter((f) => claveBlanda(f.title, f.author) === clave)
  if (porClave.length === 1) return porClave[0]

  return null
}

/**
 * ¿Gana la posición remota?
 *
 * Gana la escucha **más avanzada**, no la más reciente: así ningún dispositivo
 * hace retroceder lo escuchado en el otro. El margen evita saltos molestos por
 * una diferencia de segundos.
 */
export function ganaLaRemota(posicionLocal, posicionRemota, margen = 5) {
  return (posicionRemota || 0) > (posicionLocal || 0) + margen
}

/**
 * ¿Debe subirse esta posición, sabiendo la última remota conocida?
 *
 * No se pisa una posición más avanzada que la nuestra, salvo que el usuario
 * haya reiniciado el libro a propósito: volver casi al principio o marcarlo
 * como terminado sí son intenciones claras.
 */
export function debeSubir(posicion, posicionRemotaConocida, { terminado = false } = {}) {
  if (terminado) return true
  if (posicionRemotaConocida == null) return true
  if (posicion <= 30) return true // reinicio deliberado
  return posicion >= posicionRemotaConocida - 5
}
