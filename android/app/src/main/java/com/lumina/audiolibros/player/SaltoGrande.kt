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
     * Cuánto se queda el botón en pantalla una vez lo has visto.
     *
     * Suficiente para reaccionar al «¿qué acaba de pasar?», y poco para que no
     * se quede ahí tapando la interfaz el resto de la escucha.
     */
    const val VENTANA_EN_PANTALLA_MS = 12_000L

    /**
     * Cuánto espera un ofrecimiento que todavía no has llegado a ver.
     *
     * El salto no siempre lo das en la pantalla: la barra de la notificación
     * está a un roce de distancia con el móvil en el bolsillo, y ahí entre el
     * accidente y abrir la aplicación para arreglarlo pasan el «¿qué ha sido
     * eso?», sacar el teléfono y desbloquearlo. Con solo los doce segundos de
     * pantalla, el ofrecimiento no llegaba vivo a ese caso ni una vez.
     */
    const val VENTANA_SIN_VER_MS = 5 * 60_000L

    /**
     * Margen para reconocer que un salto **es** la vuelta que se ofrecía.
     *
     * Volver no puede armar un ofrecimiento nuevo: sería proponerte regresar al
     * accidente del que acabas de escapar. Y como al volver la reproducción
     * sigue corriendo, la posición no cae en el milisegundo exacto.
     */
    const val MARGEN_DE_VUELTA_MS = 5_000L

    /** ¿Este salto ha sido lo bastante grande como para ofrecer volver? */
    fun mereceDeshacer(desdeMs: Long, hastaMs: Long): Boolean =
        abs(hastaMs - desdeMs) >= UMBRAL_MS

    /**
     * ¿Se ha pasado el arroz? `vistoEn` en 0 significa que la pantalla aún no
     * ha llegado a enseñarlo, y entonces la ventana que corre es la larga.
     */
    fun caducado(creadoEn: Long, vistoEn: Long, ahora: Long): Boolean =
        if (vistoEn > 0L) ahora - vistoEn > VENTANA_EN_PANTALLA_MS
        else ahora - creadoEn > VENTANA_SIN_VER_MS
}
