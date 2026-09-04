package com.lumina.audiolibros.player

import kotlin.math.abs

/**
 * El punto al que se puede volver tras un salto grande.
 *
 * Vive junto al servicio y no en la pantalla porque los saltos no siempre salen
 * de la pantalla: la barra de la notificación, el coche y los botones del manos
 * libres mueven el reproductor igual, y en esos casos la interfaz puede ni
 * existir cuando ocurre el accidente. Con el ofrecimiento guardado aquí, el
 * roce que das con el móvil en el bolsillo sigue teniendo arreglo cuando lo
 * sacas y abres la aplicación.
 *
 * Las ventanas y el umbral son de [SaltoGrande]: aquí solo vive el estado.
 */
object Regreso {

    @Volatile private var puntoMs: Long? = null
    @Volatile private var creadoEn = 0L

    /** 0 mientras la pantalla no lo haya llegado a enseñar. */
    @Volatile private var vistoEn = 0L

    /** Deja apuntado de dónde venía la escucha antes del salto. */
    fun armar(desdeMs: Long) {
        puntoMs = desdeMs
        creadoEn = System.currentTimeMillis()
        vistoEn = 0L
    }

    fun descartar() {
        puntoMs = null
        vistoEn = 0L
    }

    /**
     * El ofrecimiento vivo, o null si no hay o ya caducó.
     *
     * Caduca al preguntar, y no en un reloj aparte, para que la pantalla y el
     * servicio no puedan discrepar sobre si sigue en pie.
     */
    fun punto(): Long? {
        val p = puntoMs ?: return null
        if (SaltoGrande.caducado(creadoEn, vistoEn, System.currentTimeMillis())) {
            descartar()
            return null
        }
        return p
    }

    /** La pantalla acaba de enseñarlo: a partir de aquí corre la ventana corta. */
    fun visto() {
        if (puntoMs != null && vistoEn == 0L) vistoEn = System.currentTimeMillis()
    }

    /** ¿Este salto es la vuelta que se estaba ofreciendo, y no otro accidente? */
    fun esLaVuelta(globalMs: Long): Boolean {
        val p = puntoMs ?: return false
        return abs(globalMs - p) <= SaltoGrande.MARGEN_DE_VUELTA_MS
    }
}
