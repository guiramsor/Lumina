package com.lumina.audiolibros.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Los iconos de Lumina, portados uno a uno desde src/components/Icons.jsx.
 *
 * Se dibujan a mano en vez de usar un juego de iconos de Material para que las
 * dos aplicaciones se vean idénticas: mismos trazados, mismo lienzo de 24×24 y
 * el mismo grosor de línea de 1,8 con extremos redondeados.
 */

private const val LIENZO = 24f

/** Trazo (contorno) con el mismo estilo que el `base` del escritorio. */
private fun DrawScope.trazo(datos: String, color: Color, grosor: Float) {
    drawPath(
        path = PathParser().parsePathString(datos).toPath(),
        color = color,
        style = Stroke(width = grosor, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** Relleno, para las piezas macizas (el triángulo de play, por ejemplo). */
private fun DrawScope.relleno(datos: String, color: Color) {
    drawPath(path = PathParser().parsePathString(datos).toPath(), color = color)
}

private fun DrawScope.circuloTrazo(cx: Float, cy: Float, r: Float, color: Color, grosor: Float) {
    drawCircle(color, radius = r, center = Offset(cx, cy), style = Stroke(width = grosor, cap = StrokeCap.Round))
}

/**
 * Lienzo común: escala el sistema de coordenadas 24×24 al tamaño pedido, de
 * modo que los trazados se copian tal cual del original.
 */
@Composable
fun IconoLumina(
    tamano: Dp = 24.dp,
    color: Color = Color.White,
    grosor: Float = 1.8f,
    modifier: Modifier = Modifier,
    dibujo: DrawScope.(Color, Float) -> Unit,
) {
    Canvas(modifier.requiredSize(tamano)) {
        val escala = size.minDimension / LIENZO
        scale(escala, pivot = Offset.Zero) {
            dibujo(color, grosor)
        }
    }
}

/* ---------------- Reproducción ---------------- */

val PlayPath = "M7 5.5 19 12 7 18.5z"
val PausaIzq = "M6.5 5h3.6v14h-3.6z"
val PausaDer = "M13.9 5h3.6v14h-3.6z"

fun DrawScope.iconoPlay(color: Color, grosor: Float) = relleno(PlayPath, color)

fun DrawScope.iconoPausa(color: Color, grosor: Float) {
    relleno(PausaIzq, color)
    relleno(PausaDer, color)
}

/** Flecha circular hacia atrás; el «15» se dibuja aparte como texto. */
fun DrawScope.iconoAtras15(color: Color, grosor: Float) {
    trazo("M13.5 4.8A7 7 0 1 1 10.5 4.8", color, grosor)
    trazo("M15.3 2.9 13.5 4.8 15.3 6.7", color, grosor)
}

fun DrawScope.iconoAdelante30(color: Color, grosor: Float) {
    trazo("M10.5 4.8A7 7 0 1 0 13.5 4.8", color, grosor)
    trazo("M8.7 2.9 10.5 4.8 8.7 6.7", color, grosor)
}

/* ---------------- Interfaz ---------------- */

fun DrawScope.iconoLuna(color: Color, grosor: Float) =
    trazo("M20 14.5A8 8 0 0 1 9.5 4a7 7 0 1 0 10.5 10.5z", color, grosor)

fun DrawScope.iconoMarcador(color: Color, grosor: Float) =
    trazo("M7 4h10a1 1 0 0 1 1 1v15l-6-4-6 4V5a1 1 0 0 1 1-1z", color, grosor)

fun DrawScope.iconoMarcadorLleno(color: Color, grosor: Float) =
    relleno("M7 4h10a1 1 0 0 1 1 1v15l-6-4-6 4V5a1 1 0 0 1 1-1z", color)

fun DrawScope.iconoLista(color: Color, grosor: Float) {
    trazo("M8 6h12M8 12h12M8 18h12", color, grosor)
    drawCircle(color, 1.1f, Offset(4f, 6f))
    drawCircle(color, 1.1f, Offset(4f, 12f))
    drawCircle(color, 1.1f, Offset(4f, 18f))
}

fun DrawScope.iconoBuscar(color: Color, grosor: Float) {
    circuloTrazo(11f, 11f, 6.5f, color, grosor)
    trazo("M15.8 15.8 20.5 20.5", color, grosor)
}

fun DrawScope.iconoGrafico(color: Color, grosor: Float) {
    trazo("M4 20V10", color, grosor)
    trazo("M10 20V4", color, grosor)
    trazo("M16 20v-8", color, grosor)
    trazo("M21 20H3", color, grosor)
}

fun DrawScope.iconoNube(color: Color, grosor: Float) {
    trazo("M7.2 18.5a4 4 0 0 1-.4-8 5.4 5.4 0 0 1 10.3-1.2 3.9 3.9 0 0 1 .6 7.7", color, grosor)
    trazo("M12 12.5v7", color, grosor)
    trazo("M9.6 17.1 12 19.5l2.4-2.4", color, grosor)
}

fun DrawScope.iconoChevronIzq(color: Color, grosor: Float) =
    trazo("M15 5l-7 7 7 7", color, grosor)

fun DrawScope.iconoCerrar(color: Color, grosor: Float) =
    trazo("M6 6l12 12M18 6 6 18", color, grosor)

fun DrawScope.iconoVelocidad(color: Color, grosor: Float) {
    trazo("M12 4a8 8 0 1 0 8 8", color, grosor)
    trazo("M12 12l4-4", color, grosor)
    drawCircle(color, 1.2f, Offset(20f, 4f))
}

fun DrawScope.iconoLibro(color: Color, grosor: Float) {
    trazo("M4 5.5C4 4.7 4.7 4 5.5 4H11v15H5.5A1.5 1.5 0 0 0 4 20.5z", color, grosor)
    trazo("M20 5.5C20 4.7 19.3 4 18.5 4H13v15h5.5a1.5 1.5 0 0 1 1.5 1.5z", color, grosor)
}

fun DrawScope.iconoPapelera(color: Color, grosor: Float) =
    trazo("M5 7h14M10 7V5h4v2M6 7l1 13h10l1-13", color, grosor)

fun DrawScope.iconoLlama(color: Color, grosor: Float) =
    trazo(
        "M12 21c-3.9 0-6.5-2.5-6.5-6 0-2.6 1.6-4.6 3-6.2C9.9 7.2 11 5.8 11 3.5c2.6 1.3 4 3.4 4 5.5 " +
            "0 .9-.2 1.7-.6 2.4.8-.2 1.5-.7 2-1.4 1.3 1.4 2.1 3.2 2.1 5 0 3.5-2.6 6-6.5 6z",
        color, grosor,
    )
