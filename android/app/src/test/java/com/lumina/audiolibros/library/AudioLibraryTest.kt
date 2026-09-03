package com.lumina.audiolibros.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Qué carpetas se consideran de audiolibros.
 *
 * Sin este filtro, cualquier canción de más de un minuto entraba en la
 * biblioteca, y en un teléfono con música eso es casi todo lo que aparece.
 */
class AudioLibraryTest {

    @Test
    fun `reconoce las carpetas de audiolibros habituales`() {
        assertTrue(AudioLibrary.pareceCarpetaDeLibros("Audiobooks/"))
        assertTrue(AudioLibrary.pareceCarpetaDeLibros("audiobooks/Sanderson/"))
        assertTrue(AudioLibrary.pareceCarpetaDeLibros("Audiolibros/"))
        assertTrue(AudioLibrary.pareceCarpetaDeLibros("Download/audio_libros/"))
        // Da igual como este escrito.
        assertTrue(AudioLibrary.pareceCarpetaDeLibros("AUDIOBOOK/"))
    }

    @Test
    fun `no confunde la musica ni las descargas`() {
        assertFalse(AudioLibrary.pareceCarpetaDeLibros("Music/"))
        assertFalse(AudioLibrary.pareceCarpetaDeLibros("Download/"))
        assertFalse(AudioLibrary.pareceCarpetaDeLibros("Podcasts/"))
        assertFalse(AudioLibrary.pareceCarpetaDeLibros("WhatsApp/Media/WhatsApp Audio/"))
        assertFalse(AudioLibrary.pareceCarpetaDeLibros(""))
    }

    /* ---------------- Agrupar los capitulos en un libro ---------------- */

    /**
     * El movil trataba cada archivo como un libro. La huella de un libro es el
     * hash de las huellas de TODAS sus pistas, asi que un libro que en el
     * ordenador son doce capitulos nunca podia coincidir con doce libros de una
     * pista: ni por huella ni por duracion. Sin dar ningun error.
     *
     * El criterio de agrupacion tiene que ser el mismo que el de `groupKey` en
     * importBooks.js del escritorio: carpeta mas etiqueta de album.
     */

    @Test
    fun `los archivos de la misma carpeta y album son el mismo libro`() {
        assertEquals(
            AudioLibrary.claveDeAgrupacion("Audiobooks/Elantris/", "Elantris"),
            AudioLibrary.claveDeAgrupacion("Audiobooks/Elantris/", "Elantris"),
        )
    }

    @Test
    fun `carpetas distintas son libros distintos`() {
        assertNotEquals(
            AudioLibrary.claveDeAgrupacion("Audiobooks/Elantris/", "X"),
            AudioLibrary.claveDeAgrupacion("Audiobooks/Nacidos/", "X"),
        )
    }

    @Test
    fun `albumes distintos en la misma carpeta son libros distintos`() {
        // Dos libros sueltos en la misma carpeta no deben fundirse en uno.
        assertNotEquals(
            AudioLibrary.claveDeAgrupacion("Audiobooks/", "Elantris"),
            AudioLibrary.claveDeAgrupacion("Audiobooks/", "Nacidos de la Bruma"),
        )
    }

    @Test
    fun `una carpeta sin album agrupa igual`() {
        // Es el caso de una carpeta de capitulos sin etiquetar.
        assertEquals(
            AudioLibrary.claveDeAgrupacion("Audiobooks/Elantris/", null),
            AudioLibrary.claveDeAgrupacion("Audiobooks/Elantris/", ""),
        )
    }

    @Test
    fun `el album no distingue mayusculas ni espacios sobrantes`() {
        assertEquals(
            AudioLibrary.claveDeAgrupacion("Audiobooks/", "Elantris"),
            AudioLibrary.claveDeAgrupacion("Audiobooks/", "  elantris  "),
        )
    }

    @Test
    fun `la barra final de la carpeta da igual`() {
        // MediaStore no siempre es consistente con ella.
        assertEquals(
            AudioLibrary.claveDeAgrupacion("Audiobooks/Elantris", "X"),
            AudioLibrary.claveDeAgrupacion("Audiobooks/Elantris/", "X"),
        )
    }
}
