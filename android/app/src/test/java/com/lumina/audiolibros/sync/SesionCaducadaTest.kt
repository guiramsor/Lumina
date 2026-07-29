package com.lumina.audiolibros.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Cuando el refresco del token falla, hay que decidir si la sesion sigue
 * sirviendo o hay que tirarla.
 *
 * Confundirlos tiene consecuencias en los dos sentidos: tirar la sesion por un
 * corte de red obliga a escribir la contrasena cada vez que se pierde
 * cobertura; conservarla cuando el servidor la ha rechazado deja la app
 * "con sesion" pero incapaz de sincronizar para siempre, sin mas senal que un
 * aviso generico de falta de conexion. Este segundo caso paso de verdad: el
 * movil estuvo dos dias dando "JWT expired" en cada peticion sin decir nada.
 */
class SesionCaducadaTest {

    private fun rechazada(e: Throwable): Boolean {
        val m = SupabaseSync::class.java.getDeclaredMethod("sesionRechazada", Throwable::class.java)
        m.isAccessible = true
        return m.invoke(SupabaseSync, e) as Boolean
    }

    /* ---------- Se conserva la sesion: el fallo es pasajero ---------- */

    @Test
    fun `un corte de red no tira la sesion`() {
        assertFalse(rechazada(UnknownHostException("Unable to resolve host")))
        assertFalse(rechazada(SocketTimeoutException("timeout")))
        assertFalse(rechazada(IOException("conexion perdida")))
    }

    /* ---------- Se tira la sesion: no se arregla sola ---------- */

    @Test
    fun `un refresh_token invalido tira la sesion`() {
        assertTrue(
            rechazada(
                Exception("""{"error":"invalid_grant","error_description":"Invalid Refresh Token: Already Used"}""")
            )
        )
    }

    @Test
    fun `un 400 o un 401 del servidor tiran la sesion`() {
        assertTrue(rechazada(Exception("HTTP 400")))
        assertTrue(rechazada(Exception("HTTP 401")))
    }

    @Test
    fun `el mensaje de refresh token caducado tira la sesion`() {
        assertTrue(rechazada(Exception("""{"code":"refresh_token_not_found"}""")))
    }

    @Test
    fun `un error del servidor que no es de credenciales no tira la sesion`() {
        // Un 500 es del servidor, no de la sesion: se reintentara.
        assertFalse(rechazada(Exception("HTTP 500")))
        assertFalse(rechazada(Exception("HTTP 503")))
    }
}
