package com.lumina.audiolibros.player

import kotlin.math.abs

/**
 * Cuándo un salto merece un botón para deshacerlo.
 *
 * La barra de un audiolibro es traicionera: en un libro de sesenta horas, cada
 * píxel son casi cuatro minutos, así que un roce sin querer te manda a otro
 * capítulo. Y volver es tedioso, porque ya no sabes dónde estabas.
 *
 * El umbral está por encima de los botones de salto (15 s y 30 s) a propósito:
 * esos son deliberados y no deben ensuciar la pantalla con un botón de
 * deshacer cada vez que los usas. Cualquier cosa por encima de un minuto, en
 * cambio, o la has pedido tú moviendo la barra —y entonces el botón te sobra
 * pero no te molesta— o ha sido un accidente, y entonces te salva.
 */
object SaltoGrande {

    /** A partir de aquí se ofrece volver. Por encima de los botones de salto. */
    const val UMBRAL_MS = 60_000L

    /**
     * Cuánto se queda el botón en pantalla.
     *
     * Suficiente para reaccionar al «¿qué acaba de pasar?», y poco para que no
     * se quede ahí tapando la interfaz el resto de la escucha.
     */
    const val VENTANA_MS = 12_000L

    /** ¿Este salto ha sido lo bastante grande como para ofrecer volver? */
    fun mereceDeshacer(desdeMs: Long, hastaMs: Long): Boolean =
        abs(hastaMs - desdeMs) >= UMBRAL_MS
}
