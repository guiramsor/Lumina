package com.lumina.audiolibros.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.lumina.audiolibros.data.AlmacenLocal
import com.lumina.audiolibros.library.AudioLibrary
import com.lumina.audiolibros.library.Audiolibro
import com.lumina.audiolibros.sync.SupabaseSync
import kotlinx.coroutines.launch

private enum class Pantalla { BIBLIOTECA, REPRODUCTOR, SESION, ESTADISTICAS }

@Composable
fun LuminaApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val alcance = rememberCoroutineScope()
    val estado = recordarEstadoReproductor(alcance)

    var pantalla by remember { mutableStateOf(Pantalla.BIBLIOTECA) }
    var biblioteca by remember { mutableStateOf<List<Audiolibro>>(emptyList()) }
    var progresos by remember { mutableStateOf<Map<String, AlmacenLocal.Progreso>>(emptyMap()) }
    var permisoConcedido by remember { mutableStateOf(tienePermisoAudio(context)) }
    var refrescando by remember { mutableStateOf(false) }
    var avanceEscaneo by remember { mutableFloatStateOf(1f) }
    var enSesion by remember { mutableStateOf(SupabaseSync.haySesion(context)) }

    suspend fun cargar() {
        refrescando = true
        avanceEscaneo = 0f
        biblioteca = enFondo {
            AudioLibrary.listar(context) { hecho, total ->
                avanceEscaneo = if (total == 0) 1f else hecho.toFloat() / total
            }
        }
        progresos = AlmacenLocal.progresos(context)
        avanceEscaneo = 1f
        refrescando = false
    }

    val pedirPermisos = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permisoConcedido = tienePermisoAudio(context)
        if (permisoConcedido) alcance.launch { cargar() }
    }

    LaunchedEffect(Unit) {
        val pendientes = permisosNecesarios().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (pendientes.isNotEmpty()) pedirPermisos.launch(pendientes.toTypedArray())
        if (permisoConcedido) cargar()
    }

    // Refrescar las barras de progreso al volver del reproductor.
    LaunchedEffect(pantalla) {
        if (pantalla == Pantalla.BIBLIOTECA) progresos = AlmacenLocal.progresos(context)
    }

    when (pantalla) {
        Pantalla.REPRODUCTOR -> {
            PantallaReproductor(estado, onVolver = { pantalla = Pantalla.BIBLIOTECA }, modifier = modifier)
            return
        }
        Pantalla.SESION -> {
            PantallaSesion(
                enSesion = enSesion,
                onEntrar = { correo, contrasena, alFallar ->
                    alcance.launch {
                        SupabaseSync.iniciarSesion(context, correo, contrasena)
                            .onSuccess { enSesion = true; pantalla = Pantalla.BIBLIOTECA }
                            .onFailure { alFallar(it.message ?: "No se pudo iniciar sesión.") }
                    }
                },
                onSalir = { SupabaseSync.cerrarSesion(context); enSesion = false },
                onCerrar = { pantalla = Pantalla.BIBLIOTECA },
                modifier = modifier,
            )
            return
        }
        Pantalla.ESTADISTICAS -> {
            PantallaEstadisticas(onCerrar = { pantalla = Pantalla.BIBLIOTECA }, modifier = modifier)
            return
        }
        Pantalla.BIBLIOTECA -> Unit
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Lumina", style = MaterialTheme.typography.headlineSmall)
            Row {
                TextButton(onClick = { pantalla = Pantalla.ESTADISTICAS }) { Text("Escucha") }
                TextButton(onClick = { pantalla = Pantalla.SESION }) {
                    Text(if (enSesion) "Sincronizado" else "Entrar")
                }
            }
        }

        if (estado.libro != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { pantalla = Pantalla.REPRODUCTOR }) {
                    Text("▸ ${estado.libro!!.titulo}", style = MaterialTheme.typography.labelLarge)
                }
                Button(onClick = { estado.alternar() }) {
                    Text(if (estado.sonando) "Pausar" else "Seguir")
                }
            }
        }

        if (!permisoConcedido) {
            Column(
                Modifier.fillMaxSize(),
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

        if (refrescando && avanceEscaneo < 1f) {
            LinearProgressIndicator(
                progress = { avanceEscaneo },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }

        PantallaBiblioteca(
            libros = biblioteca,
            progresos = progresos,
            refrescando = refrescando,
            onRefrescar = { alcance.launch { cargar() } },
            onAbrir = { libro ->
                estado.abrir(libro) { progresos = AlmacenLocal.progresos(context) }
                pantalla = Pantalla.REPRODUCTOR
            },
            modifier = Modifier.fillMaxSize(),
        )
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
        modifier.fillMaxSize().padding(24.dp),
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
    return ContextCompat.checkSelfPermission(context, permiso) == PackageManager.PERMISSION_GRANTED
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
