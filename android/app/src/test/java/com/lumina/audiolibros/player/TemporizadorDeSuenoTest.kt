package com.lumina.audiolibros.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El temporizador de sueño.
 *
 * Vivia en la composicion de Compose, que muere al irse la app a segundo
 * plano: con `always_finish_activities` puesto eso pasa cada vez que sales de
 * la pantalla, asi que el temporizador se congelaba justo en el escenario para
 * el que existe. Ahora lo hace avanzar el servicio, y la decision de cada tic
 * se fija aqui.
 *
 * El reloj se pasa por parametro para no depender del reloj real.
 */
class TemporizadorDeSuenoTest {

    private val T0 = 1_000_000L

    @After
    fun limpiar() = TemporizadorDeSueno.cancelar()

    @Test
    fun `sin temporizador puesto no hay nada que hacer`() {
        TemporizadorDeSueno.cancelar()
        assertFalse(TemporizadorDeSueno.activo())
        assertNull(TemporizadorDeSueno.tictac(T0))
    }

    @Test
    fun `mientras queda tiempo suena a volumen entero`() {
        TemporizadorDeSueno.iniciar(30, ahora = T0)
        val tic = TemporizadorDeSueno.tictac(T0 + 60_000)!!
        assertEquals(1f, tic.volumen, 0.001f)
        assertFalse(tic.pausar)
        assertEquals(29 * 60, TemporizadorDeSueno.restanteS)
    }

    @Test
    fun `los ultimos segundos bajan el volumen poco a poco`() {
        TemporizadorDeSueno.iniciar(1, ahora = T0)
        // Faltan 12 s: empieza el desvanecido.
        assertEquals(1f, TemporizadorDeSueno.tictac(T0 + 48_000)!!.volumen, 0.001f)
        // Faltan 6 s: a la mitad.
        assertEquals(0.5f, TemporizadorDeSueno.tictac(T0 + 54_000)!!.volumen, 0.001f)
        // Falta 1 s: casi callado, pero todavia sonando.
        val ultimo = TemporizadorDeSueno.tictac(T0 + 59_000)!!
        assertEquals(1 / 12f, ultimo.volumen, 0.001f)
        assertFalse(ultimo.pausar)
    }

    @Test
    fun `al llegar a cero pausa y devuelve el volumen entero`() {
        TemporizadorDeSueno.iniciar(1, ahora = T0)
        val tic = TemporizadorDeSueno.tictac(T0 + 60_000)!!
        assertTrue("tiene que pausar", tic.pausar)
        // Si no se restaura, el siguiente libro empezaria en silencio.
        assertEquals(1f, tic.volumen, 0.001f)
        assertFalse("y el temporizador se apaga solo", TemporizadorDeSueno.activo())
    }

    @Test
    fun `un tic tardio no lo revive ni lo deja sonando bajo`() {
        // El proceso pudo estar dormido y perderse el instante exacto.
        TemporizadorDeSueno.iniciar(1, ahora = T0)
        val tic = TemporizadorDeSueno.tictac(T0 + 600_000)!!
        assertTrue(tic.pausar)
        assertEquals(1f, tic.volumen, 0.001f)
        assertNull("y no vuelve a disparar", TemporizadorDeSueno.tictac(T0 + 700_000))
    }

    @Test
    fun `cancelarlo lo apaga sin pausar`() {
        TemporizadorDeSueno.iniciar(30, ahora = T0)
        TemporizadorDeSueno.cancelar()
        assertFalse(TemporizadorDeSueno.activo())
        assertEquals(0, TemporizadorDeSueno.restanteS)
        assertNull(TemporizadorDeSueno.tictac(T0 + 60_000))
    }

    @Test
    fun `el restante se cuenta desde el instante final, no acumulando tics`() {
        // Asi no se desfasa aunque el reloj del servicio se retrase.
        TemporizadorDeSueno.iniciar(10, ahora = T0)
        TemporizadorDeSueno.tictac(T0 + 300_000)
        assertEquals(300, TemporizadorDeSueno.restanteS)
    }
}
