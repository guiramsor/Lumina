package com.lumina.audiolibros.player

import android.content.ComponentName
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import com.lumina.audiolibros.sync.GuardadoDeProgreso
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Lo que Android Auto ve al abrir Lumina.
 *
 * El coche no habla con la pantalla: se conecta al servicio como un navegador
 * de medios, pide el catálogo y manda reproducir un identificador. Esta prueba
 * hace exactamente eso, que es lo único que demuestra que la integración
 * funciona sin subirse al coche.
 */
@RunWith(AndroidJUnit4::class)
class CatalogoDelCocheTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val context = instr.targetContext

    /**
     * Media3 exige crear y usar el navegador en el hilo principal, pero
     * esperar ahí el resultado bloquearía a quien debe completarlo.
     */
    private fun <T> enPrincipal(bloque: () -> ListenableFuture<T>): T {
        val ref = AtomicReference<ListenableFuture<T>>()
        instr.runOnMainSync { ref.set(bloque()) }
        return ref.get().get(20, TimeUnit.SECONDS)
    }

    @Test
    fun elCocheVeElCatalogoConSusCaratulas() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val navegador = enPrincipal { MediaBrowser.Builder(context, token).buildAsync() }

        try {
            val raiz = enPrincipal { navegador.getLibraryRoot(null) }
            assertEquals("la raiz no llega", true, raiz.value != null)
            val idRaiz = raiz.value!!.mediaId
            assertEquals(RAIZ_DEL_CATALOGO, idRaiz)

            val hijos = enPrincipal { navegador.getChildren(idRaiz, 0, 50, null) }
            val libros = hijos.value
            assertTrue("el catalogo llega vacio", libros != null && libros.isNotEmpty())

            val primero = libros!!.first()
            assertTrue("los libros deben poder reproducirse", primero.mediaMetadata.isPlayable == true)
            assertFalse("un libro no es una carpeta", primero.mediaMetadata.isBrowsable == true)
            assertTrue("falta el titulo", !primero.mediaMetadata.title.isNullOrBlank())

            // Lo que se vera en la pantalla del coche.
            val caratula = primero.mediaMetadata.artworkData
            assertTrue(
                "el libro llega sin caratula",
                caratula != null && caratula.isNotEmpty(),
            )
            // Y debe caber por Binder con holgura.
            assertTrue("la caratula es demasiado grande", caratula!!.size < 400_000)
        } finally {
            instr.runOnMainSync { navegador.release() }
        }
    }

    /**
     * Pedir un libro desde el coche tiene que dejar el guardado listo.
     *
     * Registrar la sesión no basta: mientras no se desbloquee, el guardián
     * rechaza toda posición por prudencia y un viaje entero se queda sin
     * guardar, en silencio y sin ningún error. Solo se nota conduciendo, que es
     * justo lo que esta prueba evita tener que hacer.
     */
    @Test
    fun pedirUnLibroDesdeElCocheDejaElGuardadoListo() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val navegador = enPrincipal { MediaBrowser.Builder(context, token).buildAsync() }

        try {
            val raiz = enPrincipal { navegador.getLibraryRoot(null) }
            val hijos = enPrincipal { navegador.getChildren(raiz.value!!.mediaId, 0, 50, null) }
            val libros = hijos.value
            assertTrue("el catalogo llega vacio", libros != null && libros.isNotEmpty())
            val elegido = libros!!.first()

            GuardadoDeProgreso.cerrar()
            // Exactamente lo que hace el coche: mandar reproducir un id.
            instr.runOnMainSync { navegador.setMediaItem(elegido) }

            // La resolucion consulta la nube, asi que puede tardar.
            val limite = System.currentTimeMillis() + 25_000
            while (System.currentTimeMillis() < limite &&
                GuardadoDeProgreso.libroEnCurso() != elegido.mediaId
            ) {
                Thread.sleep(250)
            }
            instr.runOnMainSync { navegador.pause() }

            assertEquals(
                "el coche no registro la sesion de guardado",
                elegido.mediaId,
                GuardadoDeProgreso.libroEnCurso(),
            )
            assertTrue(
                "la sesion quedo registrada pero bloqueada: no se guardaria nada en todo el viaje",
                GuardadoDeProgreso.estaColocado(),
            )
        } finally {
            instr.runOnMainSync { navegador.release() }
        }
    }
}
