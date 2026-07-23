package com.lumina.audiolibros.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reglas de resolución de conflictos de docs/SYNC.md. Es la lógica que decide
 * si al abrir un libro manda la posición del móvil o la del ordenador, así que
 * conviene fijarla: un error aquí hace saltar la reproducción sin motivo.
 */
class SupabaseSyncTest {

    private val ahora = 1_700_000_000_000L

    private fun remota(actualizadoEn: Long) = SupabaseSync.Progreso(
        bookId = "libro",
        trackId = "pista",
        posicionSegundos = 100.0,
        posicionGlobalSegundos = 100.0,
        duracionSegundos = 3600.0,
        terminado = false,
        actualizadoEn = actualizadoEn,
        dispositivo = "PC",
    )

    @Test
    fun `sin fila remota manda siempre la local`() {
        assertFalse(SupabaseSync.ganaLaRemota(null, ahora))
    }

    @Test
    fun `una escucha remota claramente posterior gana`() {
        val diezMinutosDespues = ahora + 10 * 60_000
        assertTrue(SupabaseSync.ganaLaRemota(remota(diezMinutosDespues), ahora))
    }

    @Test
    fun `una escucha remota anterior no gana`() {
        assertFalse(SupabaseSync.ganaLaRemota(remota(ahora - 60_000), ahora))
    }

    @Test
    fun `un desfase de reloj menor de un minuto no hace saltar la posicion`() {
        // Treinta segundos de diferencia son ruido entre dispositivos, no una
        // escucha posterior: debe seguir mandando la local.
        assertFalse(SupabaseSync.ganaLaRemota(remota(ahora + 30_000), ahora))
    }

    @Test
    fun `el margen es estricto justo en el limite`() {
        assertFalse(SupabaseSync.ganaLaRemota(remota(ahora + 60_000), ahora))
        assertTrue(SupabaseSync.ganaLaRemota(remota(ahora + 60_001), ahora))
    }
}
