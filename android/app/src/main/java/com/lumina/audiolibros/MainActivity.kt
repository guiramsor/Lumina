package com.lumina.audiolibros

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.lumina.audiolibros.player.PlaybackService
import com.lumina.audiolibros.ui.theme.LuminaTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LuminaTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ReproductorScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

/**
 * Pantalla mínima del primer hito: elegir un audio del móvil y reproducirlo a
 * través del servicio, para validar que el sonido continúa en segundo plano.
 * La biblioteca y la sincronización llegan después.
 */
@Composable
fun ReproductorScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var nombreArchivo by remember { mutableStateOf<String?>(null) }
    var sonando by remember { mutableStateOf(false) }
    var posicion by remember { mutableLongStateOf(0L) }
    var duracion by remember { mutableLongStateOf(0L) }

    // La notificación de controles necesita permiso a partir de Android 13.
    val pedirNotificaciones = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pedirNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Conexión con el servicio de reproducción.
    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val futuro = MediaController.Builder(context, token).buildAsync()
        futuro.addListener(
            { controller = futuro.get() },
            ContextCompat.getMainExecutor(context)
        )
        onDispose {
            controller = null
            MediaController.releaseFuture(futuro)
        }
    }

    // Estado de reproducción reflejado en la interfaz.
    DisposableEffect(controller) {
        val c = controller
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                sonando = isPlaying
            }
        }
        c?.addListener(listener)
        sonando = c?.isPlaying == true
        onDispose { c?.removeListener(listener) }
    }

    LaunchedEffect(controller, sonando) {
        while (true) {
            controller?.let {
                posicion = it.currentPosition
                duracion = it.duration.coerceAtLeast(0L)
            }
            delay(500)
        }
    }

    // Recuperar el último audio abierto: sin esto, cada reinicio obliga a
    // volver a buscarlo en el selector.
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        if (c.mediaItemCount > 0) {
            nombreArchivo = c.currentMediaItem?.mediaMetadata?.title?.toString()
            return@LaunchedEffect
        }
        ultimaUri(context)?.let { uri ->
            val nombre = nombreVisible(context, uri)
            nombreArchivo = nombre
            c.setMediaItem(itemDe(uri, nombre))
            c.prepare()
        }
    }

    val elegirAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Sin permiso persistente, la URI deja de valer al reiniciar la app.
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        recordarUri(context, uri)
        val nombre = nombreVisible(context, uri)
        nombreArchivo = nombre
        controller?.apply {
            setMediaItem(itemDe(uri, nombre))
            prepare()
            play()
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Lumina", style = MaterialTheme.typography.headlineMedium)
        Text(
            nombreArchivo ?: "Ningún audio seleccionado",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (duracion > 0) {
            Text(
                "${formatearTiempo(posicion)} / ${formatearTiempo(duracion)}",
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        OutlinedButton(onClick = { elegirAudio.launch(arrayOf("audio/*")) }) {
            Text("Elegir un audio")
        }

        Button(
            onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
            enabled = controller != null && nombreArchivo != null,
        ) {
            Text(if (sonando) "Pausar" else "Reproducir")
        }
    }
}

/**
 * Sin metadatos la notificación sale sin título, y el stack de Bluetooth
 * inunda el log con "Timeout while waiting for metadata to sync".
 */
private fun itemDe(uri: Uri, nombre: String): MediaItem =
    MediaItem.Builder()
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(nombre)
                .setArtist("Audiolibro")
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build()
        )
        .build()

private const val PREFS = "lumina"
private const val CLAVE_ULTIMA_URI = "ultima_uri"

private fun recordarUri(context: Context, uri: Uri) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(CLAVE_ULTIMA_URI, uri.toString())
        .apply()
}

/** Última URI abierta, solo si aún conservamos permiso persistente sobre ella. */
private fun ultimaUri(context: Context): Uri? {
    val guardada = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(CLAVE_ULTIMA_URI, null) ?: return null
    val uri = guardada.toUri()
    val tienePermiso = context.contentResolver.persistedUriPermissions
        .any { it.uri == uri && it.isReadPermission }
    return if (tienePermiso) uri else null
}

/**
 * Nombre legible de un documento elegido con el selector. El último segmento
 * de una URI de SAF es un identificador interno, no sirve para mostrar.
 */
private fun nombreVisible(context: Context, uri: Uri): String {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nombre = cursor.getString(0)
                if (!nombre.isNullOrBlank()) return nombre
            }
        }
    return uri.lastPathSegment ?: "Audio"
}

private fun formatearTiempo(ms: Long): String {
    val totalSegundos = ms / 1000
    val horas = totalSegundos / 3600
    val minutos = (totalSegundos % 3600) / 60
    val segundos = totalSegundos % 60
    return if (horas > 0) {
        String.format("%d:%02d:%02d", horas, minutos, segundos)
    } else {
        String.format("%d:%02d", minutos, segundos)
    }
}
