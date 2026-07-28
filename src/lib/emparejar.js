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
 * Reúne todas las filas remotas que son este mismo libro.
 *
 * `idsPropios` son los identificadores con los que este dispositivo ha escrito
 * alguna vez (su huella y, si ya lo adoptó, el identificador compartido). Sus
 * filas entran al grupo siempre; las ajenas solo si el emparejamiento es
 * inequívoco, con el mismo criterio que `elegirCoincidencia`.
 */
export function agruparMismoLibro(filas, { idsPropios = [], duracion, titulo, autor }) {
  if (!filas?.length || !duracion) return []

  const tolerancia = toleranciaDuracion(duracion)
  const enVentana = filas.filter(
    (f) => f.duration != null && Math.abs(f.duration - duracion) <= tolerancia
  )
  const propias = enVentana.filter((f) => idsPropios.includes(f.book_id))
  const ajenas = enVentana.filter((f) => !idsPropios.includes(f.book_id))

  if (ajenas.length === 1) return [...propias, ajenas[0]]
  if (ajenas.length > 1) {
    const clave = claveBlanda(titulo, autor)
    const porClave = ajenas.filter((f) => claveBlanda(f.title, f.author) === clave)
    // Si sigue habiendo ambigüedad no se toca ninguna ajena: mejor quedarse
    // sin sincronizar que fusionar dos libros distintos.
    if (porClave.length === 1) return [...propias, porClave[0]]
  }
  return propias
}

/**
 * Fila a la que escribirán todos los dispositivos de aquí en adelante.
 *
 * Se elige el identificador menor, que es un criterio determinista: los dos
 * dispositivos llegan a la misma fila sin hablar entre ellos. Cualquier otro
 * criterio (el más reciente, el más avanzado) haría que cada uno eligiera una
 * distinta y seguirían sin encontrarse.
 */
export function elegirCanonica(grupo) {
  if (!grupo?.length) return null
  return grupo.reduce((a, b) => (a.book_id <= b.book_id ? a : b))
}

/** Posición más avanzada del grupo, para no perder nada al fusionar. */
export function filaMasAvanzada(grupo) {
  if (!grupo?.length) return null
  return grupo.reduce((a, b) =>
    (b.global_position ?? b.position ?? 0) > (a.global_position ?? a.position ?? 0) ? b : a
  )
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
 * La regla protege de un caso concreto: que un dispositivo rezagado borre el
 * avance del otro solo por abrir el libro. No pretende impedir que decidas
 * dónde quieres estar.
 *
 * Por eso `intencionado` manda sobre todo lo demás. Si has movido la barra,
 * saltado a un marcador o a un capítulo, esa es tu posición aunque sea
 * anterior a la de la nube: retroceder media hora porque te has perdido tiene
 * que persistir, y sin esta distinción la nube te devolvía al punto avanzado
 * en cuanto reabrías el libro.
 */
export function debeSubir(
  posicion,
  posicionRemotaConocida,
  { terminado = false, intencionado = false } = {}
) {
  if (terminado || intencionado) return true
  if (posicionRemotaConocida == null) return true
  return posicion >= posicionRemotaConocida - 5
}
