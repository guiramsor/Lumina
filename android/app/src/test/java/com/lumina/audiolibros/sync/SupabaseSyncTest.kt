package com.lumina.audiolibros.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reglas de resolución de conflictos de docs/SYNC.md, tal y como las aplica el
 * cliente de sincronización. Es la lógica que decide si al abrir un libro manda
 * la posición del móvil o la del ordenador: un error aquí borra horas de
 * escucha, así que conviene fijarla.
 *
 * La regla es «gana la escucha más avanzada», no la más reciente. Antes ganaba
 * la más reciente y eso permitía que un dispositivo hiciera retroceder al otro.
 */
class SupabaseSyncTest {

    private fun remota(
        posicion: Double,
        global: Double = 0.0,
    ) = SupabaseSync.Progreso(
        bookId = "libro",
        trackId = "pista",
        posicionSegundos = posicion,
        posicionGlobalSegundos = global,
        duracionSegundos = 47631.0,
        terminado = false,
        actualizadoEn = 0L,
        dispositivo = "PC",
    )

    @Test
    fun `sin fila remota manda siempre la local`() {
        assertFalse(SupabaseSync.ganaLaRemota(null, 100.0))
    }

    @Test
    fun `una escucha remota mas avanzada gana`() {
        assertTrue(SupabaseSync.ganaLaRemota(remota(5000.0), 100.0))
    }

    @Test
    fun `una escucha remota mas atrasada no gana`() {
        // Es el caso que de verdad importa: el movil no debe retroceder a la
        // posicion vieja que quedo guardada en el ordenador.
        assertFalse(SupabaseSync.ganaLaRemota(remota(100.0), 5000.0))
    }

    @Test
    fun `una diferencia de segundos no hace saltar la reproduccion`() {
        assertFalse(SupabaseSync.ganaLaRemota(remota(1003.0), 1000.0))
        assertTrue(SupabaseSync.ganaLaRemota(remota(1006.0), 1000.0))
    }

    @Test
    fun `en libros de varias pistas manda la posicion global`() {
        // position es el segundo dentro de la pista; global_position, desde el
        // inicio del libro. Comparar la primera daria un resultado absurdo.
        assertTrue(SupabaseSync.ganaLaRemota(remota(posicion = 10.0, global = 9000.0), 1000.0))
    }
}
