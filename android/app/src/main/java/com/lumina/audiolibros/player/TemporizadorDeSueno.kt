package com.lumina.audiolibros.player

/**
 * Temporizador de sueño.
 *
 * Vive fuera de la pantalla por la misma razón que el guardado de la posición:
 * la composición de Compose muere en cuanto la interfaz se va a segundo plano
 * —y con `always_finish_activities` puesto, eso pasa cada vez que sales de la
 * app— mientras el servicio sigue sonando. Colgado de la pantalla, el
 * temporizador se quedaba congelado justo en el escenario para el que existe:
 * te duermes, apagas el móvil, y el libro debería callarse solo.
 *
 * Guarda el **instante** en que debe callarse, no cuántos tics le quedan: así
 * no se desfasa si el reloj se retrasa, y lo que se muestra es siempre el
 * tiempo real que queda.
 */
object TemporizadorDeSueno {

    /** Segundos de desvanecido antes de callarse. */
    const val DESVANECIDO_S = 12

    @Volatile private var finEn: Long? = null

    /** Segundos que quedan, para que la pantalla los pinte. */
    @Volatile var restanteS: Int = 0
        private set

    fun activo(): Boolean = finEn != null

    fun iniciar(minutos: Int, ahora: Long = System.currentTimeMillis()) {
        finEn = ahora + minutos * 60_000L
        restanteS = minutos * 60
    }

    fun cancelar() {
        finEn = null
        restanteS = 0
    }

    /**
     * Qué hay que hacerle al reproductor en este tic.
     *
     * Se devuelve en vez de aplicarse para que la decisión sea pura y se pueda
     * fijar con tests: quién toca el reproductor es el servicio, que es el
     * único que puede hacerlo desde el hilo principal.
     */
    data class Tic(val volumen: Float, val pausar: Boolean)

    /**
     * Avanza el temporizador. Devuelve null si no hay ninguno puesto.
     *
     * El volumen baja linealmente durante los últimos segundos para que el
     * final no sea un corte seco, y se restaura a 1 al pausar: si no, el
     * siguiente libro empezaría en silencio.
     */
    fun tictac(ahora: Long = System.currentTimeMillis()): Tic? {
        val fin = finEn ?: return null
        val restante = ((fin - ahora) / 1000).toInt()
        restanteS = restante.coerceAtLeast(0)
        if (restante <= 0) {
            finEn = null
            restanteS = 0
            return Tic(volumen = 1f, pausar = true)
        }
        val volumen = if (restante <= DESVANECIDO_S) restante / DESVANECIDO_S.toFloat() else 1f
        return Tic(volumen = volumen, pausar = false)
    }
}
