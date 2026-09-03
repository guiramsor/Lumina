package com.lumina.audiolibros.ui

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.lumina.audiolibros.data.AlmacenLocal
import com.lumina.audiolibros.library.Audiolibro
import com.lumina.audiolibros.player.Catalogo
import com.lumina.audiolibros.player.PlaybackService
import com.lumina.audiolibros.player.TemporizadorDeSueno
import com.lumina.audiolibros.sync.EmparejarLibros
import com.lumina.audiolibros.sync.GuardadoDeProgreso
import com.lumina.audiolibros.sync.PosicionDelLibro
import com.lumina.audiolibros.sync.SupabaseSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Retroceso al reanudar, copiado de la app de escritorio: cuanto más tiempo
 * lleves sin escuchar, más atrás retoma para que recuperes el hilo.
 * Debe coincidir con smartRewindSeconds de src/player/PlayerContext.jsx.
 */
fun segundosDeRebobinado(pausaMs: Long): Long {
    val s = pausaMs / 1000
    return when {
        s < 30 -> 0
        s < 5 * 60 -> 5
        s < 30 * 60 -> 10
        s < 2 * 3600 -> 15
        s < 24 * 3600 -> 25
        else -> 30
    }
}

val VELOCIDADES = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

/** Modo del temporizador de sueño. */
enum class ModoSueno { NINGUNO, MINUTOS }

/** Para poder mostrar sin ruido si la posición llegó o no a la nube. */
enum class EstadoSync { INACTIVO, SUBIENDO, HECHO, FALLO }

/**
 * Estado central de la reproducción: la versión Android del PlayerContext.
 * Reúne el reproductor, el almacén local y la sincronización, para que las
 * pantallas solo pinten.
 */
