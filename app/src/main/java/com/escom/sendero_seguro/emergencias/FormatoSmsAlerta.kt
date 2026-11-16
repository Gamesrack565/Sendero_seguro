package com.escom.sendero_seguro.emergencias

import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToLong

/** Codifica el SMS SS1 que recibe la pasarela del servidor. */
object FormatoSmsAlerta {
    const val LONGITUD = 46

    fun codificar(alerta: AlertaEmergencia): String {
        val idCorto = FormatoPaqueteMalla.idCortoAlerta(alerta.idAlerta)
            ?: throw IllegalArgumentException("El id de alerta debe ser UUID")
        val tipoId = alerta.tipoIncidenteId.takeIf { it > 0 } ?: 1
        require(tipoId <= 1295) { "El tipo de incidente no cabe en el formato SS1" }

        fun coordenada(valor: Double, digitos: Int): String {
            require(valor.isFinite()) { "La coordenada debe ser finita" }
            val magnitud = abs((valor * 100_000).roundToLong()).toString()
            require(magnitud.length <= digitos) { "La coordenada está fuera de rango" }
            return (if (valor < 0) "-" else "+") + magnitud.padStart(digitos, '0')
        }

        val cuerpo = buildString {
            append("SS1|")
            append(idCorto)
            append(tipoId.toString(36).uppercase().padStart(2, '0'))
            append(coordenada(alerta.latitudInicial, 7))
            append(coordenada(alerta.longitudInicial, 8))
            append(alerta.precisionMetros.toInt().coerceIn(0, 999).toString().padStart(3, '0'))
            append((alerta.timestampInicio / 1000L).toString(36).uppercase().padStart(6, '0'))
        }
        require(cuerpo.length == 40) { "Los datos no caben en el formato SS1" }

        val verificacion = MessageDigest.getInstance("SHA-256")
            .digest(cuerpo.toByteArray(Charsets.US_ASCII))
            .take(3)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return (cuerpo + verificacion).also {
            check(it.length == LONGITUD)
        }
    }
}
