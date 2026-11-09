package com.senderoseguro.server.auditoria

import com.senderoseguro.server.plataforma.AUTH_JWT
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.consultar
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.exigirRol
import com.senderoseguro.server.plataforma.uuidONulo
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.sql.Connection
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

@Serializable
data class RegistroBitacora(
    val id: Long,
    val ocurridaEn: String,
    val usuarioId: String? = null,
    val accion: String,
    val entidad: String? = null,
    val entidadId: String? = null,
    val detalle: JsonObject,
    val ip: String? = null,
    val hash: String,
)

/** `siguienteCursor` y `primerIdAlterado` salen aunque sean null, como pide el contrato (el servidor omite los null). */
@Serializable
data class PaginaBitacora(val elementos: List<RegistroBitacora>, val siguienteCursor: JsonElement)

@Serializable
data class VerificacionBitacora(
    val integra: Boolean,
    val registrosRevisados: Long,
    val primerIdAlterado: JsonElement,
    val verificadaEn: String,
)

/**
 * Consulta de la bitácora (P13), solo para Administradores:
 * - `GET /bitacora`: registros del más nuevo al más viejo, con filtros y cursor.
 * - `GET /bitacora/verificar`: revisa con `fn_verificar_bitacora()` que nadie haya alterado la cadena de hashes.
 */
fun Route.rutasBitacora(dataSource: DataSource) {
    authenticate(AUTH_JWT) {
        route("/bitacora") {
            get {
                call.exigirRol(Rol.ADMINISTRADOR)
                call.respond(consultar(dataSource, call.filtrosBitacora()))
            }
            get("/verificar") {
                call.exigirRol(Rol.ADMINISTRADOR)
                call.respond(verificar(dataSource))
            }
        }
    }
}

private data class FiltrosBitacora(
    val accion: String?,
    val entidad: String?,
    val entidadId: String?,
    val usuarioId: UUID?,
    val desde: Instant?,
    val hasta: Instant?,
    val limite: Int,
    /** Id del último registro de la página anterior: se sigue con los de id menor. */
    val antesDe: Long?,
)

private fun consultar(dataSource: DataSource, f: FiltrosBitacora): PaginaBitacora {
    val condiciones = mutableListOf<String>()
    val parametros = mutableListOf<Any?>()
    fun filtro(sql: String, valor: Any?) {
        if (valor != null) { condiciones += sql; parametros += valor }
    }
    filtro("accion = ?", f.accion)
    filtro("entidad = ?", f.entidad)
    filtro("entidad_id = ?", f.entidadId)
    filtro("usuario_id = ?", f.usuarioId)
    filtro("ocurrida_en >= ?::timestamptz", f.desde)
    filtro("ocurrida_en < ?::timestamptz", f.hasta)
    filtro("id < ?", f.antesDe)
    val donde = if (condiciones.isEmpty()) "" else "WHERE " + condiciones.joinToString(" AND ")
    val filas = dataSource.connection.use { conn ->
        conn.consultar(
            """SELECT id, ocurrida_en, usuario_id, accion, entidad, entidad_id, detalle::text AS detalle,
                      host(ip) AS ip, encode(hash, 'hex') AS hash
               FROM bitacora $donde ORDER BY id DESC LIMIT ?""",
            *parametros.toTypedArray(), f.limite + 1,
        ) { rs ->
            RegistroBitacora(
                id = rs.getLong("id"),
                ocurridaEn = rs.getTimestamp("ocurrida_en").toInstant().toString(),
                usuarioId = rs.uuidONulo("usuario_id")?.toString(),
                accion = rs.getString("accion"),
                entidad = rs.getString("entidad"),
                entidadId = rs.getString("entidad_id"),
                detalle = Json.parseToJsonElement(rs.getString("detalle")).let { it as? JsonObject ?: JsonObject(mapOf("valor" to it)) },
                ip = rs.getString("ip"),
                hash = rs.getString("hash"),
            )
        }
    }
    val pagina = filas.take(f.limite)
    val siguiente = if (filas.size > f.limite) JsonPrimitive(escribirCursor(pagina.last().id)) else JsonNull
    return PaginaBitacora(pagina, siguiente)
}

private fun verificar(dataSource: DataSource): VerificacionBitacora = dataSource.connection.use(::verificarBitacora)

/** Revisa toda la cadena. En una sola sentencia, para que el conteo y la revisión vean la misma foto de la tabla. */
internal fun verificarBitacora(conn: Connection): VerificacionBitacora =
    conn.consultarUno(
        "SELECT (SELECT count(*) FROM bitacora) AS total, (SELECT min(id) FROM fn_verificar_bitacora()) AS alterado",
    ) { rs ->
        val alterado = rs.getLong("alterado").takeUnless { rs.wasNull() }
        VerificacionBitacora(
            integra = alterado == null,
            registrosRevisados = rs.getLong("total"),
            primerIdAlterado = alterado?.let(::JsonPrimitive) ?: JsonNull,
            verificadaEn = Instant.now().toString(),
        )
    }!!

private fun escribirCursor(id: Long) = Base64.getUrlEncoder().withoutPadding().encodeToString("b:$id".toByteArray())

private fun leerCursor(c: String): Long = runCatching {
    String(Base64.getUrlDecoder().decode(c)).removePrefix("b:").toLong().also { require(it > 0) }
}.getOrElse { throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "El cursor no es válido") }

private fun ApplicationCall.filtrosBitacora(): FiltrosBitacora {
    val q = request.queryParameters
    fun texto(nombre: String) = q[nombre]?.trim()?.takeIf { it.isNotEmpty() }
    fun fecha(nombre: String) = q[nombre]?.let {
        runCatching { Instant.parse(it) }
            .getOrElse { throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "$nombre debe ser una fecha ISO 8601, por ejemplo 2026-10-25T00:00:00Z") }
    }
    val usuarioId = q["usuarioId"]?.let {
        runCatching { UUID.fromString(it) }.getOrElse { throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "usuarioId no es un UUID") }
    }
    val limite = q["limite"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..200 } }
        ?: if (q["limite"] == null) 50 else throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "limite debe estar entre 1 y 200")
    return FiltrosBitacora(
        accion = texto("accion")?.uppercase(),
        entidad = texto("entidad"),
        entidadId = texto("entidadId"),
        usuarioId = usuarioId,
        desde = fecha("desde"),
        hasta = fecha("hasta"),
        limite = limite,
        antesDe = q["cursor"]?.let(::leerCursor),
    )
}
