package com.lumina.audiolibros.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumina.audiolibros.data.AlmacenLocal
import com.lumina.audiolibros.library.Audiolibro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Criterios de orden, los mismos que ofrece la app de escritorio. */
val ORDENES = listOf(
    "reciente" to "Recientes",
    "titulo" to "Título",
    "autor" to "Autor",
    "progreso" to "Progreso",
)

/** Decodifica la portada fuera del hilo principal para que la lista no dé tirones. */
@Composable
fun recordarPortada(ruta: String?): ImageBitmap? {
    val estado = produceState<ImageBitmap?>(initialValue = null, ruta) {
        value = if (ruta == null) null else withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(ruta)?.asImageBitmap() }.getOrNull()
        }
    }
    return estado.value
}

@Composable
fun Portada(ruta: String?, titulo: String, modifier: Modifier = Modifier) {
    val imagen = recordarPortada(ruta)
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (imagen != null) {
            Image(
                bitmap = imagen,
                contentDescription = titulo,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text("🎧", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaBiblioteca(
    libros: List<Audiolibro>,
    progresos: Map<String, AlmacenLocal.Progreso>,
    refrescando: Boolean,
    onRefrescar: () -> Unit,
    onAbrir: (Audiolibro) -> Unit,
    modifier: Modifier = Modifier,
) {
    var busqueda by remember { mutableStateOf("") }
    var orden by remember { mutableStateOf("reciente") }

    val visibles = remember(libros, busqueda, orden, progresos) {
        val q = busqueda.trim().lowercase()
        val filtrados = if (q.isEmpty()) libros else libros.filter {
            "${it.titulo} ${it.autor}".lowercase().contains(q)
        }
        when (orden) {
            "titulo" -> filtrados.sortedBy { it.titulo.lowercase() }
            "autor" -> filtrados.sortedWith(compareBy({ it.autor.lowercase().ifEmpty { "￿" } }, { it.titulo.lowercase() }))
            "progreso" -> filtrados.sortedByDescending { porcentaje(it, progresos[it.bookId]) }
            // "reciente": lo escuchado más recientemente primero.
            else -> filtrados.sortedByDescending { progresos[it.bookId]?.actualizadoEn ?: 0L }
        }
    }

    // "Continuar escuchando": el libro con la escucha más reciente sin terminar.
    val continuar = remember(libros, progresos) {
        libros.filter { progresos[it.bookId]?.let { p -> !p.terminado && p.posicionMs > 0 } == true }
            .maxByOrNull { progresos[it.bookId]?.actualizadoEn ?: 0L }
    }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = busqueda,
            onValueChange = { busqueda = it },
            placeholder = { Text("Buscar…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ORDENES.forEach { (id, etiqueta) ->
                FilterChip(
                    selected = orden == id,
                    onClick = { orden = id },
                    label = { Text(etiqueta, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        PullToRefreshBox(
            isRefreshing = refrescando,
            onRefresh = onRefrescar,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (continuar != null && busqueda.isBlank()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                        HeroContinuar(continuar, progresos[continuar.bookId], onAbrir)
                    }
                }
                items(visibles, key = { it.uri.toString() }) { libro ->
                    TarjetaLibro(libro, progresos[libro.bookId], onAbrir)
                }
            }
        }
    }
}

@Composable
private fun HeroContinuar(
    libro: Audiolibro,
    progreso: AlmacenLocal.Progreso?,
    onAbrir: (Audiolibro) -> Unit,
) {
    val pct = porcentaje(libro, progreso)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onAbrir(libro) }
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Portada(libro.portada, libro.titulo, Modifier.size(72.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("CONTINUAR ESCUCHANDO", style = MaterialTheme.typography.labelSmall)
            Text(
                libro.titulo,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(
                progress = { pct / 100f },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
            val restante = (libro.duracionMs - (progreso?.posicionMs ?: 0)).coerceAtLeast(0)
            Text(
                "${pct.toInt()}% · quedan ${formatearTiempo(restante)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TarjetaLibro(
    libro: Audiolibro,
    progreso: AlmacenLocal.Progreso?,
    onAbrir: (Audiolibro) -> Unit,
) {
    Column(
        Modifier.clickable { onAbrir(libro) },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Portada(libro.portada, libro.titulo, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(
            libro.titulo,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (libro.autor.isNotEmpty()) {
            Text(
                libro.autor,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val pct = porcentaje(libro, progreso)
        if (pct > 0) {
            LinearProgressIndicator(
                progress = { pct / 100f },
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }
        Text(formatearTiempo(libro.duracionMs), style = MaterialTheme.typography.labelSmall)
    }
}

private fun porcentaje(libro: Audiolibro, progreso: AlmacenLocal.Progreso?): Float {
    if (progreso == null || libro.duracionMs <= 0) return 0f
    if (progreso.terminado) return 100f
    return (progreso.posicionMs * 100f / libro.duracionMs).coerceIn(0f, 100f)
}
