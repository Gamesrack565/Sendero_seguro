package com.escom.sendero_seguro.ui.util

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Formatos de texto que comparten las pantallas.

private val es = Locale("es", "MX")
private val zona: ZoneId get() = ZoneId.systemDefault()

/** 5512345678 → «55 1234 5678». */
fun formatoTelefono(telefono: String): String {
    val d = telefono.filter(Char::isDigit)
    return if (d.length == 10) "${d.take(2)} ${d.substring(2, 6)} ${d.substring(6)}" else telefono
}

fun horaDe(ms: Long, segundos: Boolean = true): String =
    DateTimeFormatter.ofPattern(if (segundos) "HH:mm:ss" else "HH:mm", es).format(Instant.ofEpochMilli(ms).atZone(zona))

fun fechaHoraDe(ms: Long): String =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", es).format(Instant.ofEpochMilli(ms).atZone(zona))

fun fechaDe(ms: Long): String =
    DateTimeFormatter.ofPattern("d MMM yyyy", es).format(Instant.ofEpochMilli(ms).atZone(zona))

fun instanteMs(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

/** «hace 2 h», «ayer», «hace 3 días». */
fun haceCuanto(ms: Long, ahora: Long = System.currentTimeMillis()): String {
    val d = Duration.ofMillis((ahora - ms).coerceAtLeast(0))
    return when {
        d.toMinutes() < 1 -> "hace ${d.seconds} s"
        d.toHours() < 1 -> "hace ${d.toMinutes()} min"
        d.toDays() < 1 -> "hace ${d.toHours()} h"
        d.toDays() < 2 -> "ayer"
        else -> "hace ${d.toDays()} días"
    }
}

/** 650.0 → «650 m»; 1530.0 → «1.5 km». */
fun distanciaTexto(metros: Double): String =
    if (metros < 1000) "${metros.toInt()} m" else String.format(es, "%.1f km", metros / 1000)

fun megas(bytes: Long): String = String.format(es, "%.1f MB", bytes / 1_048_576.0)

/** Etiqueta de categoría del catálogo (FISICO → Físico). */
fun categoriaLegible(categoria: String?): String = when (categoria?.uppercase()) {
    "FISICO" -> "Físico"
    "SOCIAL" -> "Social"
    "DIGITAL" -> "Digital"
    null -> "Otro"
    else -> categoria.lowercase().replaceFirstChar { it.uppercase() }
}
