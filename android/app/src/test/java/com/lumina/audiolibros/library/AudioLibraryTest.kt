package com.lumina.audiolibros.library

import org.junit.Assert.assertFalse
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
}
