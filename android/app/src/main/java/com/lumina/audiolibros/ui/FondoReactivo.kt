package com.lumina.audiolibros.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.lumina.audiolibros.ui.theme.FondoNoche

/**
 * Fondo reactivo: el equivalente móvil del ReactiveBackground del escritorio.
 *
 * Dos manchas de color difuminadas sobre el negro violáceo, teñidas con el
 * color dominante de la portada. Cuando suena algo respiran muy despacio; en
 * pausa se quedan quietas, así la animación no cuesta batería sin motivo.
 */
@Composable
fun FondoReactivo(
    acento: Color,
    animado: Boolean,
    modifier: Modifier = Modifier,
    contenido: @Composable BoxScope.() -> Unit,
) {
    val transicion = rememberInfiniteTransition(label = "respiracion")
    val pulso by transicion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // Nueve segundos por ciclo: se percibe como algo vivo, no como una
            // animación que reclama atención.
            animation = tween(9_000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulso",
    )
    val factor = if (animado) pulso else 0.5f

    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(FondoNoche)

            val anchura = size.width
            val altura = size.height

            // Mancha superior, la protagonista.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(acento.copy(alpha = 0.34f), Color.Transparent),
                    center = Offset(anchura * 0.22f, altura * (0.16f + 0.03f * factor)),
                    radius = anchura * (0.95f + 0.08f * factor),
                ),
                radius = anchura * (0.95f + 0.08f * factor),
                center = Offset(anchura * 0.22f, altura * (0.16f + 0.03f * factor)),
            )

            // Mancha inferior, más apagada y desplazada, para dar profundidad.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(acento.copy(alpha = 0.18f), Color.Transparent),
                    center = Offset(anchura * 0.85f, altura * (0.82f - 0.04f * factor)),
                    radius = anchura * (0.8f + 0.1f * (1f - factor)),
                ),
                radius = anchura * (0.8f + 0.1f * (1f - factor)),
                center = Offset(anchura * 0.85f, altura * (0.82f - 0.04f * factor)),
            )

            // Viñeteado: oscurece los bordes y centra la mirada, igual que el
            // vignette del canvas del escritorio.
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, FondoNoche.copy(alpha = 0.75f)),
                    center = Offset(anchura / 2f, altura / 2f),
                    radius = maxOf(anchura, altura) * 0.75f,
                )
            )
        }
        contenido()
    }
}
