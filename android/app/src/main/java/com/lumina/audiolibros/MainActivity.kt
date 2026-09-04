package com.lumina.audiolibros

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.lumina.audiolibros.player.EXTRA_ABRIR_REPRODUCTOR
import com.lumina.audiolibros.ui.FondoReactivo
import com.lumina.audiolibros.ui.LuminaApp
import com.lumina.audiolibros.ui.theme.LuminaTheme
import com.lumina.audiolibros.ui.theme.VioletaLumina

class MainActivity : ComponentActivity() {

    /**
     * La notificación pide abrir el reproductor, no la biblioteca.
     *
     * Es estado y no una simple lectura del intent porque la aplicación puede
     * estar ya abierta: en ese caso el aviso llega por `onNewIntent`, con la
     * pantalla ya compuesta.
     */
    private val abrirReproductor = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        abrirReproductor.value = intent?.getBooleanExtra(EXTRA_ABRIR_REPRODUCTOR, false) == true
        setContent { Raiz(abrirReproductor) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_ABRIR_REPRODUCTOR, false)) abrirReproductor.value = true
    }
}

/**
 * Raíz de la interfaz: sostiene el color de acento y el fondo reactivo, para
 * que ambos envuelvan a toda la app. El acento lo dicta la portada del libro
 * en curso, igual que en el escritorio.
 */
@Composable
private fun Raiz(abrirReproductor: MutableState<Boolean>) {
    var acento by remember { mutableStateOf(VioletaLumina) }
    var sonando by remember { mutableStateOf(false) }

    LuminaTheme(acento = acento) {
        // Sin un Surface envolviendo la app, Material deja el color de texto
        // por defecto en negro y nada se lee sobre el fondo oscuro. Se fija
        // aquí para toda la interfaz.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            FondoReactivo(acento = acento, animado = sonando) {
                LuminaApp(
                    abrirReproductor = abrirReproductor.value,
                    onReproductorAbierto = { abrirReproductor.value = false },
                    onAcento = { acento = it ?: VioletaLumina },
                    onSonando = { sonando = it },
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                )
            }
        }
    }
}
