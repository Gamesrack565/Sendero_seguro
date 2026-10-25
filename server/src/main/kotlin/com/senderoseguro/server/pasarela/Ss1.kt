package com.senderoseguro.server.pasarela

import java.security.MessageDigest
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToLong

/** Lo que trae un SMS de contingencia `SS1|…` ya decodificado. */
data class DatosSs1(
    /** Primeros 8 caracteres hex del UUID que la app le dio a la alerta, en minúsculas. */
    val idCorto: String,
    val tipoIncidenteId: Int,
    val latitud: Double,
    val longitud: Double,
    val precisionM: Int,
    /** Hora en que se activó la alerta, según el reloj del teléfono. */
    val activadaEn: Instant,
)

/**
 * Formato `SS1` de 46 caracteres (contrato de la API, sección 4). Después de `SS1|` todos los campos son de
 * ancho fijo: id corto (8), tipo en base 36 (2), latitud (8), longitud (9), precisión (3), hora en base 36 (6)
 * y verificación (6, los primeros caracteres hex de SHA-256 sobre los 40 anteriores).
 */
object Ss1 {
    const val LARGO = 46

    /** Decodifica [texto]. Regresa los datos, o el motivo por el que no es un `SS1` válido. */
    fun decodificar(texto: String): Result<DatosSs1> = runCatching {
        val t = texto.trim()
        require(t.length == LARGO && t.startsWith("SS1|")) { "No es un SS1 de $LARGO caracteres" }
        require(t.substring(40).equals(verificacion(t.substring(0, 40)), ignoreCase = true)) { "La verificación no coincide" }
        val idCorto = t.substring(4, 12).lowercase()
        require(idCorto.all { it in '0'..'9' || it in 'a'..'f' }) { "El id corto no es hex" }
        val tipo = t.substring(12, 14).toIntOrNull(36)?.takeIf { it > 0 } ?: error("Tipo inválido")
        val latitud = coordenada(t.substring(14, 22)).also { require(it in -90.0..90.0) { "Latitud fuera de rango" } }
        val longitud = coordenada(t.substring(22, 31)).also { require(it in -180.0..180.0) { "Longitud fuera de rango" } }
        val precision = t.substring(31, 34).takeIf { s -> s.all { it.isDigit() } }?.toInt() ?: error("Precisión inválida")
        val hora = t.substring(34, 40).toLongOrNull(36) ?: error("Hora inválida")
        DatosSs1(idCorto, tipo, latitud, longitud, precision, Instant.ofEpochSecond(hora))
    }

    /** Arma el `SS1` como lo hace la app. Lo usan las pruebas y la ruta de desarrollo. */
    fun codificar(d: DatosSs1): String {
        require(d.idCorto.length == 8) { "El id corto lleva 8 caracteres" }
        fun signo(v: Double, digitos: Int) = (if (v < 0) "-" else "+") + abs((v * 100_000).roundToLong()).toString().padStart(digitos, '0')
        val cuerpo = "SS1|" + d.idCorto.lowercase() +
            d.tipoIncidenteId.toString(36).uppercase().padStart(2, '0') +
            signo(d.latitud, 7) + signo(d.longitud, 8) +
            d.precisionM.coerceIn(0, 999).toString().padStart(3, '0') +
            d.activadaEn.epochSecond.toString(36).uppercase().padStart(6, '0')
        return cuerpo + verificacion(cuerpo)
    }

    private fun verificacion(primeros40: String): String =
        MessageDigest.getInstance("SHA-256").digest(primeros40.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it) }.take(6)

    /** Signo y dígitos, en grados × 100 000. */
    private fun coordenada(s: String): Double {
        require(s[0] == '+' || s[0] == '-') { "Coordenada sin signo" }
        val n = s.substring(1).takeIf { d -> d.all { it.isDigit() } }?.toLong() ?: error("Coordenada inválida")
        return (if (s[0] == '-') -n else n) / 100_000.0
    }
}
