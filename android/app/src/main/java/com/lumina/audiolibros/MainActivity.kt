package com.lumina.audiolibros

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.lumina.audiolibros.ui.FondoReactivo
import com.lumina.audiolibros.ui.LuminaApp
import com.lumina.audiolibros.ui.theme.LuminaTheme
import com.lumina.audiolibros.ui.theme.VioletaLumina

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Raiz() }
    }
}

/**
 * Raíz de la interfaz: sostiene el color de acento y el fondo reactivo, para
 * que ambos envuelvan a toda la app. El acento lo dicta la portada del libro
 * en curso, igual que en el escritorio.
 */
@Composable
private fun Raiz() {
    var acento by remember { mutableStateOf(VioletaLumina) }
    var sonando by remember { mutableStateOf(false) }

    LuminaTheme(acento = acento) {
        // Sin un Surface envolviendo la app, Material deja el color de texto
        // por defecto en negro y nada se lee sobre el fondo oscuro. Se fija
        // aquí para toda la interfaz.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            FondoReactivo(acento = acento, animado = sonando) {
                LuminaApp(
                    onAcento = { acento = it ?: VioletaLumina },
                    onSonando = { sonando = it },
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                )
            }
        }
    }
}
