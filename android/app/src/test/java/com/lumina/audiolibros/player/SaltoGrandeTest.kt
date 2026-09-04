package com.lumina.audiolibros.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cuando un salto merece el boton de volver.
 *
 * Lo importante que fija este test no es el numero, es la RELACION: el umbral
 * tiene que quedar por encima de los botones de salto. Si algun dia alguien
 * sube el salto adelante a 60 s o baja el umbral, el boton empezaria a
 * aparecer cada vez que se usa un control normal y estos tests lo dirian.
 */
class SaltoGrandeTest {

    private val UN_MINUTO = 60_000L

    @Test
    fun `los botones de salto no ofrecen deshacer`() {
        // Son deliberados: ensuciarian la pantalla en cada uso.
        for (segundos in listOf(-15L, 15L, -30L, 30L)) {
            val hasta = 3_600_000L + segundos * 1000
            assertFalse(
                "un salto de $segundos s no deberia ofrecer deshacer",
                SaltoGrande.mereceDeshacer(3_600_000L, hasta),
            )
        }
    }

    @Test
    fun `el rebobinado inteligente tampoco`() {
        // Como mucho retrocede 30 s al reanudar.
        assertFalse(SaltoGrande.mereceDeshacer(3_600_000L, 3_600_000L - 30_000L))
    }

    @Test
    fun `un roce en la barra de un libro largo si lo ofrece`() {
        // Sesenta horas repartidas en la pantalla: un pixel son casi 4 minutos.
        val estabas = 15L * 3600 * 1000
        val teVas = estabas + 4L * 60 * 1000
        assertTrue(SaltoGrande.mereceDeshacer(estabas, teVas))
    }

    @Test
    fun `da igual el sentido del salto`() {
        val a = 10L * 3600 * 1000
        val b = a + 30L * 60 * 1000
        assertTrue(SaltoGrande.mereceDeshacer(a, b))
        assertTrue("volver atras tambien se puede deshacer", SaltoGrande.mereceDeshacer(b, a))
    }

    @Test
    fun `justo en el umbral se ofrece`() {
        assertTrue(SaltoGrande.mereceDeshacer(0, UN_MINUTO))
        assertFalse(SaltoGrande.mereceDeshacer(0, UN_MINUTO - 1))
    }

    @Test
    fun `quedarse donde estabas no es un salto`() {
        assertFalse(SaltoGrande.mereceDeshacer(1_234_567L, 1_234_567L))
    }

    @Test
    fun `el umbral esta por encima del mayor salto de un boton`() {
        // La invariante de la que depende todo lo demas.
        val mayorSaltoDeBoton = 30_000L
        assertTrue(
            "el umbral debe superar el salto de 30 s",
            SaltoGrande.UMBRAL_MS > mayorSaltoDeBoton,
        )
    }
}
