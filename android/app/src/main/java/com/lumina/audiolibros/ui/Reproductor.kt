package com.lumina.audiolibros.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.lumina.audiolibros.data.AlmacenLocal
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
    var arrastrando by remember { mutableStateOf<Float?>(null) }
    var panel by remember { mutableStateOf<String?>(null) }
    var marcadores by remember { mutableStateOf(AlmacenLocal.marcadores(context, libro.bookId)) }
    var notaNueva by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(libro.bookId) {
        marcadores = AlmacenLocal.marcadores(context, libro.bookId)
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            TextButton(onClick = onVolver) { Text("‹ Biblioteca") }
        }

        Portada(libro.portada, libro.titulo, Modifier.fillMaxWidth(0.72f).aspectRatio(1f))

        Text(
            libro.titulo,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (libro.autor.isNotEmpty()) {
            Text(libro.autor, style = MaterialTheme.typography.bodyMedium)
        }
        estado.aviso?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }

        // Barra de progreso arrastrable.
        val duracion = estado.duracionMs.coerceAtLeast(1L)
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
            Text(formatearTiempo(estado.posicionMs), style = MaterialTheme.typography.labelMedium)
            Text(
                "-${formatearTiempo((estado.duracionMs - estado.posicionMs).coerceAtLeast(0))}",
                style = MaterialTheme.typography.labelMedium,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { estado.saltar(-15) }) { Text("−15s") }
            Button(onClick = { estado.alternar() }, modifier = Modifier.size(width = 132.dp, height = 52.dp)) {
                Text(if (estado.sonando) "Pausar" else "Reproducir")
            }
            OutlinedButton(onClick = { estado.saltar(30) }) { Text("+30s") }
        }

        // Velocidad de reproducción, guardada por libro.
        Text("Velocidad · ${estado.velocidad}×", style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            VELOCIDADES.forEach { v ->
                FilterChip(
                    selected = estado.velocidad == v,
                    onClick = { estado.cambiarVelocidad(v) },
                    label = { Text("${v}×", style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = { panel = if (panel == "sueno") null else "sueno" }) {
                Text(
                    if (estado.modoSueno == ModoSueno.MINUTOS) {
                        "Sueño · ${formatearTiempo(estado.suenoRestanteS * 1000L)}"
                    } else "Temporizador"
                )
            }
            FilledTonalButton(onClick = { notaNueva = "" }) { Text("Marcador") }
            FilledTonalButton(onClick = { panel = if (panel == "marcadores") null else "marcadores" }) {
                Text("Lista (${marcadores.size})")
            }
        }

        if (panel == "sueno") {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MINUTOS_SUENO.forEach { m ->
                        FilterChip(
                            selected = false,
                            onClick = { estado.iniciarSueno(m); panel = null },
                            label = { Text("${m}m", style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                if (estado.modoSueno == ModoSueno.MINUTOS) {
                    TextButton(onClick = { estado.cancelarSueno() }) { Text("Cancelar temporizador") }
                }
            }
        }

        if (panel == "marcadores") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (marcadores.isEmpty()) {
                    Text("Aún no hay marcadores.", style = MaterialTheme.typography.bodySmall)
                }
                marcadores.forEach { marcador ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { estado.buscar(marcador.posicionMs) }) {
                            Text("${formatearTiempo(marcador.posicionMs)} · ${marcador.nota.ifEmpty { "Sin nota" }}")
                        }
                        TextButton(onClick = {
                            AlmacenLocal.borrarMarcador(context, libro.bookId, marcador.id)
                            marcadores = AlmacenLocal.marcadores(context, libro.bookId)
                        }) { Text("Borrar") }
                    }
                }
            }
        }
    }

    if (notaNueva != null) {
        AlertDialog(
            onDismissRequest = { notaNueva = null },
            title = { Text("Marcador en ${formatearTiempo(estado.posicionMs)}") },
            text = {
                OutlinedTextField(
                    value = notaNueva.orEmpty(),
                    onValueChange = { notaNueva = it },
                    label = { Text("Nota (opcional)") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AlmacenLocal.anadirMarcador(
                        context,
                        AlmacenLocal.Marcador(
                            id = UUID.randomUUID().toString(),
                            bookId = libro.bookId,
                            posicionMs = estado.posicionMs,
                            nota = notaNueva.orEmpty().trim(),
                            creadoEn = System.currentTimeMillis(),
                        )
                    )
                    marcadores = AlmacenLocal.marcadores(context, libro.bookId)
                    notaNueva = null
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { notaNueva = null }) { Text("Cancelar") } },
        )
    }
}
