package com.lumina.audiolibros.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mismos casos que `test/emparejar.test.mjs` en el escritorio. Si las dos
 * plataformas no deciden igual, un dispositivo emparejaría libros que el otro
 * no, o haría retroceder la escucha del otro. Ver docs/SYNC.md.
 */
class EmparejarLibrosTest {

    private data class Fila(val duracion: Double?, val titulo: String?, val autor: String?)

    private fun elegir(filas: List<Fila>, duracion: Double, titulo: String?, autor: String?) =
        EmparejarLibros.elegirCoincidencia(
            filas, duracion, titulo, autor,
            duracionDe = { it.duracion }, tituloDe = { it.titulo }, autorDe = { it.autor },
        )

    /* ---------------- Normalización ---------------- */

    @Test
    fun `la normalizacion ignora mayusculas, acentos y puntuacion`() {
        assertEquals("el ritmo de la guerra", EmparejarLibros.normalizarTexto("El Ritmo de la Guerra"))
        assertEquals("el ritmo de la guerra", EmparejarLibros.normalizarTexto("EL RITMO DE LA GUERRA"))
        assertEquals("el ritmo de la guerra", EmparejarLibros.normalizarTexto("  El  Rítmo, de la Guerra!  "))
    }

    @Test
    fun `titulos escritos de formas distintas dan la misma clave`() {
        assertEquals(
            EmparejarLibros.claveBlanda("Trenza del Mar Esmeralda", "Brandon Sanderson"),
            EmparejarLibros.claveBlanda("trenza  del  mar  esmeralda", "BRANDON SANDERSON"),
        )
    }

    @Test
    fun `libros distintos no comparten clave`() {
        assertNotEquals(
            EmparejarLibros.claveBlanda("Elantris", "Sanderson"),
            EmparejarLibros.claveBlanda("Nacidos de la bruma", "Sanderson"),
        )
    }

    /* ---------------- Emparejamiento por duración ---------------- */

    @Test
    fun `empareja una unica fila de duracion parecida`() {
        val r = elegir(listOf(Fila(47631.0, "X", "Y")), 47628.0, "otro titulo", "")
        assertEquals(47631.0, r?.duracion)
    }

    @Test
    fun `no empareja si la duracion se aleja demasiado`() {
        assertNull(elegir(listOf(Fila(47631.0, "X", "Y")), 40000.0, "X", "Y"))
    }

    @Test
    fun `con varias duraciones parecidas desempata el titulo`() {
        val filas = listOf(Fila(47631.0, "Elantris", "Sanderson"), Fila(47640.0, "Trenza", "Sanderson"))
        assertEquals("Trenza", elegir(filas, 47635.0, "TRENZA", "sanderson")?.titulo)
    }

    @Test
    fun `ante la duda no empareja nada`() {
        val filas = listOf(Fila(47631.0, "Elantris", "S"), Fila(47640.0, "Trenza", "S"))
        assertNull(elegir(filas, 47635.0, "Otro", "S"))
    }

    @Test
    fun `la tolerancia crece con la duracion pero nunca baja de diez segundos`() {
        assertEquals(120.0, elegir(listOf(Fila(120.0, "a", "")), 128.0, "a", "")?.duracion)
        assertNull(elegir(listOf(Fila(120.0, "a", "")), 145.0, "a", ""))
        assertEquals(47631.0, elegir(listOf(Fila(47631.0, "a", "")), 47700.0, "a", "")?.duracion)
    }

    /* ---------------- Agrupar el mismo libro ---------------- */

    private data class FilaId(
        val id: String,
        val duracion: Double?,
        val titulo: String = "X",
        val autor: String = "Y",
    )

    private fun agrupar(filas: List<FilaId>, propios: List<String>, duracion: Double, titulo: String, autor: String) =
        EmparejarLibros.agruparMismoLibro(
            filas, propios, duracion, titulo, autor,
            idDe = { it.id }, duracionDe = { it.duracion },
            tituloDe = { it.titulo }, autorDe = { it.autor },
        )

    @Test
    fun `agrupa la fila ajena que es el mismo libro`() {
        val grupo = agrupar(listOf(FilaId("ajena", 47630.0)), listOf("mia"), 47631.0, "X", "Y")
        assertEquals(listOf("ajena"), grupo.map { it.id })
    }

    @Test
    fun `agrupa la propia y la ajena cuando existen las dos`() {
        val filas = listOf(FilaId("mia", 47631.0), FilaId("ajena", 47630.0))
        val grupo = agrupar(filas, listOf("mia"), 47631.0, "X", "Y")
        assertEquals(listOf("ajena", "mia"), grupo.map { it.id }.sorted())
    }

    @Test
    fun `no agrupa filas de otra duracion`() {
        val filas = listOf(FilaId("mia", 47631.0), FilaId("otroLibro", 12000.0))
        assertEquals(listOf("mia"), agrupar(filas, listOf("mia"), 47631.0, "X", "Y").map { it.id })
    }