class EstadoReproductor(
    private val context: Context,
    private val alcance: CoroutineScope,
) {
    var controller: MediaController? = null
    var libro by mutableStateOf<Audiolibro?>(null)
        private set
    var sonando by mutableStateOf(false)
    var posicionMs by mutableLongStateOf(0L)
    var duracionMs by mutableLongStateOf(0L)
    var velocidad by mutableFloatStateOf(1f)
        private set
    var aviso by mutableStateOf<String?>(null)
    var cargando by mutableStateOf(false)

    var modoSueno by mutableStateOf(ModoSueno.NINGUNO)
        private set
    var suenoRestanteS by mutableIntStateOf(0)

    var estadoSync by mutableStateOf(EstadoSync.INACTIVO)
        private set
    var sincronizadoEn by mutableStateOf<Long?>(null)
        private set

    private var ultimaPausaEn: Long? = null

    /* ---------------- Abrir ---------------- */

    fun abrir(elegido: Audiolibro, alTerminar: () -> Unit = {}) {
        val c = controller ?: return
        cargando = true
        aviso = null
        alcance.launch {
            val local = AlmacenLocal.progreso(context, elegido.bookId)
            val guardado = AlmacenLocal.syncId(context, elegido.bookId)
            // Averigua en qué fila de la nube vive el libro. La duración y las
            // etiquetas lo reconocen aunque el archivo del ordenador no sea
            // idéntico al de este móvil, y entonces se adopta su identificador.
            val lectura = SupabaseSync.reconciliar(
                context,
                fingerprint = elegido.bookId,
                syncId = guardado,
                duracionSegundos = elegido.duracionMs / 1000.0,
                titulo = elegido.titulo,
                autor = elegido.autor,
            )
            val remoto = lectura.getOrNull()?.progreso
            val lecturaRemotaFiable = lectura.isSuccess
            // Si la lectura falló no se sabe nada nuevo: se conserva el
            // identificador ya adoptado. Guardar aquí la huella lo pisaría y
            // devolvería el móvil a escribir en su propia fila, deshaciendo la
            // adopción por un simple corte de red.
            val idNube = lectura.getOrNull()?.syncId ?: guardado ?: elegido.bookId
            if (lecturaRemotaFiable) AlmacenLocal.guardarSyncId(context, elegido.bookId, idNube)

            var posicion = local?.posicionMs ?: 0L
            var escuchadoEn = local?.actualizadoEn ?: 0L
            var terminado = local?.terminado == true

            // Gana la escucha más avanzada, venga de donde venga.
            if (SupabaseSync.ganaLaRemota(remoto, posicion / 1000.0) && remoto != null) {
                // Absoluta, no la del interior de la pista: si el ordenador
                // tiene el libro partido en capítulos, `position` es el segundo
                // dentro de uno de ellos y saltaríamos a un punto sin relación.
                val segundos = EmparejarLibros.posicionAbsoluta(
                    remoto.posicionGlobalSegundos, remoto.posicionSegundos
                )
                posicion = (segundos * 1000).toLong()
                    .coerceIn(0L, if (elegido.duracionMs > 0) elegido.duracionMs else Long.MAX_VALUE)
                escuchadoEn = remoto.actualizadoEn
                terminado = remoto.terminado
                aviso = "Retomado desde ${formatearTiempo(posicion)}" +
                    (remoto.dispositivo?.let { " · $it" } ?: "")
            }

            // Un libro terminado se reabre desde el principio.
            if (terminado) posicion = 0L

            // Rebobinado inteligente entre sesiones.
            if (posicion > 0 && escuchadoEn > 0) {
                posicion = (posicion - segundosDeRebobinado(System.currentTimeMillis() - escuchadoEn) * 1000)
                    .coerceAtLeast(0L)
            }

            val suya = local?.velocidad ?: AlmacenLocal.velocidadPorDefecto(context)
            libro = elegido
            velocidad = suya
            ultimaPausaEn = null

            // A partir de aquí el guardado es cosa del servicio, que sigue vivo
            // aunque esta pantalla desaparezca.
            GuardadoDeProgreso.abrir(
                bookId = elegido.bookId,
                syncId = idNube,
                duraciones = elegido.duraciones,
                trackIds = elegido.pistas.map { it.trackId },
                titulo = elegido.titulo,
                autor = elegido.autor,
                lecturaFiable = lecturaRemotaFiable,
                posicionRemota = remoto?.let {
                    EmparejarLibros.posicionAbsoluta(it.posicionGlobalSegundos, it.posicionSegundos)
                },
                velocidad = suya,
            )
            // Volver a empezar un libro terminado es una decisión, no inercia,
            // así que debe propagarse aunque la nube vaya más avanzada.
            if (terminado) GuardadoDeProgreso.marcarIntencionada()

            android.util.Log.i(
                "LuminaSync",
                "abrir '${elegido.titulo}' local=${local?.posicionMs}ms " +
                    "remoto=${remoto?.posicionSegundos?.toLong()}s fiable=$lecturaRemotaFiable -> ${posicion}ms"
            )

            // El mismo constructor que usa el coche: así la carátula, el título
            // y el autor se ven igual en la notificación y en Android Auto.
            val pistas = Catalogo.pistasDe(elegido, idNube)
            // De la posición global del libro a «qué pista y por dónde de ella».
            val punto = PosicionDelLibro.desdeGlobal(elegido.duraciones, posicion)

            // La posición se pasa al cargar el medio, no con un seekTo posterior:
            // así no hay ventana en la que el reproductor esté en el segundo 0.
            c.setMediaItems(pistas, punto.indice, punto.dentro)
            c.setPlaybackSpeed(suya)
            c.prepare()
            c.play()
            GuardadoDeProgreso.colocado()
            cargando = false

            if (!lecturaRemotaFiable) {
                // Distinguir las dos causas importa: una se arregla sola al
                // recuperar cobertura y la otra no se arregla nunca hasta que
                // el usuario vuelva a entrar. Decir «sin conexión» en el
                // segundo caso deja creyendo que sincroniza cuando no lo hace.
                aviso = if (SupabaseSync.sesionCaducada) {
                    "La sesión ha caducado: vuelve a iniciarla para sincronizar. " +
                        "Mientras tanto se escucha desde la posición del móvil y no se sube nada."
                } else {
                    "Sin conexión con la nube: se escucha desde la posición del móvil " +
                        "y no se subirá nada para no pisar la del ordenador."
                }
            }
            alTerminar()
        }
    }

    /* ---------------- Controles ---------------- */

    fun alternar() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            // Al reanudar tras una pausa larga, retroceder un poco. En segundos
            // del libro, para que pueda cruzar el borde de una pista.
            ultimaPausaEn?.let { pausa ->
                val atras = segundosDeRebobinado(System.currentTimeMillis() - pausa) * 1000
                if (atras > 0) irA(posicionGlobal(c) - atras)
            }
            ultimaPausaEn = null
            c.play()
        }
    }

    /**
     * Saltar y buscar trabajan en segundos **del libro**, no del archivo: en un
     * libro de capítulos, «+30 s» al final de una pista tiene que pasar a la
     * siguiente, no quedarse clavado en el borde.
     */
    fun saltar(segundos: Long) {
        val c = controller ?: return
        irA(posicionGlobal(c) + segundos * 1000)
        ultimaPausaEn = null
        GuardadoDeProgreso.marcarIntencionada()
        guardarAhora()
    }

    fun buscar(globalMs: Long) {
        irA(globalMs)
        ultimaPausaEn = null
        GuardadoDeProgreso.marcarIntencionada()
        guardarAhora()
    }

    fun cambiarVelocidad(nueva: Float) {
        velocidad = nueva
        controller?.setPlaybackSpeed(nueva)
        AlmacenLocal.guardarVelocidadPorDefecto(context, nueva)
        GuardadoDeProgreso.fijarVelocidad(nueva)
    }

    /**
     * Guardado inmediato tras una acción del usuario.
     *
     * El reloj periódico vive en el servicio, no aquí: una barra movida quiere
     * quedar escrita ya, sin esperar al siguiente tic.
     */
    private fun guardarAhora() {
        val c = controller ?: return
        val indice = c.currentMediaItemIndex
        val dentro = c.currentPosition
        alcance.launch {
            GuardadoDeProgreso.guardar(context, indice, dentro, forzar = true)
        }
    }

    /** Duraciones de las pistas del libro abierto, para traducir posiciones. */
    private fun duraciones(): List<Long> = libro?.duraciones ?: emptyList()

    /** Segundo del libro en el que va la escucha, sumando las pistas anteriores. */
    private fun posicionGlobal(c: MediaController): Long =
        PosicionDelLibro.aGlobal(duraciones(), c.currentMediaItemIndex, c.currentPosition)

    /** Lleva la reproducción a un segundo del libro, sea la pista que sea. */
    private fun irA(globalMs: Long) {
        val c = controller ?: return
        val tope = (libro?.duracionMs ?: 0L).coerceAtLeast(0L)
        val punto = PosicionDelLibro.desdeGlobal(duraciones(), globalMs.coerceIn(0L, tope))
        c.seekTo(punto.indice, punto.dentro)
    }

    /* ---------------- Temporizador de sueño ---------------- */

    /*
     * La cuenta atrás la lleva TemporizadorDeSueno, y quien la hace avanzar es
     * el servicio. Aquí solo se pone, se quita y se mira: si el reloj colgara
     * de esta pantalla se congelaría al irse a segundo plano, que es justo
     * cuando el temporizador tiene que hacer su trabajo.
     */
    fun iniciarSueno(minutos: Int) {
        AlmacenLocal.guardarMinutosSueno(context, minutos)
        TemporizadorDeSueno.iniciar(minutos)
        modoSueno = ModoSueno.MINUTOS
        suenoRestanteS = minutos * 60
    }

    fun cancelarSueno() {
        TemporizadorDeSueno.cancelar()
        modoSueno = ModoSueno.NINGUNO
        suenoRestanteS = 0
        controller?.volume = 1f
    }

    /** Refleja en la interfaz lo que el temporizador lleva hecho por su cuenta. */
    fun refrescarSueno() {
        if (TemporizadorDeSueno.activo()) {
            modoSueno = ModoSueno.MINUTOS
            suenoRestanteS = TemporizadorDeSueno.restanteS
        } else if (modoSueno != ModoSueno.NINGUNO) {
            // Ha saltado mientras la pantalla no miraba.
            modoSueno = ModoSueno.NINGUNO
            suenoRestanteS = 0
        }
    }

    fun anotarPausa() {
        ultimaPausaEn = System.currentTimeMillis()
    }

    /** Lo que pinta la barra: siempre en segundos del libro entero. */
    fun refrescarPosicion() {
        val c = controller ?: return
        posicionMs = posicionGlobal(c)
        duracionMs = libro?.duracionMs ?: 0L
    }

    /**
     * Copia a estado de Compose lo que el guardián ha hecho por su cuenta, para
     * que el indicador de la nube siga vivo. La pantalla ya no decide nada
     * sobre el guardado: solo lo mira.
     */
    fun refrescarEstadoSync() {
        if (GuardadoDeProgreso.subiendo) {
            estadoSync = EstadoSync.SUBIENDO
            return
        }
        estadoSync = when (GuardadoDeProgreso.ultimoResultado) {
            GuardadoDeProgreso.Resultado.SUBIDO -> EstadoSync.HECHO
            GuardadoDeProgreso.Resultado.FALLO_AL_SUBIR -> EstadoSync.FALLO
            GuardadoDeProgreso.Resultado.SOLO_LOCAL ->
                if (GuardadoDeProgreso.lecturaRemotaFueFiable()) estadoSync else EstadoSync.FALLO
            GuardadoDeProgreso.Resultado.SIN_SESION -> EstadoSync.INACTIVO
        }
        sincronizadoEn = GuardadoDeProgreso.sincronizadoEn
    }
}

