package com.lumina.audiolibros.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Esta posicion la ha elegido el usuario.»
 *
 * Es lo unico que permite subir una posicion anterior a la que hay en la nube,
 * porque el servidor rechaza cualquier retroceso que no venga marcado. Antes se
 * ponia al saltar y solo se bajaba al abrir otro libro, asi que un unico toque
 * a «+30 s» dejaba todas las subidas de las horas siguientes saltandose la
 * comprobacion. Como los botones de salto son los que mas se usan, la
 * proteccion quedaba apagada casi siempre.
 *
 * Misma spec que src/lib/intencion.js.
 */
class IntencionTest {

    @Test
    fun `un libro recien abierto no tiene intencion pendiente`() {
        assertFalse(Intencion().activa())
    }

    @Test
    fun `saltar la marca`() {
        val i = Intencion()
        i.marcar()
        assertTrue(i.activa())
    }

    @Test
    fun `la intencion se consume con la subida que la lleva a la nube`() {
        val i = Intencion()
        i.marcar()
        i.cumplida(i.sello())
        assertFalse("ya esta guardada: lo que venga despues es inercia", i.activa())
    }

    @Test
    fun `una subida fallida no consume la intencion`() {
        // Si no, un retroceso se perderia por un corte de red: el reintento
        // iria condicionado y el servidor lo rechazaria por ir hacia atras.
        val i = Intencion()
        i.marcar()
        val sello = i.sello()
        assertTrue(i.activa())
        i.cumplida(sello)
        assertFalse("y se consume cuando por fin llega", i.activa())
    }

    @Test
    fun `saltar otra vez mientras la subida esta en vuelo no pierde la intencion nueva`() {
        val i = Intencion()
        i.marcar()
        val selloEnVuelo = i.sello()

        i.marcar()                  // el usuario salta de nuevo
        i.cumplida(selloEnVuelo)    // responde la subida vieja

        assertTrue("la intencion nueva sigue viva", i.activa())
        i.cumplida(i.sello())
        assertFalse(i.activa())
    }

    @Test
    fun `varios saltos seguidos cuentan como una sola intencion pendiente`() {
        val i = Intencion()
        i.marcar(); i.marcar(); i.marcar()
        assertTrue(i.activa())
        i.cumplida(i.sello())
        assertFalse("la ultima subida las salda todas", i.activa())
    }

    @Test
    fun `abrir otro libro empieza sin intencion`() {
        val i = Intencion()
        i.marcar()
        i.reiniciar()
        assertFalse(i.activa())
    }

    @Test
    fun `un salto no desactiva la proteccion el resto de la tarde`() {
        val i = Intencion()
        i.marcar()
        assertTrue("esa subida puede ir hacia atras: la eligio el usuario", i.activa())
        i.cumplida(i.sello())

        // Tres horas escuchando: ninguna subida debe saltarse la comprobacion.
        repeat(360) { subida ->
            assertFalse("la subida $subida debe ir condicionada", i.activa())
            i.cumplida(i.sello())
        }
    }
}
