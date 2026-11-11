package com.escom.sendero_seguro.emergencias

import java.nio.ByteBuffer

/**
 * P5.1 (RF-14, A-01): bytes del paquete que viaja en el anuncio BLE de la malla.
 *
 * Un anuncio BLE "legacy" deja 24 bytes para los datos del fabricante, así que el paquete lleva ids cortos:
 *
 * | Bytes | Campo |
 * |---|---|
 * | 0-1 | 'S' 'S' (marca de Sendero Seguro) |
 * | 2-5 | id corto de la alerta: primeros 8 hex de su UUID (el mismo que lleva el SMS) |
 * | 6-9 | latitud (float) |
 * | 10-13 | longitud (float) |
 * | 14 | batería (%) |
 * | 15 | TTL (nace en 5); el bit alto marca un acuse del operador |
 * | 16-19 | id corto de la persona: últimos 8 hex del UUID de su cuenta |
 * | 20-23 | hora de activación (segundos desde 1970) |
 *
 * Los saltos no viajan: son 5 - TTL. Un paquete de 16 o 17 bytes es del formato anterior y se sigue leyendo.
 */
object FormatoPaqueteMalla {
    const val TAMANO = 24
    const val TTL_INICIAL = 5
    private const val MARCA: Byte = 0x53 // 'S'
    private const val BIT_ACUSE = 0x80
    private val HEX8 = Regex("^[0-9a-f]{8}$")

    /** Primeros 8 hex del id de la alerta, o null si no es un UUID ni un id corto. */
    fun idCortoAlerta(alertaId: String): String? =
        alertaId.replace("-", "").lowercase().take(8).takeIf { HEX8.matches(it) }

    /** Últimos 8 hex del id de la cuenta, o null si no es un UUID ni un id corto (por ejemplo, sin sesión). */
    fun idCortoUsuario(usuarioId: String): String? =
        usuarioId.replace("-", "").lowercase().takeLast(8).takeIf { HEX8.matches(it) }

    fun codificar(p: PaqueteMalla): ByteArray {
        val alerta = idCortoAlerta(p.alertaId)?.toLong(16)?.toInt()
            ?: p.packetId.filter { it.isDigit() }.take(9).toIntOrNull()
            ?: p.packetId.hashCode()
        val persona = idCortoUsuario(p.emisorId)?.toLong(16)?.toInt() ?: 0
        val ttl = p.ttl.coerceIn(0, 0x7F) or (if (p.esAcuseOperador) BIT_ACUSE else 0)
        return ByteBuffer.allocate(TAMANO)
            .put(MARCA).put(MARCA)
            .putInt(alerta)
            .putFloat(p.latitud.toFloat())
            .putFloat(p.longitud.toFloat())
            .put(p.nivelBateria.coerceIn(0, 100).toByte())
            .put(ttl.toByte())
            .putInt(persona)
            .putInt((p.timestamp / 1000L).toInt())
            .array()
    }

    /** El paquete que trae un anuncio, o null si no es de Sendero Seguro. */
    fun decodificar(bytes: ByteArray): PaqueteMalla? {
        if (bytes.size < 16 || bytes[0] != MARCA || bytes[1] != MARCA) return null
        val b = ByteBuffer.wrap(bytes)
        b.position(2)
        val alerta = b.int
        val lat = b.float.toDouble()
        val lon = b.float.toDouble()
        val bateria = b.get().toInt() and 0xFF
        val ttlByte = b.get().toInt() and 0xFF
        if (bytes.size < TAMANO) {
            // Formato anterior (16 o 17 bytes): número de 4 cifras y sin persona ni hora.
            val saltos = if (bytes.size >= 17) b.get().toInt() else (TTL_INICIAL - ttlByte).coerceAtLeast(0)
            return PaqueteMalla(
                packetId = "SOS-$alerta", emisorId = "NODO_CERCANO", latitud = lat, longitud = lon,
                nivelBateria = bateria, ttl = ttlByte, saltosRecorridos = saltos,
            )
        }
        val persona = b.int
        val segundos = b.int.toLong() and 0xFFFFFFFFL
        val idAlerta = "%08x".format(alerta)
        val acuse = ttlByte and BIT_ACUSE != 0
        val ttl = ttlByte and 0x7F
        return PaqueteMalla(
            packetId = (if (acuse) "ACK-" else "SOS-") + idAlerta,
            alertaId = idAlerta,
            emisorId = "%08x".format(persona),
            latitud = lat,
            longitud = lon,
            nivelBateria = bateria,
            ttl = ttl,
            saltosRecorridos = (TTL_INICIAL - ttl).coerceAtLeast(0),
            esAcuseOperador = acuse,
            timestamp = segundos * 1000L,
        )
    }

    /**
     * Cuerpo de `POST /malla/relevos` para el servidor (Módulo 4), o null si el paquete no se puede entregar
     * (un acuse, o un paquete sin id corto de alerta o de persona).
     */
    fun relevoJson(p: PaqueteMalla): String? {
        if (p.esAcuseOperador) return null
        val alerta = idCortoAlerta(p.alertaId) ?: return null
        val persona = idCortoUsuario(p.emisorId) ?: return null
        val activadaEn = java.time.Instant.ofEpochMilli(p.timestamp).toString()
        return """{"idCortoAlerta":"$alerta","idCortoUsuario":"$persona","latitud":${p.latitud},""" +
            """"longitud":${p.longitud},"nivelBateria":${p.nivelBateria.coerceIn(0, 100)},"ttl":${p.ttl},""" +
            """"saltosMalla":${p.saltosRecorridos},"activadaEn":"$activadaEn"}"""
    }
}
