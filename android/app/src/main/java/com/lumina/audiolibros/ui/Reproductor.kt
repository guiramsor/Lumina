package com.lumina.audiolibros.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.audiolibros.data.AlmacenLocal
import kotlinx.coroutines.delay
import java.util.UUID

private val MINUTOS_SUENO = listOf(5, 10, 15, 30, 45, 60)

@Composable
fun PantallaReproductor(
    estado: EstadoReproductor,
    onVolver: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val libro = estado.libro ?: return

    // Deslizar hacia atrás vuelve a la biblioteca, no cierra la app.
    BackHandler { onVolver() }

    var arrastrando by remember { mutableStateOf<Float?>(null) }
    var dialogo by remember { mutableStateOf<String?>(null) }
    var marcadores by remember { mutableStateOf(AlmacenLocal.marcadores(context, libro.bookId)) }

    LaunchedEffect(libro.bookId) {
        marcadores = AlmacenLocal.marcadores(context, libro.bookId)
    }

    val duracion = estado.duracionMs.coerceAtLeast(1L)
    // Al arrastrar, el tiempo sigue al dedo en vez de al reproductor.
    val posicionMostrada = arrastrando?.let { (it * duracion).toLong() } ?: estado.posicionMs

    Column(
        modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BotonIcono(onClick = onVolver) { c, g -> iconoChevronIzq(c, g) }
            Spacer(Modifier.weight(1f))
            IconoSincronizacion(estado) { dialogo = "sync" }
        }

        Spacer(Modifier.weight(1f))

        Portada(libro.portada, libro.titulo, Modifier.fillMaxWidth(0.78f).aspectRatio(1f))

        Spacer(Modifier.height(24.dp))

        Text(
            libro.titulo,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (libro.autor.isNotEmpty()) {
            Text(
                libro.autor,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        Spacer(Modifier.height(20.dp))

        Slider(
            value = arrastrando ?: (estado.posicionMs.toFloat() / duracion).coerceIn(0f, 1f),
            onValueChange = { arrastrando = it },
            onValueChangeFinished = {
                arrastrando?.let { estado.buscar((it * duracion).toLong()) }
                arrastrando = null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatearTiempo(posicionMostrada), style = MaterialTheme.typography.labelMedium)
            Text(
                "-${formatearTiempo((duracion - posicionMostrada).coerceAtLeast(0))}",
                style = MaterialTheme.typography.labelMedium,
            )
        }

        Spacer(Modifier.height(20.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SaltoIcono("15", atras = true) { estado.saltar(-15) }

            // Botón principal, con el halo del acento del libro detrás.
            Box(
                Modifier
                    .size(78.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { estado.alternar() },
                contentAlignment = Alignment.Center,
            ) {
                IconoLumina(
                    tamano = 34.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                    dibujo = if (estado.sonando) { c, g -> iconoPausa(c, g) } else { c, g -> iconoPlay(c, g) },
                )
            }

            SaltoIcono("30", atras = false) { estado.saltar(30) }
        }

        Spacer(Modifier.height(20.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BotonVelocidad(estado)
            BotonSueno(estado)
            BotonEtiqueta(
                texto = if (marcadores.isEmpty()) "Marcar" else "${marcadores.size}",
                onClick = { dialogo = "marcadores" },
            ) { c, g -> iconoMarcador(c, g) }
        }

        Spacer(Modifier.weight(1f))
    }

    when (dialogo) {
        "sync" -> DialogoSincronizacion(estado) { dialogo = null }
        "marcadores" -> DialogoMarcadores(
            marcadores = marcadores,
            posicionActual = estado.posicionMs,
            onIr = { estado.buscar(it); dialogo = null },
            onAnadir = { nota ->
                AlmacenLocal.anadirMarcador(
                    context,
                    AlmacenLocal.Marcador(
                        id = UUID.randomUUID().toString(),
                        bookId = libro.bookId,
                        posicionMs = estado.posicionMs,
                        nota = nota,
                        creadoEn = System.currentTimeMillis(),
                    )
                )
                marcadores = AlmacenLocal.marcadores(context, libro.bookId)
            },
            onBorrar = { id ->
                AlmacenLocal.borrarMarcador(context, libro.bookId, id)
                marcadores = AlmacenLocal.marcadores(context, libro.bookId)
            },
            onCerrar = { dialogo = null },
        )
    }
}

/**
 * Salto de 15 o 30 segundos: la flecha circular del escritorio con el número
 * dentro, en vez de un botón de texto.
 */
@Composable
private fun SaltoIcono(segundos: String, atras: Boolean, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        IconoLumina(tamano = 30.dp, color = color) { c, g ->
            if (atras) iconoAtras15(c, g) else iconoAdelante30(c, g)
        }
        Text(
            segundos,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = color,
        )
    }
}

/* ---------------- Sincronización ---------------- */

/**
 * Un solo carácter en la esquina. La información detallada vive en el diálogo
 * que se abre al pulsarlo: en la pantalla del libro no debe competir con nada.
 */
@Composable
private fun IconoSincronizacion(estado: EstadoReproductor, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        when (estado.estadoSync) {
            EstadoSync.SUBIENDO -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            EstadoSync.HECHO -> IconoLumina(20.dp, MaterialTheme.colorScheme.primary) { c, g -> iconoNube(c, g) }
            EstadoSync.FALLO -> IconoLumina(20.dp, MaterialTheme.colorScheme.error) { c, g -> iconoNube(c, g) }
            EstadoSync.INACTIVO -> IconoLumina(20.dp, MaterialTheme.colorScheme.onSurfaceVariant) { c, g -> iconoNube(c, g) }
        }
    }
}

@Composable
private fun DialogoSincronizacion(estado: EstadoReproductor, onCerrar: () -> Unit) {
    var ahora by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            ahora = System.currentTimeMillis()
            delay(5_000)
        }
    }

    val detalle = when (estado.estadoSync) {
        EstadoSync.SUBIENDO -> "Guardando la posición en la nube…"
        EstadoSync.FALLO -> "No se ha podido contactar con la nube. La posición está guardada en el móvil y se subirá cuando vuelva la conexión."
        EstadoSync.HECHO -> {
            val hace = estado.sincronizadoEn?.let { (ahora - it) / 1000 } ?: 0
            when {
                hace < 10 -> "Posición sincronizada ahora mismo."
                hace < 60 -> "Última sincronización hace $hace segundos."
                else -> "Última sincronización hace ${hace / 60} min."
            }
        }
        EstadoSync.INACTIVO -> "Todavía no se ha subido nada en esta sesión. La posición se guarda al pausar y cada 30 segundos."
    }

    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Sincronización") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(detalle, style = MaterialTheme.typography.bodyMedium)
                estado.aviso?.let {
                    HorizontalDivider()
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onCerrar) { Text("Cerrar") } },
    )
}

