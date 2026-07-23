package com.lumina.audiolibros.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lumina.audiolibros.data.AlmacenLocal
import java.util.Calendar

/** Estadísticas de escucha, equivalentes al panel del escritorio. */
@Composable
fun PantallaEstadisticas(onCerrar: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val datos by produceState(initialValue = emptyMap<String, Int>()) {
        value = AlmacenLocal.estadisticas(context)
    }

    val dias = ultimosSieteDias()
    val hoy = datos[dias.last()] ?: 0
    val semana = dias.sumOf { datos[it] ?: 0 }
    val total = datos.values.sum()
    val racha = calcularRacha(datos)
    val maximo = maxOf(1, dias.maxOf { datos[it] ?: 0 })

    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Tu escucha", style = MaterialTheme.typography.headlineSmall)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metrica("Hoy", formatearHoras(hoy))
            Metrica("7 días", formatearHoras(semana))
            Metrica(if (racha == 1) "día seguido" else "días seguidos", "$racha")
            Metrica("Total", formatearHoras(total))
        }

        // Barras de los últimos siete días.
        Row(
            Modifier.fillMaxWidth().height(120.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            dias.forEachIndexed { indice, dia ->
                val segundos = datos[dia] ?: 0
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height((100 * segundos / maximo).coerceAtLeast(if (segundos > 0) 6 else 2).dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (indice == dias.lastIndex) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                    )
                    Text(
                        etiquetaDia(dia),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        if (total == 0) {
            Text(
                "Todavía no hay datos: dale al play y vuelve por aquí.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        TextButton(onClick = onCerrar) { Text("Volver") }
    }
}

@Composable
private fun Metrica(etiqueta: String, valor: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(valor, style = MaterialTheme.typography.titleMedium)
        Text(etiqueta, style = MaterialTheme.typography.labelSmall)
    }
}

private fun ultimosSieteDias(): List<String> {
    val calendario = Calendar.getInstance()
    calendario.add(Calendar.DAY_OF_YEAR, -6)
    return (0..6).map {
        val dia = AlmacenLocal.diaDeHoy(calendario.time)
        calendario.add(Calendar.DAY_OF_YEAR, 1)
        dia
    }
}

private fun etiquetaDia(dia: String): String =
    listOf("D", "L", "M", "X", "J", "V", "S").let { nombres ->
        runCatching {
            val partes = dia.split("-").map { it.toInt() }
            val c = Calendar.getInstance()
            c.set(partes[0], partes[1] - 1, partes[2])
            nombres[c.get(Calendar.DAY_OF_WEEK) - 1]
        }.getOrDefault("·")
    }

/** Días consecutivos con al menos un minuto; hoy puede estar todavía a cero. */
private fun calcularRacha(datos: Map<String, Int>): Int {
    val calendario = Calendar.getInstance()
    var racha = 0
    if ((datos[AlmacenLocal.diaDeHoy(calendario.time)] ?: 0) >= 60) racha++
    calendario.add(Calendar.DAY_OF_YEAR, -1)
    while ((datos[AlmacenLocal.diaDeHoy(calendario.time)] ?: 0) >= 60) {
        racha++
        calendario.add(Calendar.DAY_OF_YEAR, -1)
    }
    return racha
}

private fun formatearHoras(segundos: Int): String {
    if (segundos <= 0) return "0 min"
    val minutos = segundos / 60
    val horas = minutos / 60
    return if (horas > 0) "${horas} h ${minutos % 60} min" else "$minutos min"
}
