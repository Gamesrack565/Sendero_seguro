package com.senderoseguro.server.auditoria

import com.senderoseguro.server.dispersion.ConsultaAlertas
import com.senderoseguro.server.plataforma.AUTH_JWT
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.UsuarioToken
import com.senderoseguro.server.plataforma.consultar
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.instante
import com.senderoseguro.server.plataforma.usuarioActual
import com.senderoseguro.server.plataforma.uuid
import com.senderoseguro.server.plataforma.uuidONulo
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@Serializable
data class EventoTimeline(
    val tipo: String,
    val ocurridoEn: String,
    val actor: String? = null,
    val estadoAnterior: String? = null,
    val estadoNuevo: String? = null,
    val detalle: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class LineaDeTiempoJson(val alertaId: String, val eventos: List<EventoTimeline>)

/**
 * `GET /alertas/{id}/timeline` (P13, A-13 y W-03): todo lo que le pasó a una alerta en orden. Une `evento_alerta`,
 * `asignacion`, `escalamiento`, `notificacion` e `intento_cancelacion`.
 *
 * Quién la ve es lo mismo que `GET /alertas/{id}`: el Administrador, el operador cuya unidad tuvo la alerta y quien
 * la emitió. La consola ve todo, con nombres. Quien la emitió ve una versión para la app: sus cambios de estado,
 * la unidad asignada, sus intentos de cancelación y los avisos a su red de apoyo, sin nombres de operadores ni
 * datos internos. Si canceló con el PIN de coacción, su línea termina en CANCELADA en ese momento (RF-13), igual
 * que lo que ve en el detalle, aunque la alerta siga activa en silencio.
 */
class LineaDeTiempo(private val dataSource: DataSource, private val consulta: ConsultaAlertas) {

    fun de(usuario: UsuarioToken, alertaId: UUID): LineaDeTiempoJson = dataSource.connection.use { conn ->
        // Mismas reglas de visibilidad que el detalle: si no la puede ver, 404.
        consulta.detalle(conn, usuario, alertaId)
        val vistaEmisor = usuario.rol !in setOf(Rol.ADMINISTRADOR, Rol.OPERADOR)
        val alerta = conn.consultarUno(
            "SELECT usuario_id, coaccion_detectada, activada_en FROM alerta WHERE id = ?", alertaId,
        ) { Triple(it.uuid("usuario_id"), it.getBoolean("coaccion_detectada"), it.instante("activada_en")!!) }!!
        val (emisorId, coaccion, activadaEn) = alerta

        val eventos = (estados(conn, alertaId, vistaEmisor, emisorId) + asignaciones(conn, alertaId) +
            escalamientos(conn, alertaId, vistaEmisor) + avisos(conn, alertaId, vistaEmisor) +
            cancelaciones(conn, alertaId, vistaEmisor))
            .sortedWith(compareBy<Pair<Instant, EventoTimeline>> { it.first }.thenBy { ORDEN.indexOf(it.second.tipo) })

        val visibles = if (vistaEmisor && coaccion) {
            // Lo que pasó después de la coacción no se le muestra; en su lugar, la cancelación que la app simuló.
            val corte = conn.consultarUno(
                "SELECT min(intentado_en) AS t FROM intento_cancelacion WHERE alerta_id = ? AND es_coaccion", alertaId,
            ) { it.instante("t") } ?: activadaEn
            val antes = eventos.filter { !it.first.isAfter(corte) }
            val ultimoEstado = antes.lastOrNull { it.second.tipo == "CAMBIO_ESTADO" }?.second?.estadoNuevo
            antes + (corte to EventoTimeline("CAMBIO_ESTADO", corte.toString(), estadoAnterior = ultimoEstado, estadoNuevo = "CANCELADA"))
        } else {
            eventos
        }
        LineaDeTiempoJson(alertaId.toString(), visibles.map { it.second })
    }

    private fun estados(conn: Connection, alertaId: UUID, vistaEmisor: Boolean, emisorId: UUID) = conn.consultar(
        """SELECT ev.ocurrido_en, ev.estado_anterior::text AS anterior, ev.estado_nuevo::text AS nuevo, ev.detalle::text AS detalle,
                  ev.actor_id, u.nombre AS actor, u.rol::text AS actor_rol
           FROM evento_alerta ev LEFT JOIN usuario u ON u.id = ev.actor_id
           WHERE ev.alerta_id = ? ORDER BY ev.id""",
        alertaId,
    ) { rs ->
        val detalle = Json.parseToJsonElement(rs.getString("detalle")).jsonObject
        val anterior = rs.getString("anterior")
        val nuevo = rs.getString("nuevo")
        val actorId = rs.uuidONulo("actor_id")
        val actor = when {
            actorId == null -> null
            !vistaEmisor || actorId == emisorId -> rs.getString("actor")
            else -> ROLES[rs.getString("actor_rol")]
        }
        val momento = rs.instante("ocurrido_en")!!
        // Los eventos sin cambio de estado son notas internas (por ejemplo, ENTREGA_DESCARTADA): solo la consola.
        when {
            anterior != nuevo -> momento to EventoTimeline("CAMBIO_ESTADO", momento.toString(), actor, anterior, nuevo,
                if (vistaEmisor) JsonObject(emptyMap()) else detalle)
            vistaEmisor -> null
            (detalle["accion"] as? JsonPrimitive)?.contentOrNull == "ENTREGA_DESCARTADA" ->
                momento to EventoTimeline("NOTIFICACION", momento.toString(), actor, detalle = detalle)
            else -> momento to EventoTimeline("CAMBIO_ESTADO", momento.toString(), actor, anterior, nuevo, detalle)
        }
    }.filterNotNull()

    private fun asignaciones(conn: Connection, alertaId: UUID) = conn.consultar(
        """SELECT s.asignada_en, s.liberada_en, s.rol::text AS rol, s.nivel_escalamiento, s.distancia_m::double precision AS distancia,
                  u.nombre AS unidad, c.nombre AS corporacion
           FROM asignacion s JOIN unidad_atencion u ON u.id = s.unidad_id JOIN corporacion c ON c.id = u.corporacion_id
           WHERE s.alerta_id = ? ORDER BY s.id""",
        alertaId,
    ) { rs ->
        val momento = rs.instante("asignada_en")!!
        momento to EventoTimeline(
            "ASIGNACION", momento.toString(),
            detalle = buildJsonObject {
                put("unidad", rs.getString("unidad"))
                put("corporacion", rs.getString("corporacion"))
                put("rol", rs.getString("rol"))
                put("nivelEscalamiento", rs.getInt("nivel_escalamiento"))
                rs.getDouble("distancia").takeUnless { rs.wasNull() }?.let { put("distanciaM", it) }
                rs.instante("liberada_en")?.let { put("liberadaEn", it.toString()) }
            },
        )
    }

    private fun escalamientos(conn: Connection, alertaId: UUID, vistaEmisor: Boolean) = if (vistaEmisor) emptyList() else conn.consultar(
        """SELECT e.escalada_en, e.nivel, e.destino_tipo::text AS destino, e.motivo, c.nombre AS corporacion, o.nombre AS operador
           FROM escalamiento e LEFT JOIN corporacion c ON c.id = e.corporacion_id LEFT JOIN usuario o ON o.id = e.operador_id
           WHERE e.alerta_id = ? ORDER BY e.nivel""",
        alertaId,
    ) { rs ->
        val momento = rs.instante("escalada_en")!!
        momento to EventoTimeline(
            "ESCALAMIENTO", momento.toString(),
            detalle = buildJsonObject {
                put("nivel", rs.getInt("nivel"))
                put("destino", rs.getString("destino"))
                put("motivo", rs.getString("motivo"))
                rs.getString("corporacion")?.let { put("corporacion", it) }
                rs.getString("operador")?.let { put("operador", it) }
            },
        )
    }

    /** Los avisos. Quien emitió la alerta solo ve los de su red de apoyo, sin a quién se mandaron. */
    private fun avisos(conn: Connection, alertaId: UUID, vistaEmisor: Boolean) = conn.consultar(
        """SELECT n.creada_en, n.tipo::text AS tipo, n.canal::text AS canal, n.estado::text AS estado, n.reintentos,
                  n.enviada_en, n.entregada_en, n.ultimo_error, coalesce(u.nombre, c.nombre) AS destinatario
           FROM notificacion n LEFT JOIN usuario u ON u.id = n.usuario_id LEFT JOIN contacto_confianza c ON c.id = n.contacto_id
           WHERE n.alerta_id = ? ${if (vistaEmisor) "AND n.tipo = 'RED_APOYO'" else ""} ORDER BY n.id""",
        alertaId,
    ) { rs ->
        val momento = rs.instante("creada_en")!!
        momento to EventoTimeline(
            "NOTIFICACION", momento.toString(),
            detalle = buildJsonObject {
                put("tipo", rs.getString("tipo"))
                put("canal", rs.getString("canal"))
                put("estado", rs.getString("estado"))
                rs.instante("entregada_en")?.let { put("entregadaEn", it.toString()) }
                if (!vistaEmisor) {
                    put("destinatario", rs.getString("destinatario"))
                    put("reintentos", rs.getInt("reintentos"))
                    rs.instante("enviada_en")?.let { put("enviadaEn", it.toString()) }
                    rs.getString("ultimo_error")?.let { put("ultimoError", it) }
                }
            },
        )
    }

    private fun cancelaciones(conn: Connection, alertaId: UUID, vistaEmisor: Boolean) = conn.consultar(
        """SELECT i.intentado_en, i.motivo::text AS motivo, i.metodo::text AS metodo, i.exitoso, i.es_coaccion, u.nombre
           FROM intento_cancelacion i JOIN usuario u ON u.id = i.usuario_id
           WHERE i.alerta_id = ? ORDER BY i.id""",
        alertaId,
    ) { rs ->
        val momento = rs.instante("intentado_en")!!
        val coaccion = rs.getBoolean("es_coaccion")
        momento to EventoTimeline(
            "INTENTO_CANCELACION", momento.toString(), rs.getString("nombre"),
            detalle = buildJsonObject {
                rs.getString("motivo")?.let { put("motivo", it) }
                put("metodo", rs.getString("metodo"))
                // Con coacción, la app le mostró a la persona que sí se canceló.
                put("exitoso", rs.getBoolean("exitoso") || (vistaEmisor && coaccion))
                if (!vistaEmisor) put("esCoaccion", coaccion)
            },
        )
    }

    private companion object {
        /** Para eventos del mismo instante: primero el cambio de estado, luego lo que provocó. */
        val ORDEN = listOf("CAMBIO_ESTADO", "ASIGNACION", "ESCALAMIENTO", "NOTIFICACION", "INTENTO_CANCELACION")
        val ROLES = mapOf("OPERADOR" to "Operador", "ADMINISTRADOR" to "Administrador", "COORDINADOR" to "Coordinador", "INTEGRANTE" to "Integrante")
    }
}

fun Route.rutasLineaDeTiempo(lineaDeTiempo: LineaDeTiempo) {
    authenticate(AUTH_JWT) {
        get("/alertas/{id}/timeline") {
            val id = runCatching { UUID.fromString(call.parameters["id"]) }
                .getOrElse { throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "El id de la alerta no es un UUID") }
            call.respond(lineaDeTiempo.de(call.usuarioActual(), id))
        }
    }
}