/* ---------------- Velocidad y sueño ---------------- */

/** Píldora de cristal con icono y texto, el patrón de botón secundario. */
@Composable
private fun BotonEtiqueta(
    texto: String,
    onClick: () -> Unit,
    resaltado: Boolean = false,
    dibujo: DrawScope.(Color, Float) -> Unit,
) {
    val color = if (resaltado) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        IconoLumina(tamano = 17.dp, color = color, dibujo = dibujo)
        Text(texto, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** Botón que despliega las velocidades y se cierra al elegir una. */
@Composable
private fun BotonVelocidad(estado: EstadoReproductor) {
    var abierto by remember { mutableStateOf(false) }
    Box {
        BotonEtiqueta(
            texto = "${estado.velocidad}×".replace(".0×", "×"),
            onClick = { abierto = true },
        ) { c, g -> iconoVelocidad(c, g) }
        DropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }) {
            VELOCIDADES.forEach { v ->
                DropdownMenuItem(
                    text = { Text("${v}×".replace(".0×", "×")) },
                    onClick = {
                        estado.cambiarVelocidad(v)
                        abierto = false
                    },
                )
            }
        }
    }
}

@Composable
private fun BotonSueno(estado: EstadoReproductor) {
    var abierto by remember { mutableStateOf(false) }
    Box {
        BotonEtiqueta(
            texto = if (estado.modoSueno == ModoSueno.MINUTOS) {
                formatearTiempo(estado.suenoRestanteS * 1000L)
            } else "Sueño",
            onClick = { abierto = true },
            resaltado = estado.modoSueno == ModoSueno.MINUTOS,
        ) { c, g -> iconoLuna(c, g) }
        DropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }) {
            MINUTOS_SUENO.forEach { m ->
                DropdownMenuItem(
                    text = { Text("$m minutos") },
                    onClick = { estado.iniciarSueno(m); abierto = false },
                )
            }
            if (estado.modoSueno == ModoSueno.MINUTOS) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Cancelar") },
                    onClick = { estado.cancelarSueno(); abierto = false },
                )
            }
        }
    }
}

/* ---------------- Marcadores ---------------- */

@Composable
private fun DialogoMarcadores(
    marcadores: List<AlmacenLocal.Marcador>,
    posicionActual: Long,
    onIr: (Long) -> Unit,
    onAnadir: (String) -> Unit,
    onBorrar: (String) -> Unit,
    onCerrar: () -> Unit,
) {
    var nota by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Marcadores") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = nota,
                    onValueChange = { nota = it },
                    label = { Text("Nota en ${formatearTiempo(posicionActual)}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { onAnadir(nota.trim()); nota = "" }) {
                    Text("Guardar marcador aquí")
                }

                if (marcadores.isNotEmpty()) HorizontalDivider()
                marcadores.forEach { marcador ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { onIr(marcador.posicionMs) }, modifier = Modifier.weight(1f)) {
                            Text(
                                "${formatearTiempo(marcador.posicionMs)} · ${marcador.nota.ifEmpty { "Sin nota" }}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = { onBorrar(marcador.id) }) { Text("✕") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onCerrar) { Text("Cerrar") } },
    )
}
