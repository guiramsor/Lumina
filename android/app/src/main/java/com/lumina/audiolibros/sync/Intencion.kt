package com.lumina.audiolibros.sync

/**
 * «Esta posición la ha elegido el usuario.»
 *
 * Es la bandera que permite subir una posición **anterior** a la que hay en la
 * nube: si ha movido la barra, saltado 30 segundos, ido a un marcador o
 * reabierto un libro terminado, esa es su posición aunque vaya hacia atrás. El
 * servidor rechaza cualquier retroceso salvo que esta bandera venga puesta
 * (ver `guardar_progreso` en supabase/schema.sql).
 *
 * Y aquí está el motivo de que esto sea una clase con nombre en vez de un
 * booleano suelto: **se consume**. Antes se ponía a true al saltar y solo se
 * bajaba al abrir otro libro, así que un único toque a «+30 s» dejaba todas las
 * subidas de las tres horas siguientes saltándose la comprobación del servidor.
 * Como los botones de salto son los que más se usan, la protección quedaba
 * apagada casi siempre.
 *
 * Una intención vale para **una** subida: la que la lleva a la nube. Lo que
 * venga después es inercia otra vez.
 *
 * El sello existe por una carrera real: si se salta de nuevo mientras la subida
 * anterior está en vuelo, al terminar esa subida no se puede bajar la bandera,
 * porque ya no es la misma intención. El sello distingue una de otra.
 *
 * Contrato compartido con `src/lib/intencion.js` del escritorio.
 */
class Intencion {

    @Volatile private var activa = false
    @Volatile private var sello = 0L

    /** La posición pasa a ser una decisión del usuario. */
    fun marcar() {
        activa = true
        sello++
    }

    /** ¿Puede la próxima subida saltarse la comprobación del servidor? */
    fun activa(): Boolean = activa

    /** Sello con el que sale una subida, para reconocerla al volver. */
    fun sello(): Long = sello

    /**
     * La subida con ese sello ha llegado a la nube: la intención ya está
     * guardada y deja de valer. Si entretanto se ha marcado otra, no se toca.
     */
    fun cumplida(selloDeLaSubida: Long) {
        if (sello == selloDeLaSubida) activa = false
    }

    /** Libro nuevo: se empieza sin intención pendiente. */
    fun reiniciar() {
        activa = false
    }
}