/** Crea el estado y lo mantiene conectado al servicio mientras viva la pantalla. */
@Composable
fun recordarEstadoReproductor(alcance: CoroutineScope): EstadoReproductor {
    val context = LocalContext.current
    val estado = remember { EstadoReproductor(context, alcance) }

    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val futuro = MediaController.Builder(context, token).buildAsync()
        futuro.addListener({ estado.controller = futuro.get() }, ContextCompat.getMainExecutor(context))
        onDispose {
            estado.controller = null
            MediaController.releaseFuture(futuro)
        }
    }

    DisposableEffect(estado.controller) {
        val c = estado.controller
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                estado.sonando = isPlaying
                // Guardar al pausar es cosa del servicio, que también recibe
                // este mismo aviso. Aquí solo se anota para el rebobinado.
                if (!isPlaying) estado.anotarPausa()
            }
        }
        c?.addListener(listener)
        estado.sonando = c?.isPlaying == true
        onDispose { c?.removeListener(listener) }
    }

    /*
     * Reloj de la interfaz: solo pinta.
     *
     * El guardado de la posición y las estadísticas viven en PlaybackService,
     * no aquí. Este `LaunchedEffect` muere en cuanto la pantalla sale de
     * composición —al pulsar atrás, al reclamar memoria el sistema, o si la
     * reproducción arrancó desde Android Auto y la interfaz no llegó a
     * existir— mientras el servicio sigue sonando tan tranquilo. Con el
     * guardado colgando de aquí, una hora de escucha en el coche no dejaba
     * rastro: ni posición, ni nube, ni estadísticas.
     */
    LaunchedEffect(estado.controller) {
        while (true) {
            delay(500)
            val c = estado.controller ?: continue
            estado.refrescarPosicion()
            estado.refrescarEstadoSync()
            estado.refrescarSueno()
        }
    }

    return estado
}

internal suspend fun <T> enFondo(bloque: () -> T): T = withContext(Dispatchers.IO) { bloque() }
