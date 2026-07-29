/**
 * Comprobar que los audios siguen donde se importaron.
 *
 * Desde que la biblioteca guarda la ruta en vez de una copia, un archivo
 * movido o borrado deja al libro sin nada que reproducir. Antes eso solo se
 * notaba al abrirlo y darle al play; conviene verlo en la propia biblioteca.
 */

/**
 * Devuelve el conjunto de ids de libros cuyo audio ya no está en su sitio.
 *
 * Los libros antiguos, que guardan los bytes dentro de la base de datos, nunca
 * faltan: no dependen de ninguna ruta. Fuera de Electron no hay forma de
 * comprobarlo, y entonces no se marca nada.
 */
export async function librosSinArchivo(books) {
  if (!window.lumina?.existe) return new Set()

  const faltan = new Set()
  // Una ruta se comprueba una sola vez aunque la compartan varios libros.
  const cache = new Map()
  const existe = async (ruta) => {
    if (!cache.has(ruta)) cache.set(ruta, await window.lumina.existe(ruta))
    return cache.get(ruta)
  }

  for (const libro of books || []) {
    const rutas = (libro.tracks || []).map((t) => t.ruta).filter(Boolean)
    if (!rutas.length) continue
    for (const ruta of rutas) {
      if (!(await existe(ruta))) {
        faltan.add(libro.id)
        break
      }
    }
  }
  return faltan
}