    @Test
    fun `ante dos ajenas ambiguas no se agrupa ninguna`() {
        val filas = listOf(
            FilaId("mia", 47631.0),
            FilaId("ajena1", 47630.0, "Elantris", "S"),
            FilaId("ajena2", 47635.0, "Trenza", "S"),
        )
        assertEquals(listOf("mia"), agrupar(filas, listOf("mia"), 47631.0, "Otro", "S").map { it.id })
    }

    @Test
    fun `la fila canonica es la misma se mire desde donde se mire`() {
        val a = FilaId("aaa", 47631.0)
        val b = FilaId("zzz", 47631.0)
        assertEquals("aaa", EmparejarLibros.elegirCanonica(listOf(a, b)) { it.id }?.id)
        assertEquals("aaa", EmparejarLibros.elegirCanonica(listOf(b, a)) { it.id }?.id)
    }

    /* ---------------- Resolución de posiciones ---------------- */

    @Test
    fun `gana la escucha mas avanzada, no la mas reciente`() {
        assertTrue(EmparejarLibros.ganaLaRemota(100.0, 5000.0))
        assertFalse(EmparejarLibros.ganaLaRemota(5000.0, 100.0))
    }

    @Test
    fun `una diferencia de segundos no hace saltar la reproduccion`() {
        assertFalse(EmparejarLibros.ganaLaRemota(1000.0, 1003.0))
        assertTrue(EmparejarLibros.ganaLaRemota(1000.0, 1006.0))
    }

    @Test
    fun `no se pisa una posicion remota mas avanzada`() {
        assertFalse(EmparejarLibros.debeSubir(100.0, 5000.0))
        assertTrue(EmparejarLibros.debeSubir(6000.0, 5000.0))
    }

    @Test
    fun `un retroceso deliberado siempre se propaga`() {
        // Vas por 7 h, te pierdes y retrocedes a 6 h 30: esa es tu posicion,
        // aunque la nube tenga una mas avanzada.
        assertTrue(EmparejarLibros.debeSubir(23400.0, 25593.0, intencionado = true))
        assertFalse(EmparejarLibros.debeSubir(23400.0, 25593.0))
    }

    @Test
    fun `reiniciar un libro si se propaga`() {
        assertTrue(EmparejarLibros.debeSubir(12.0, 5000.0, intencionado = true))
        assertTrue(EmparejarLibros.debeSubir(100.0, 5000.0, terminado = true))
    }

    @Test
    fun `la posicion absoluta cuenta desde el principio del libro`() {
        // El ordenador tiene el libro partido en capitulos: position es el
        // segundo dentro de uno de ellos, no del libro entero.
        assertEquals(21600.0, EmparejarLibros.posicionAbsoluta(21600.0, 40.0), 0.001)
        // Filas antiguas sin global: solo queda position como respaldo.
        assertEquals(40.0, EmparejarLibros.posicionAbsoluta(0.0, 40.0), 0.001)
    }

    @Test
    fun `estar al principio no basta para pisar la nube`() {
        // Antes cualquier posicion menor de 30 s se tomaba por un reinicio.
        assertFalse(EmparejarLibros.debeSubir(0.0, 5000.0))
        assertFalse(EmparejarLibros.debeSubir(12.0, 5000.0))
    }

    @Test
    fun `sin nada remoto conocido siempre se sube`() {
        assertTrue(EmparejarLibros.debeSubir(42.0, null))
    }

    /* ---------------- Escritura condicional ---------------- */

    @Test
    fun `una escritura por inercia va condicionada`() {
        // La comprueba el servidor: `debeSubir` compara con una referencia que
        // se queda vieja en cuanto el otro dispositivo escribe algo.
        assertFalse(EmparejarLibros.escrituraIncondicional())
        assertFalse(EmparejarLibros.escrituraIncondicional(terminado = false, intencionado = false))
    }

    @Test
    fun `lo que elige el usuario se salta la condicion`() {
        // Mismos casos que la excepcion de debeSubir: si no, un retroceso
        // deliberado lo rechazaria el servidor por ir hacia atras.
        assertTrue(EmparejarLibros.escrituraIncondicional(intencionado = true))
        assertTrue(EmparejarLibros.escrituraIncondicional(terminado = true))
    }

    @Test
    fun `la excepcion coincide exactamente con la de debeSubir`() {
        // Las dos reglas tienen que abrirse por el mismo sitio. Si se separan,
        // habria posiciones que el cliente deja subir y el servidor rechaza.
        for (terminado in listOf(false, true)) {
            for (intencionado in listOf(false, true)) {
                val salta = EmparejarLibros.escrituraIncondicional(terminado, intencionado)
                val exenta = EmparejarLibros.debeSubir(
                    posicion = 0.0,
                    posicionRemotaConocida = 99999.0,
                    terminado = terminado,
                    intencionado = intencionado,
                )
                assertEquals("terminado=$terminado intencionado=$intencionado", exenta, salta)
            }
        }
    }
}
