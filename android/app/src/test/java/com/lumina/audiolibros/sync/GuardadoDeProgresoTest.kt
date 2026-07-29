package com.lumina.audiolibros.sync

import com.lumina.audiolibros.sync.GuardadoDeProgreso.Circunstancias
import com.lumina.audiolibros.sync.GuardadoDeProgreso.Veredicto
import com.lumina.audiolibros.sync.GuardadoDeProgreso.decidir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La tabla de decisiones del guardado.
 *
 * Antes estas reglas estaban repartidas entre la pantalla y el servicio, y el
 * servicio se saltaba casi todas: cerrar la app desde recientes subia la
 * posicion del movil sin comprobar nada. Aqui se fijan de una vez, con los
 * escenarios concretos que costaron horas de escucha.
 */
class GuardadoDeProgresoTest {

    /** Escuchando tan tranquilo: todo en orden y toca subir. */
    private fun normal(
        hayLibroAbierto: Boolean = true,
        colocado: Boolean = true,
        yaTerminado: Boolean = false,
        marcaFinal: Boolean = false,
        posicionSegundos: Double = 25593.0,
        duracionMs: Long = 47_631_000L,
        hayCuenta: Boolean = true,
        lecturaFiable: Boolean = true,
        msDesdeLaUltimaSubida: Long = 60_000L,
        forzar: Boolean = false,
        posicionRemota: Double? = 25000.0,
        intencionada: Boolean = false,
    ) = Circunstancias(
        hayLibroAbierto, colocado, yaTerminado, marcaFinal, posicionSegundos, duracionMs,
        hayCuenta, lecturaFiable, msDesdeLaUltimaSubida, forzar, posicionRemota, intencionada,
    )

    @Test
    fun `escuchando con todo en orden se sube`() {
        assertEquals(Veredicto.SUBIR, decidir(normal()))
    }

    /* ---------- El agujero de cerrar la app desde recientes ---------- */

    @Test
    fun `sin libro registrado no se sube ni se guarda`() {
        // Es el caso de cerrar la app desde recientes tras reiniciarse el
        // proceso: no se sabe si la nube se leyo ni por donde iba el otro
        // dispositivo. Antes esta ruta subia igualmente.
        val v = decidir(normal(hayLibroAbierto = false))
        assertEquals(Veredicto.SIN_LIBRO, v)
        assertFalse(v.sube)
        assertFalse(v.guardaEnDisco)
    }

    @Test
    fun `si la lectura remota fallo al abrir no se sube nunca, ni forzando`() {
        // El escenario que costaba siete horas: abrir sin cobertura, escuchar
        // dos minutos y cerrar la app con la cobertura ya recuperada.
        val v = decidir(normal(lecturaFiable = false, forzar = true, posicionSegundos = 120.0))
        assertEquals(Veredicto.LECTURA_NO_FIABLE, v)
        assertFalse(v.sube)
        // Pero en el disco si se guarda: escuchar sin conexion debe funcionar.
        assertTrue(v.guardaEnDisco)
    }

    @Test
    fun `ni siquiera al marcar el final se sube con una lectura no fiable`() {
        val v = decidir(normal(lecturaFiable = false, marcaFinal = true, forzar = true))
        assertFalse(v.sube)
    }

    /* ---------- Filas invisibles por no tener duracion ---------- */

    @Test
    fun `sin duracion conocida no se sube`() {
        // Una fila con duration nula no la devuelve nunca la busqueda por
        // rango de duracion, asi que el libro se quedaria partido en dos filas
        // que ya no vuelven a encontrarse.
        val v = decidir(normal(duracionMs = 0L, forzar = true))
        assertEquals(Veredicto.SIN_DURACION, v)
        assertFalse(v.sube)
        assertTrue(v.guardaEnDisco)
    }

    @Test
    fun `una duracion desconocida del reproductor no impide guardar en disco`() {
        assertTrue(decidir(normal(duracionMs = 0L)).guardaEnDisco)
    }

    /* ---------- Reglas de docs SYNC ---------- */

    @Test
    fun `no se pisa una posicion remota mas avanzada`() {
        val v = decidir(normal(posicionSegundos = 100.0, posicionRemota = 25593.0))
        assertEquals(Veredicto.LA_NUBE_VA_MAS_AVANZADA, v)
        assertFalse(v.sube)
    }

    @Test
    fun `un retroceso deliberado si se propaga`() {
        // Vas por 7 h, te pierdes y retrocedes a 6 h 30: esa es tu posicion.
        val v = decidir(normal(posicionSegundos = 23400.0, posicionRemota = 25593.0, intencionada = true))
        assertEquals(Veredicto.SUBIR, v)
    }

    @Test
    fun `reabrir un libro terminado se propaga aunque la nube vaya avanzada`() {
        val v = decidir(
            normal(posicionSegundos = 0.0, posicionRemota = 25593.0, marcaFinal = true, forzar = true)
        )
        assertEquals(Veredicto.SUBIR, v)
    }

    @Test
    fun `sin cuenta no se sube pero si se guarda`() {
        val v = decidir(normal(hayCuenta = false))
        assertEquals(Veredicto.SIN_CUENTA, v)
        assertFalse(v.sube)
        assertTrue(v.guardaEnDisco)
    }

    /* ---------- Cerrojos de coherencia ---------- */

    @Test
    fun `antes de colocar la posicion no se guarda nada`() {
        // Los primeros milisegundos el reproductor esta en cero: guardarlos
        // borraria el avance real.
        val v = decidir(normal(colocado = false, posicionSegundos = 0.0))
        assertEquals(Veredicto.SIN_COLOCAR, v)
        assertFalse(v.guardaEnDisco)
    }

    @Test
    fun `despues de marcar el final ya no se guarda mas`() {
        assertEquals(Veredicto.YA_TERMINADO, decidir(normal(yaTerminado = true)))
        // Salvo la propia marca de final, que debe poder escribirse.
        assertEquals(
            Veredicto.SUBIR,
            decidir(normal(yaTerminado = true, marcaFinal = true, forzar = true, posicionRemota = null)),
        )
    }

    @Test
    fun `estar al principio del libro es una posicion valida y se guarda`() {
        // El guardado local del segundo cero es legitimo: es lo que hace que
        // reiniciar un libro desde el movil quede escrito.
        val v = decidir(normal(posicionSegundos = 0.0, posicionRemota = null))
        assertTrue(v.guardaEnDisco)
    }

    @Test
    fun `la espera entre subidas ahorra trafico pero se salta al forzar`() {
        assertEquals(Veredicto.DEMASIADO_PRONTO, decidir(normal(msDesdeLaUltimaSubida = 5_000L)))
        assertEquals(Veredicto.SUBIR, decidir(normal(msDesdeLaUltimaSubida = 5_000L, forzar = true)))
    }

    @Test
    fun `los cerrojos de seguridad van antes que el ahorro de trafico`() {
        // Si no, una lectura fallida podria colarse como "todavia no toca" y
        // acabar subiendo en el siguiente intento forzado.
        val v = decidir(normal(lecturaFiable = false, msDesdeLaUltimaSubida = 5_000L))
        assertEquals(Veredicto.LECTURA_NO_FIABLE, v)
    }
}
