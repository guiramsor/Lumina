package com.lumina.audiolibros.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistencia local, equivalente al IndexedDB de la app de escritorio.
 *
 * Se guarda como JSON en preferencias en vez de montar una base de datos: son
 * unas decenas de libros y unos cientos de marcadores como mucho, y así la app
 * no arrastra Room ni el procesador de anotaciones que exige.
 *
 * Lo importante es que la escucha funciona sin conexión: la nube sincroniza,
 * pero nunca es requisito para reproducir.
 */
object AlmacenLocal {

    private const val PREFS = "lumina_datos"
    private const val PROGRESO = "progreso"
    private const val MARCADORES = "marcadores"
    private const val ESTADISTICAS = "estadisticas"
    private const val AJUSTES = "ajustes"

    data class Progreso(
        val bookId: String,
        val posicionMs: Long,
        val duracionMs: Long,
        val terminado: Boolean,
        val velocidad: Float,
        val actualizadoEn: Long,
    )

    data class Marcador(
        val id: String,
        val bookId: String,
        val posicionMs: Long,
        val nota: String,
        val creadoEn: Long,
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun objeto(context: Context, clave: String): JSONObject =
        runCatching { JSONObject(prefs(context).getString(clave, "{}") ?: "{}") }
            .getOrDefault(JSONObject())

    private fun guardar(context: Context, clave: String, valor: JSONObject) {
        prefs(context).edit().putString(clave, valor.toString()).apply()
    }

    /* ---------------- Progreso ---------------- */

    fun progreso(context: Context, bookId: String): Progreso? {
        val fila = objeto(context, PROGRESO).optJSONObject(bookId) ?: return null
        return Progreso(
            bookId = bookId,
            posicionMs = fila.optLong("posicion"),
            duracionMs = fila.optLong("duracion"),
            terminado = fila.optBoolean("terminado"),
            velocidad = fila.optDouble("velocidad", 1.0).toFloat(),
            actualizadoEn = fila.optLong("actualizadoEn"),
        )
    }

    fun guardarProgreso(context: Context, progreso: Progreso) {
        val todos = objeto(context, PROGRESO)
        todos.put(
            progreso.bookId,
            JSONObject()
                .put("posicion", progreso.posicionMs)
                .put("duracion", progreso.duracionMs)
                .put("terminado", progreso.terminado)
                .put("velocidad", progreso.velocidad.toDouble())
                .put("actualizadoEn", progreso.actualizadoEn)
        )
        guardar(context, PROGRESO, todos)
    }

    /** Todos los progresos, para pintar las barras de la biblioteca. */
    fun progresos(context: Context): Map<String, Progreso> {
        val todos = objeto(context, PROGRESO)
        return todos.keys().asSequence().mapNotNull { id ->
            progreso(context, id)?.let { id to it }
        }.toMap()
    }

    /* ---------------- Marcadores ---------------- */

    fun marcadores(context: Context, bookId: String): List<Marcador> {
        val lista = objeto(context, MARCADORES).optJSONArray(bookId) ?: return emptyList()
        return (0 until lista.length()).map { i ->
            val m = lista.getJSONObject(i)
            Marcador(
                id = m.optString("id"),
                bookId = bookId,
                posicionMs = m.optLong("posicion"),
                nota = m.optString("nota"),
                creadoEn = m.optLong("creadoEn"),
            )
        }.sortedBy { it.posicionMs }
    }

    fun anadirMarcador(context: Context, marcador: Marcador) {
        val todos = objeto(context, MARCADORES)
        val lista = todos.optJSONArray(marcador.bookId) ?: JSONArray()
        lista.put(
            JSONObject()
                .put("id", marcador.id)
                .put("posicion", marcador.posicionMs)
                .put("nota", marcador.nota)
                .put("creadoEn", marcador.creadoEn)
        )
        todos.put(marcador.bookId, lista)
        guardar(context, MARCADORES, todos)
    }

    fun borrarMarcador(context: Context, bookId: String, id: String) {
        val todos = objeto(context, MARCADORES)
        val lista = todos.optJSONArray(bookId) ?: return
        val filtrada = JSONArray()
        for (i in 0 until lista.length()) {
            val m = lista.getJSONObject(i)
            if (m.optString("id") != id) filtrada.put(m)
        }
        todos.put(bookId, filtrada)
        guardar(context, MARCADORES, todos)
    }

    /* ---------------- Estadísticas ---------------- */

    fun diaDeHoy(fecha: Date = Date()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(fecha)

    /** Acumula segundos de escucha reales en el día de hoy. */
    fun sumarEscucha(context: Context, segundos: Int) {
        if (segundos <= 0) return
        val todos = objeto(context, ESTADISTICAS)
        val dia = diaDeHoy()
        todos.put(dia, todos.optInt(dia, 0) + segundos)
        guardar(context, ESTADISTICAS, todos)
    }

    /** Segundos escuchados por día, clave `yyyy-MM-dd`. */
    fun estadisticas(context: Context): Map<String, Int> {
        val todos = objeto(context, ESTADISTICAS)
        return todos.keys().asSequence().associateWith { todos.optInt(it, 0) }
    }

    /* ---------------- Ajustes ---------------- */

    fun velocidadPorDefecto(context: Context): Float =
        objeto(context, AJUSTES).optDouble("velocidad", 1.0).toFloat()

    fun guardarVelocidadPorDefecto(context: Context, velocidad: Float) {
        guardar(context, AJUSTES, objeto(context, AJUSTES).put("velocidad", velocidad.toDouble()))
    }

    fun minutosSueno(context: Context): Int =
        objeto(context, AJUSTES).optInt("minutosSueno", 30)

    fun guardarMinutosSueno(context: Context, minutos: Int) {
        guardar(context, AJUSTES, objeto(context, AJUSTES).put("minutosSueno", minutos))
    }

    fun ordenBiblioteca(context: Context): String =
        objeto(context, AJUSTES).optString("orden", "reciente")

    fun guardarOrdenBiblioteca(context: Context, orden: String) {
        guardar(context, AJUSTES, objeto(context, AJUSTES).put("orden", orden))
    }
}
