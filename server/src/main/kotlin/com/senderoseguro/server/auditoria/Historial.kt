package com.senderoseguro.server.auditoria

import com.senderoseguro.server.plataforma.AUTH_JWT
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.Bitacora
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.UsuarioToken
import com.senderoseguro.server.plataforma.consultar
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.exigirRol
import com.senderoseguro.server.plataforma.instante
import com.senderoseguro.server.plataforma.ipCliente
import com.senderoseguro.server.plataforma.transaccion
import com.senderoseguro.server.plataforma.usuarioActual
import com.senderoseguro.server.plataforma.uuid
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/** `referencia` sale aunque sea null (trayectos), como pide el contrato. */
@Serializable
data class ElementoHistorial(
    val tipo: String,
    val id: String,
    val referencia: JsonElement,
    val estado: String,
    val ocurridoEn: String,
)

@Serializable
data class PaginaHistorial(val elementos: List<ElementoHistorial>, val siguienteCursor: JsonElement)

data class FiltrosHistorial(
    val tipo: String? = null,
    val desde: Instant? = null,
    val hasta: Instant? = null,
    val limite: Int = 50,
    /** Último elemento de la página anterior: se sigue con los más viejos. */
    val despuesDe: Pair<Instant, UUID>? = null,
)

/**
 * Historial de una persona (P13): sus alertas, sus reportes con nombre y sus trayectos, del más nuevo al más viejo.
 * Lee `v_historial_usuario`, que ya deja fuera los reportes anónimos.
 *
 * - [propio]: lo que ve cada quien en su app. Una alerta cancelada con el PIN de coacción sale como CANCELADA,
 *   igual que en el detalle y la línea de tiempo (RF-13).
 * - [ajeno]: solo el Administrador, con una justificación. La consulta queda en la bitácora como
 *   CONSULTAR_HISTORIAL_AJENO en la misma transacción, así que no hay consulta sin registro.
 */
class Historial(private val dataSource: DataSource) {

    fun propio(usuario: UsuarioToken, filtros: FiltrosHistorial): PaginaHistorial =
        dataSource.connection.use { conn -> pagina(conn, usuario.usuarioId, filtros, vistaPropia = true) }

    fun ajeno(admin: UsuarioToken, usuarioId: UUID, justificacion: String, filtros: FiltrosHistorial, ip: String?): PaginaHistorial {
        if (admin.rol != Rol.ADMINISTRADOR) throw ApiException(HttpStatusCode.Forbidden, "SIN_PERMISO", "Tu rol no puede hacer esto")
        val motivo = justificacion.trim()
        if (motivo.length !in 10..500) {
            throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "X-Justificacion debe tener de 10 a 500 caracteres")
        }
        return dataSource.transaccion { conn ->
            conn.consultarUno("SELECT 1 FROM usuario WHERE id = ?", usuarioId) { true }
                ?: throw ApiException(HttpStatusCode.NotFound, "NO_ENCONTRADO", "No existe esa persona")
            Bitacora.registrar(
                conn, "CONSULTAR_HISTORIAL_AJENO", admin.usuarioId, entidad = "usuario", entidadId = usuarioId.toString(),
                detalle = buildJsonObject {
                    put("justificacion", motivo)
                    filtros.tipo?.let { put("tipo", it) }
                    filtros.desde?.let { put("desde", it.toString()) }
                    filtros.hasta?.let { put("hasta", it.toString()) }
                    put("siguientePagina", filtros.despuesDe != null)
                },
                ip = ip,
            )
            pagina(conn, usuarioId, filtros, vistaPropia = false)
        }
    }

    private fun pagina(conn: Connection, usuarioId: UUID, f: FiltrosHistorial, vistaPropia: Boolean): PaginaHistorial {
        val condiciones = mutableListOf("h.usuario_id = ?")
        val parametros = mutableListOf<Any?>(usuarioId)
        fun filtro(sql: String, vararg valores: Any?) {
            if (valores.all { it != null }) { condiciones += sql; parametros.addAll(valores) }
        }
        filtro("h.tipo = ?", f.tipo)
        filtro("h.ocurrido_en >= ?::timestamptz", f.desde?.toString())
        filtro("h.ocurrido_en < ?::timestamptz", f.hasta?.toString())
        filtro("(h.ocurrido_en, h.id) < (?::timestamptz, ?)", f.despuesDe?.first?.toString(), f.despuesDe?.second)
        val filas = conn.consultar(
            """SELECT h.tipo, h.id, h.referencia, h.estado, h.ocurrido_en, coalesce(a.coaccion_detectada, false) AS coaccion
               FROM v_historial_usuario h
               LEFT JOIN alerta a ON h.tipo = 'ALERTA' AND a.id = h.id
               WHERE ${condiciones.joinToString(" AND ")}
               ORDER BY h.ocurrido_en DESC, h.id DESC LIMIT ?""",
            *parametros.toTypedArray(), f.limite + 1,
        ) { rs ->
            val ocurrido = rs.instante("ocurrido_en")!!
            val id = rs.uuid("id")
            (ocurrido to id) to ElementoHistorial(
                tipo = rs.getString("tipo"),
                id = id.toString(),
                referencia = rs.getString("referencia")?.let(::JsonPrimitive) ?: JsonNull,
                estado = if (vistaPropia && rs.getBoolean("coaccion")) "CANCELADA" else rs.getString("estado"),
                ocurridoEn = ocurrido.toString(),
            )
        }
        val pagina = filas.take(f.limite)
        val siguiente = if (filas.size > f.limite) JsonPrimitive(escribirCursor(pagina.last().first)) else JsonNull
        return PaginaHistorial(pagina.map { it.second }, siguiente)
    }
}

