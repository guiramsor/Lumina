package com.lumina.audiolibros.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Tema Noche, el mismo del escritorio.
 *
 * Los valores salen de las variables de src/styles.css: fondo casi negro con
 * tinte violeta, superficies de cristal y trazos a un 12 % de blanco. El
 * acento no es fijo: lo aporta la portada del libro que se esté escuchando,
 * igual que en Windows.
 */

/** Violeta de Lumina (hsl 265 60% 60%), usado mientras no hay portada. */
val VioletaLumina = Color(0xFF8E6BD9)

val FondoNoche = Color(0xFF07060D)
val SuperficieNoche = Color(0xFF14121E)
val SuperficieAlta = Color(0xFF1C1A2A)
val TextoPrincipal = Color(0xF2FFFFFF)
val TextoTenue = Color(0x8FFFFFFF)
val TrazoSuave = Color(0x1FFFFFFF)

@Composable
fun LuminaTheme(
    acento: Color = VioletaLumina,
    content: @Composable () -> Unit,
) {
    // Sobre el acento va texto oscuro si es claro y texto claro si es oscuro.
    // Darlo por supuesto es la forma segura de acabar con letras negras
    // invisibles cuando la portada tiñe la app de un color apagado.
    val sobreAcento = if (acento.luminance() > 0.45f) Color(0xFF0B0A14) else TextoPrincipal

    val esquema = darkColorScheme(
        primary = acento,
        onPrimary = sobreAcento,
        primaryContainer = acento.copy(alpha = 0.22f),
        onPrimaryContainer = TextoPrincipal,
        secondary = acento.copy(alpha = 0.8f),
        onSecondary = sobreAcento,
        secondaryContainer = acento.copy(alpha = 0.24f),
        onSecondaryContainer = TextoPrincipal,
        tertiary = acento,
        onTertiary = sobreAcento,
        background = FondoNoche,
        onBackground = TextoPrincipal,
        surface = SuperficieNoche,
        onSurface = TextoPrincipal,
        surfaceVariant = SuperficieAlta,
        onSurfaceVariant = TextoTenue,
        outline = TrazoSuave,
        outlineVariant = TrazoSuave,
        error = Color(0xFFFF8080),
        onError = Color(0xFF0B0A14),
        // Los contenedores que Material usa para dialogos y menus: sin fijarlos
        // se quedan en los grises por defecto, que desentonan con el violeta.
        surfaceContainer = SuperficieNoche,
        surfaceContainerHigh = SuperficieAlta,
        surfaceContainerHighest = SuperficieAlta,
        surfaceContainerLow = SuperficieNoche,
        surfaceContainerLowest = FondoNoche,
        inverseSurface = TextoPrincipal,
        inverseOnSurface = FondoNoche,
    )

    val vista = LocalView.current
    if (!vista.isInEditMode) {
        SideEffect {
            // Barras del sistema transparentes con iconos claros: el fondo de
            // la app llega hasta arriba, como la ventana sin marco del PC.
            val ventana = (vista.context as Activity).window
            WindowCompat.getInsetsController(ventana, vista).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = esquema,
        typography = Typography,
        content = content,
    )
}
