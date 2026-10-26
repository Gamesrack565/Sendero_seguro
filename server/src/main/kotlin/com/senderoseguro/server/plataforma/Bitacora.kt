package com.senderoseguro.server.plataforma

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.plugins.origin
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import java.util.UUID

/**
 * Escribe en `bitacora` (P13, RNF16). La base calcula el hash que encadena cada registro con el anterior, y no
 * deja cambiar ni borrar registros. Se llama dentro de la misma transacción que la acción que se registra, para
 * que no quede una acción sin su registro ni un registro de algo que no pasó.
 *
 * ```kotlin
 * dataSource.transaccion { conn ->
 *     // ... la acción ...
 *     Bitacora.registrar(conn, "EXPORTAR_ESTADISTICAS", usuario.usuarioId, detalle = buildJsonObject { put("formato", "csv") }, ip = call.ipCliente())
 * }
 * ```
 */
object Bitacora {
    fun registrar(
        conn: Connection,
        /** Qué se hizo, en mayúsculas y con guion bajo, por ejemplo CONSULTAR_HISTORIAL_AJENO. Máximo 60 caracteres. */
        accion: String,
        usuarioId: UUID?,
        entidad: String? = null,
        entidadId: String? = null,
        detalle: JsonObject = JsonObject(emptyMap()),
        ip: String? = null,
    ) {
        require(accion.length in 1..60) { "La acción de la bitácora debe tener de 1 a 60 caracteres" }
        conn.ejecutar(
            """INSERT INTO bitacora (usuario_id, accion, entidad, entidad_id, detalle, ip)
               VALUES (?, ?, ?, ?, ?::jsonb, ?::inet)""",
            usuarioId, accion, entidad?.take(60), entidadId, detalle.toString(), ip?.takeIf(::esIp),
        )
    }

    /** Solo dígitos, puntos y dos puntos (IPv4 o IPv6); otra cosa (un nombre de host) no cabe en `inet`. */
    private fun esIp(texto: String) = texto.isNotEmpty() && texto.length <= 45 &&
        texto.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '.' || it == ':' } &&
        (texto.count { it == '.' } == 3 || texto.contains(':'))
}

/**
 * IP de quien hizo la petición, para la bitácora. En la nube (`servidor.detrasDeProxy`) la conexión llega del
 * balanceador de Azure, que agrega la IP real al final de X-Forwarded-For; las anteriores las pudo escribir el cliente.
 */
fun ApplicationCall.ipCliente(): String? {
    val detrasDeProxy = application.environment.config.propertyOrNull("servidor.detrasDeProxy")?.getString().toBoolean()
    if (detrasDeProxy) request.headers["X-Forwarded-For"]?.let { ipReenviada(it) }?.let { return it }
    return request.origin.remoteAddress
}

/** La última IP de X-Forwarded-For, sin puerto: "1.2.3.4, 5.6.7.8:51000" → "5.6.7.8"; "[2001:db8::1]:443" → "2001:db8::1". */
internal fun ipReenviada(encabezado: String): String? {
    val ultima = encabezado.substringAfterLast(',').trim()
    val ip = when {
        ultima.startsWith("[") -> ultima.substringAfter('[').substringBefore(']')
        ultima.count { it == ':' } == 1 -> ultima.substringBefore(':')
        else -> ultima
    }
    return ip.ifEmpty { null }
}
