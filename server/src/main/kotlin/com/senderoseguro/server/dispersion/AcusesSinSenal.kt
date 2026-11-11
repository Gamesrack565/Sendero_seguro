package com.senderoseguro.server.dispersion

import com.senderoseguro.server.plataforma.Bitacora
import com.senderoseguro.server.plataforma.consultar
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.uuid
import com.senderoseguro.server.sincronizacion.ContextoSync
import com.senderoseguro.server.sincronizacion.ElementoSync
import com.senderoseguro.server.sincronizacion.ManejadorSync
import com.senderoseguro.server.sincronizacion.ResultadoManejo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.sql.Connection
import java.time.Instant
import java.util.UUID

private val HEX8 = Regex("^[0-9a-f]{8}$")

/**
 * Acuse del operador sin señal (A-14, CU-24): el teléfono de un operador recibió la alerta por la malla, el operador
 * tocó «Acusar y acudir» y el acuse se quedó en su cola. Llega en `/sync/lote` como tipo ACUSE con:
 *
 * ```json
 * {"idCortoAlerta": "1a2b3c4d", "idCortoUsuario": "9f8e7d6c", "activadaEn": "...", "acusadaEn": "...", "alertaId": "opcional"}
 * ```
 *
 * Los ids cortos son los del paquete de la malla (los primeros 8 hex de la alerta y los últimos 8 de la persona).
 * - Si la unidad del operador es la asignada, es un acuse normal.
 * - Si la alerta todavía no tiene acuse, la unidad del operador la toma: se libera la asignación anterior y la
 *   unidad cercana queda como principal (llegó primero a la emisora).
 * - Si otra unidad ya la acusó, queda registrado como apoyo.
 * En todos los casos `acusada_en` es la hora en que el operador acusó en el teléfono, no la de la sincronización,
 * para que la meta de 2 minutos (RNF4) se mida bien. Si la alerta todavía no llegó al servidor, se pide reintentar.
 */
class AcusesSinSenal {

    val manejadorSync = ManejadorSync { conn, contexto, elemento -> procesar(conn, contexto, elemento) }