fun Route.rutasHistorial(historial: Historial) {
    authenticate(AUTH_JWT) {
        get("/historial") {
            call.respond(historial.propio(call.usuarioActual(), call.filtrosHistorial()))
        }
        get("/usuarios/{id}/historial") {
            val admin = call.exigirRol(Rol.ADMINISTRADOR)
            val id = runCatching { UUID.fromString(call.parameters["id"]) }
                .getOrElse { throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "El id de la persona no es un UUID") }
            val justificacion = call.request.headers["X-Justificacion"]
                ?: throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "Falta el encabezado X-Justificacion con el motivo de la consulta")
            call.respond(historial.ajeno(admin, id, justificacion, call.filtrosHistorial(), call.ipCliente()))
        }
    }
}

private val TIPOS = setOf("ALERTA", "REPORTE", "TRAYECTO")

private fun ApplicationCall.filtrosHistorial(): FiltrosHistorial {
    val q = request.queryParameters
    fun invalido(mensaje: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", mensaje)
    fun fecha(nombre: String) = q[nombre]?.let {
        runCatching { Instant.parse(it) }.getOrElse { invalido("$nombre debe ser una fecha ISO 8601, por ejemplo 2026-10-25T00:00:00Z") }
    }
    val tipo = q["tipo"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
    if (tipo != null && tipo !in TIPOS) invalido("tipo debe ser uno de: ${TIPOS.joinToString()}")
    val limite = q["limite"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..200 } ?: invalido("limite debe estar entre 1 y 200") } ?: 50
    return FiltrosHistorial(tipo, fecha("desde"), fecha("hasta"), limite, q["cursor"]?.let(::leerCursor))
}

private fun escribirCursor(ultimo: Pair<Instant, UUID>) =
    Base64.getUrlEncoder().withoutPadding().encodeToString("h:${ultimo.first}|${ultimo.second}".toByteArray())

private fun leerCursor(c: String): Pair<Instant, UUID> = runCatching {
    val (instante, id) = String(Base64.getUrlDecoder().decode(c)).removePrefix("h:").split("|")
    Instant.parse(instante) to UUID.fromString(id)
}.getOrElse { throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "El cursor no es válido") }
