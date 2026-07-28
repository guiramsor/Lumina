package com.lumina.audiolibros.sync

import java.text.Normalizer

/**
 * Emparejar el mismo audiolibro cuando los archivos no son idénticos.
 *
 * La huella (Fingerprint.kt) reconoce copias byte a byte. Pero el mismo libro
 * puede estar en cada dispositivo con otra codificación, o con las etiquetas
 * editadas a mano, y entonces las huellas no coinciden aunque sea el mismo
 * libro. Estas funciones son la segunda vía.
 *
 * Contrato compartido con la app de escritorio: cualquier cambio aquí debe
 * hacerse también en `src/lib/emparejar.js`. Ver docs/SYNC.md.
 */
object EmparejarLibros {

    /** Minúsculas, sin acentos y sin puntuación, para comparar títulos. */
    fun normalizarTexto(texto: String?): String =
        Normalizer.normalize(texto.orEmpty(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    /** Clave con la que dos libros se consideran «el mismo» por sus etiquetas. */
    fun claveBlanda(titulo: String?, autor: String?): String =
        "${normalizarTexto(titulo)}|${normalizarTexto(autor)}"

    /**
     * Margen admitido al comparar duraciones. Dos codificaciones del mismo
     * libro rara vez difieren en más de unos segundos, pero un porcentaje
     * pequeño evita descartar libros muy largos por unos pocos.
     */
    fun toleranciaDuracion(segundos: Double): Double = maxOf(10.0, segundos * 0.002)

    /**
     * Elige, entre las filas remotas, la que corresponde al mismo libro.
     *
     * Manda la duración, porque no cambia aunque se editen las etiquetas; la
     * clave blanda solo desempata. Devuelve null si no hay coincidencia clara:
     * es preferible no sincronizar a mezclar dos libros distintos.
     */
    fun <T> elegirCoincidencia(
        filas: List<T>,
        duracion: Double,
        titulo: String?,
        autor: String?,
        duracionDe: (T) -> Double?,
        tituloDe: (T) -> String?,
        autorDe: (T) -> String?,
    ): T? {
        if (filas.isEmpty() || duracion <= 0) return null

        val tolerancia = toleranciaDuracion(duracion)
        val candidatas = filas.filter { f ->
            duracionDe(f)?.let { kotlin.math.abs(it - duracion) <= tolerancia } == true
        }
        if (candidatas.isEmpty()) return null
        if (candidatas.size == 1) return candidatas.first()

        val clave = claveBlanda(titulo, autor)
        val porClave = candidatas.filter { claveBlanda(tituloDe(it), autorDe(it)) == clave }
        return if (porClave.size == 1) porClave.first() else null
    }

    /**
     * Reúne todas las filas remotas que son este mismo libro.
     *
     * `idsPropios` son los identificadores con los que este dispositivo ha
     * escrito alguna vez. Sus filas entran al grupo siempre; las ajenas solo
     * si el emparejamiento es inequívoco.
     */
    fun <T> agruparMismoLibro(
        filas: List<T>,
        idsPropios: List<String>,
        duracion: Double,
        titulo: String?,
        autor: String?,
        idDe: (T) -> String,
        duracionDe: (T) -> Double?,
        tituloDe: (T) -> String?,
        autorDe: (T) -> String?,
    ): List<T> {
        if (filas.isEmpty() || duracion <= 0) return emptyList()

        val tolerancia = toleranciaDuracion(duracion)
        val enVentana = filas.filter { f ->
            duracionDe(f)?.let { kotlin.math.abs(it - duracion) <= tolerancia } == true
        }
        val propias = enVentana.filter { idDe(it) in idsPropios }
        val ajenas = enVentana.filter { idDe(it) !in idsPropios }

        if (ajenas.size == 1) return propias + ajenas.first()
        if (ajenas.size > 1) {
            val clave = claveBlanda(titulo, autor)
            val porClave = ajenas.filter { claveBlanda(tituloDe(it), autorDe(it)) == clave }
            // Ante la ambigüedad no se toca ninguna ajena.
            if (porClave.size == 1) return propias + porClave.first()
        }
        return propias
    }

    /**
     * Fila a la que escribirán todos los dispositivos de aquí en adelante.
     *
     * Se elige el identificador menor, criterio determinista: los dos
     * dispositivos llegan a la misma fila sin hablar entre ellos.
     */
    fun <T> elegirCanonica(grupo: List<T>, idDe: (T) -> String): T? =
        grupo.minByOrNull { idDe(it) }

    /**
     * ¿Gana la posición remota?
     *
     * Gana la escucha **más avanzada**, no la más reciente: así ningún
     * dispositivo hace retroceder lo escuchado en el otro.
     */
    fun ganaLaRemota(posicionLocal: Double, posicionRemota: Double, margen: Double = 5.0): Boolean =
        posicionRemota > posicionLocal + margen

    /**
     * ¿Debe subirse esta posición, sabiendo la última remota conocida?
     *
     * La regla protege de un caso concreto: que un dispositivo rezagado borre
     * el avance del otro solo por abrir el libro. No pretende impedir que el
     * usuario decida dónde quiere estar.
     *
     * Por eso `intencionado` manda sobre todo lo demás: si ha movido la barra
     * o ha saltado, esa es su posición aunque sea anterior a la de la nube.
     */
    fun debeSubir(
        posicion: Double,
        posicionRemotaConocida: Double?,
        terminado: Boolean = false,
        intencionado: Boolean = false,
    ): Boolean {
        if (terminado || intencionado) return true
        if (posicionRemotaConocida == null) return true
        return posicion >= posicionRemotaConocida - 5
    }
}
