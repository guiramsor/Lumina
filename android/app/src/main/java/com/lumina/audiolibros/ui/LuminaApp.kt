package com.lumina.audiolibros.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.lumina.audiolibros.library.AudioLibrary
import com.lumina.audiolibros.library.Audiolibro
import com.lumina.audiolibros.player.EXTRA_TRACK_ID
import com.lumina.audiolibros.player.PlaybackService
import com.lumina.audiolibros.sync.Fingerprint
import com.lumina.audiolibros.sync.SupabaseSync
import com.lumina.audiolibros.sync.UriSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val INTERVALO_SUBIDA_MS = 30_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LuminaApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val alcance = rememberCoroutineScope()

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var biblioteca by remember { mutableStateOf<List<Audiolibro>>(emptyList()) }
    var permisoConcedido by remember { mutableStateOf(tienePermisoAudio(context)) }
    var enSesion by remember { mutableStateOf(SupabaseSync.haySesion(context)) }
    var mostrarSesion by remember { mutableStateOf(false) }

    var sonando by remember { mutableStateOf(false) }
    var tituloActual by remember { mutableStateOf<String?>(null) }
    var bookIdActual by remember { mutableStateOf<String?>(null) }
    var trackIdActual by remember { mutableStateOf<String?>(null) }
    var posicion by remember { mutableLongStateOf(0L) }
    var duracion by remember { mutableLongStateOf(0L) }
    var aviso by remember { mutableStateOf<String?>(null) }
    var cargando by remember { mutableStateOf(false) }
    var refrescando by remember { mutableStateOf(false) }

    // Releer la biblioteca del teléfono: recoge los libros añadidos o borrados
    // desde fuera de la app.
    suspend fun refrescarBiblioteca() {
        refrescando = true
        biblioteca = withContext(Dispatchers.IO) { AudioLibrary.listar(context) }
        refrescando = false
    }

    val pedirPermisos = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permisoConcedido = tienePermisoAudio(context)
        if (permisoConcedido) biblioteca = AudioLibrary.listar(context)
    }

    LaunchedEffect(Unit) {
        val pendientes = permisosNecesarios().filter {
            ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (pendientes.isNotEmpty()) pedirPermisos.launch(pendientes.toTypedArray())
        if (permisoConcedido) biblioteca = withContext(Dispatchers.IO) { AudioLibrary.listar(context) }
    }

    // Conexión con el servicio de reproducción.
    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val futuro = MediaController.Builder(context, token).buildAsync()
        futuro.addListener({ controller = futuro.get() }, ContextCompat.getMainExecutor(context))
        onDispose {
            controller = null
            MediaController.releaseFuture(futuro)
        }
    }

    DisposableEffect(controller) {
        val c = controller
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                sonando = isPlaying
                // Al pausar se sube siempre: es el momento en que el otro
                // dispositivo querrá retomar.
                if (!isPlaying) {
                    val id = bookIdActual ?: return
                    val pos = c?.currentPosition ?: return
                    alcance.launch { subir(context, id, trackIdActual, pos, c.duration, tituloActual) }
                }
            }
        }
        c?.addListener(listener)
        sonando = c?.isPlaying == true
        onDispose { c?.removeListener(listener) }
    }

    LaunchedEffect(controller) {
        while (true) {
            controller?.let {
                posicion = it.currentPosition
                duracion = it.duration.coerceAtLeast(0L)
            }
            delay(500)
        }
    }

    // Subida periódica mientras se escucha.
    LaunchedEffect(bookIdActual, sonando) {
        if (!sonando) return@LaunchedEffect
        while (true) {
            delay(INTERVALO_SUBIDA_MS)
            val c = controller ?: continue
            val id = bookIdActual ?: continue
            subir(context, id, trackIdActual, c.currentPosition, c.duration, tituloActual)
        }
    }

    fun abrir(libro: Audiolibro) {
        val c = controller ?: return
        cargando = true
        aviso = null
        alcance.launch {
            val huellaPista = withContext(Dispatchers.IO) {
                runCatching { Fingerprint.ofTrack(UriSource(context.contentResolver, libro.uri)) }.getOrNull()
            }
            val bookId = huellaPista?.let { Fingerprint.ofBook(listOf(it)) }
            trackIdActual = huellaPista
            bookIdActual = bookId
            tituloActual = libro.titulo

            val remoto = bookId?.let { SupabaseSync.descargar(context, it) }

            // La identidad viaja dentro del MediaItem para que el servicio
            // pueda guardar la posición al cerrar la app, cuando esta pantalla
            // ya no existe.
            c.setMediaItem(
                MediaItem.Builder()
                    .setUri(libro.uri)
                    .setMediaId(bookId.orEmpty())
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(libro.titulo)
                            .setArtist("Audiolibro")
                            .setIsPlayable(true)
                            .setIsBrowsable(false)
                            .setExtras(Bundle().apply { putString(EXTRA_TRACK_ID, huellaPista) })
                            .build()
                    )
                    .build()
            )
            c.prepare()
            if (remoto != null && remoto.posicionSegundos > 0 && !remoto.terminado) {
                c.seekTo((remoto.posicionSegundos * 1000).toLong())
                aviso = "Retomado desde ${formatearTiempo((remoto.posicionSegundos * 1000).toLong())}" +
                    (remoto.dispositivo?.let { " · $it" } ?: "")
            }
            c.play()
            cargando = false
        }
    }

    if (mostrarSesion) {
        PantallaSesion(
            enSesion = enSesion,
            onEntrar = { correo, contrasena, alFallar ->
                alcance.launch {
                    SupabaseSync.iniciarSesion(context, correo, contrasena)
                        .onSuccess {
                            enSesion = true
                            mostrarSesion = false
                        }
                        .onFailure { alFallar(it.message ?: "No se pudo iniciar sesión.") }
                }
            },
            onSalir = {
                SupabaseSync.cerrarSesion(context)
                enSesion = false
            },
            onCerrar = { mostrarSesion = false },
            modifier = modifier,
        )
        return
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Lumina", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = { mostrarSesion = true }) {
                Text(if (enSesion) "Sincronizando" else "Iniciar sesión")
            }
        }

        if (tituloActual != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(tituloActual!!, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(
                    "${formatearTiempo(posicion)} / ${formatearTiempo(duracion)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                aviso?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = {
                    controller?.let { if (it.isPlaying) it.pause() else it.play() }
                }) {
                    Text(if (sonando) "Pausar" else "Reproducir")
                }
            }
        }

        if (!permisoConcedido) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Lumina necesita permiso para leer los audios del teléfono.")
                OutlinedButton(onClick = { pedirPermisos.launch(permisosNecesarios().toTypedArray()) }) {
                    Text("Conceder permiso")
                }
            }
            return@Column
        }

        Text(
            "Tu biblioteca (${biblioteca.size})",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )

        if (cargando) {
            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        PullToRefreshBox(
            isRefreshing = refrescando,
            onRefresh = { alcance.launch { refrescarBiblioteca() } },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(biblioteca, key = { it.uri.toString() }) { libro ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { abrir(libro) }
                            .padding(vertical = 12.dp)
                    ) {
                        Text(libro.titulo, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                        Text(
                            buildString {
                                append(formatearTiempo(libro.duracionMs))
                                if (libro.carpeta.isNotEmpty()) append(" · ${libro.carpeta.trimEnd('/')}")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PantallaSesion(
    enSesion: Boolean,
    onEntrar: (String, String, (String) -> Unit) -> Unit,
    onSalir: () -> Unit,
    onCerrar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var correo by remember { mutableStateOf("") }
    var contrasena by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Text("Sincronización", style = MaterialTheme.typography.headlineSmall)

        if (!SupabaseSync.configurado()) {
            Text("Esta compilación no lleva credenciales de sincronización.")
        } else if (enSesion) {
            Text("Sincronización activa.")
            Text(
                "Tu posición se guarda al pausar y cada 30 segundos. Los audios nunca se suben.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onSalir) { Text("Cerrar sesión") }
        } else {
            Text(
                "Entra con la misma cuenta que usas en el ordenador.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = correo,
                onValueChange = { correo = it },
                label = { Text("Correo") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = contrasena,
                onValueChange = { contrasena = it },
                label = { Text("Contraseña") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Button(
                onClick = { error = null; onEntrar(correo.trim(), contrasena) { error = it } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Iniciar sesión") }
        }

        TextButton(onClick = onCerrar) { Text("Volver") }
    }
}

private suspend fun subir(
    context: Context,
    bookId: String,
    trackId: String?,
    posicionMs: Long,
    duracionMs: Long,
    titulo: String?,
) {
    if (posicionMs <= 0) return
    SupabaseSync.subir(
        context,
        SupabaseSync.Progreso(
            bookId = bookId,
            trackId = trackId,
            posicionSegundos = posicionMs / 1000.0,
            // Un archivo por libro: la posición dentro de la pista y la global
            // coinciden. Cambiará cuando haya libros de varias pistas.
            posicionGlobalSegundos = posicionMs / 1000.0,
            duracionSegundos = if (duracionMs > 0) duracionMs / 1000.0 else null,
            terminado = false,
            actualizadoEn = System.currentTimeMillis(),
            dispositivo = null,
        ),
        titulo,
    )
}

private fun permisosNecesarios(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.READ_MEDIA_AUDIO)
        add(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

private fun tienePermisoAudio(context: Context): Boolean {
    val permiso = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return ContextCompat.checkSelfPermission(context, permiso) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
}

internal fun formatearTiempo(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSegundos = ms / 1000
    val horas = totalSegundos / 3600
    val minutos = (totalSegundos % 3600) / 60
    val segundos = totalSegundos % 60
    return if (horas > 0) {
        String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", horas, minutos, segundos)
    } else {
        String.format(java.util.Locale.getDefault(), "%d:%02d", minutos, segundos)
    }
}