    private fun procesar(conn: Connection, contexto: ContextoSync, e: ElementoSync): ResultadoManejo {
        val d = e.datos
        val idCorto = d.texto("idCortoAlerta")?.lowercase()
        val persona = d.texto("idCortoUsuario")?.lowercase()
        val activadaEn = d.texto("activadaEn")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val acusadaEnTelefono = d.texto("acusadaEn")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: e.creadoEn
        val alertaCompleta = d.texto("alertaId")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (idCorto == null || !HEX8.matches(idCorto) || persona == null || !HEX8.matches(persona) || activadaEn == null) {
            return ResultadoManejo.Rechazado("VALIDACION", "Faltan idCortoAlerta, idCortoUsuario (8 hex) o activadaEn")
        }

        val unidad = conn.consultarUno(
            """SELECT o.unidad_id FROM operador o JOIN usuario u ON u.id = o.usuario_id
               WHERE o.usuario_id = ? AND o.activo AND u.rol = 'OPERADOR'""",
            contexto.usuarioId,
        ) { it.uuid("unidad_id") } ?: return ResultadoManejo.Rechazado("SIN_PERMISO", "Solo un operador con unidad puede acusar")

        val emisor = conn.consultar(
            "SELECT id FROM usuario WHERE right(replace(id::text, '-', ''), 8) = ? LIMIT 2", persona,
        ) { it.uuid("id") }.singleOrNull()
            ?: return ResultadoManejo.Rechazado("NO_ENCONTRADO", "No se reconoce a la persona del paquete")
        val alertaId = AlertasRecibidas.buscar(conn, emisor, idCorto, activadaEn, alertaCompleta?.takeIf { it.toString().startsWith(idCorto) })
            ?: return ResultadoManejo.Rechazado("NO_ENCONTRADO", "La alerta todavía no llega al servidor", temporal = true)

        val alerta = conn.consultarUno(
            """SELECT estado::text AS estado, usuario_id, coaccion_detectada, activada_en, folio, nivel_escalamiento
               FROM alerta WHERE id = ? FOR UPDATE""",
            alertaId,
        ) { rs ->
            Alerta(rs.getString("estado"), rs.uuid("usuario_id"), rs.getBoolean("coaccion_detectada"),
                rs.getTimestamp("activada_en").toInstant(), rs.getString("folio"), rs.getInt("nivel_escalamiento"))
        }!!
        if (alerta.estado in setOf("CERRADA", "CANCELADA")) {
            return ResultadoManejo.Rechazado("TRANSICION_INVALIDA", "La alerta ya terminó (${alerta.estado})")
        }
        if (yaAcuso(conn, alertaId, unidad)) return ResultadoManejo.Duplicado(alerta.folio)

        val acusadaEn = acusadaEnTelefono.coerceAtMost(Instant.now()).coerceAtLeast(alerta.activadaEn)
        val asignadaAMiUnidad = conn.consultarUno(
            "SELECT 1 FROM asignacion WHERE alerta_id = ? AND unidad_id = ? AND liberada_en IS NULL AND rol = 'PRINCIPAL'", alertaId, unidad,
        ) { true } == true
        val sinAcuse = alerta.estado in setOf("ENVIADA", "ASIGNADA", "ESCALADA")
        val unidadLibre = conn.consultarUno("SELECT estado = 'DISPONIBLE' AND activa FROM unidad_atencion WHERE id = ? FOR UPDATE", unidad) {
            it.getBoolean(1)
        } == true

        val accion = when {
            sinAcuse && asignadaAMiUnidad -> "ACUSE"
            sinAcuse && unidadLibre -> {
                // La unidad cercana toma la alerta: la anterior queda libre para otra emergencia.
                Canalizacion.liberarAsignaciones(conn, alertaId)
                val reabierta = conn.ejecutar(
                    "UPDATE asignacion SET liberada_en = NULL WHERE alerta_id = ? AND unidad_id = ? AND nivel_escalamiento = ?",
                    alertaId, unidad, alerta.nivel.toShort(),
                )
                if (reabierta > 0) conn.ejecutar("UPDATE unidad_atencion SET estado = 'ASIGNADA' WHERE id = ?", unidad)
                else Canalizacion.asignar(conn, alertaId, Canalizacion.Unidad(unidad, "", null), alerta.nivel)
                "ACUSE"
            }
            else -> "ACUSE_APOYO"
        }
        val detalle = buildJsonObject {
            put("accion", accion)
            put("unidadId", unidad.toString())
            put("canal", "MALLA")
            put("acusadaEnTelefono", acusadaEn.toString())
        }
        if (accion == "ACUSE" && sinAcuse) {
            conn.ejecutar(
                """UPDATE alerta SET estado = 'ACUSADA', acusada_en = COALESCE(acusada_en, ?), acusada_por = COALESCE(acusada_por, ?)
                   WHERE id = ?""",
                acusadaEn, contexto.usuarioId, alertaId,
            )
            Canalizacion.registrarEvento(conn, alertaId, alerta.estado, "ACUSADA", detalle, contexto.usuarioId)
            if (!alerta.coaccion) Canalizacion.avisar(conn, alertaId, alerta.usuarioId, "ACUSE")
        } else {
            Canalizacion.registrarEvento(conn, alertaId, alerta.estado, alerta.estado, detalle, contexto.usuarioId)
        }
        Bitacora.registrar(conn, "ACUSE_SIN_SENAL", contexto.usuarioId, "alerta", alertaId.toString(), detalle)
        return ResultadoManejo.Aceptado(alerta.folio)
    }

    private fun yaAcuso(conn: Connection, alertaId: UUID, unidad: UUID): Boolean = conn.consultarUno(
        """SELECT 1 FROM evento_alerta WHERE alerta_id = ? AND detalle->>'accion' IN ('ACUSE', 'ACUSE_APOYO')
             AND detalle->>'unidadId' = ?""",
        alertaId, unidad.toString(),
    ) { true } ?: false

    private fun JsonObject.texto(clave: String): String? = this[clave]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }

    private class Alerta(val estado: String, val usuarioId: UUID, val coaccion: Boolean, val activadaEn: Instant, val folio: String, val nivel: Int)
}
