package com.senderoseguro.server.dispersion

import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.UsuarioToken
import com.senderoseguro.server.plataforma.consultar
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.dobleONulo
import com.senderoseguro.server.plataforma.instante
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * Lectura de alertas para la consola (W-02, W-03) y para la app del emisor (A-13).
 *
 * Quién ve qué:
 * - ADMINISTRADOR: todas.
 * - OPERADOR: las que tienen o tuvieron una asignación a su unidad.
 * - Emisor (INTEGRANTE o COORDINADOR): solo las suyas, sin datos de la consola. Si la canceló con el PIN
 *   de coacción, la ve como CANCELADA (la consola ve el estado real).
 * Lo que no puede ver responde 404, como si no existiera.
 */
class ConsultaAlertas(private val dataSource: DataSource) {

    data class Filtros(val estados: List<String>?, val desde: Instant?, val hasta: Instant?, val limite: Int, val cursor: String?)

    fun listar(usuario: UsuarioToken, f: Filtros): PaginaAlertas = dataSource.connection.use { conn ->
        val condiciones = mutableListOf<String>()
        val parametros = mutableListOf<Any?>()
        when (usuario.rol) {
            Rol.ADMINISTRADOR -> {}
            Rol.OPERADOR -> {
                condiciones += "EXISTS (SELECT 1 FROM asignacion x WHERE x.alerta_id = a.id AND x.unidad_id = ?)"
                parametros += Canalizacion.unidadDeOperador(conn, usuario.usuarioId) ?: return PaginaAlertas(emptyList(), null)
            }
            else -> throw ApiException(HttpStatusCode.Forbidden, "SIN_PERMISO", "La lista de alertas es solo para la consola")
        }
        f.estados?.let { estados ->
            condiciones += "a.estado::text = ANY (?)"
            parametros += conn.createArrayOf("text", estados.toTypedArray())
        }
        f.desde?.let { condiciones += "a.activada_en >= ?"; parametros += it }
        f.hasta?.let { condiciones += "a.activada_en < ?"; parametros += it }

        val inicio = f.cursor?.let(::leerCursor) ?: 0
        val where = if (condiciones.isEmpty()) "" else "WHERE " + condiciones.joinToString(" AND ")
        // Activas primero; luego la de mayor prioridad y, a igual prioridad, la más antigua.
        val filas = conn.consultar(
            """$SELECT_ALERTA
               $where
               ORDER BY (a.estado IN ('ENVIADA','ASIGNADA','ESCALADA','ACUSADA','EN_ATENCION')) DESC,
                        prioridad DESC, a.activada_en, a.id
               LIMIT ? OFFSET ?""",
            *parametros.toTypedArray(), f.limite + 1, inicio,
        ) { resumen(it, vistaEmisor = false) }
        val hayMas = filas.size > f.limite
        PaginaAlertas(filas.take(f.limite), if (hayMas) escribirCursor(inicio + f.limite) else null)
    }

    /** Detalle de una alerta, con la vista que le toca a quien pregunta. */
    fun detalle(usuario: UsuarioToken, alertaId: UUID): AlertaDetalle = dataSource.connection.use { detalle(it, usuario, alertaId) }

    internal fun detalle(conn: Connection, usuario: UsuarioToken, alertaId: UUID): AlertaDetalle {
        val vistaEmisor = when (usuario.rol) {
            Rol.ADMINISTRADOR -> false
            Rol.OPERADOR -> {
                val unidad = Canalizacion.unidadDeOperador(conn, usuario.usuarioId) ?: throw noEncontrada()
                val tuvo = conn.consultarUno(
                    "SELECT 1 FROM asignacion WHERE alerta_id = ? AND unidad_id = ?", alertaId, unidad,
                ) { true } ?: false
                if (!tuvo) throw noEncontrada()
                false
            }
            else -> true
        }
        val filtroEmisor = if (vistaEmisor) " AND a.usuario_id = ?" else ""
        val parametros = if (vistaEmisor) arrayOf<Any?>(alertaId, usuario.usuarioId) else arrayOf<Any?>(alertaId)
        return conn.consultarUno("$SELECT_ALERTA WHERE a.id = ?$filtroEmisor", *parametros) { rs ->
            val r = resumen(rs, vistaEmisor)
            AlertaDetalle(
                id = r.id, folio = r.folio, estado = r.estado, tipoIncidente = r.tipoIncidente, prioridad = r.prioridad,
                canalRecepcion = r.canalRecepcion, activadaEn = r.activadaEn, acuseLimiteEn = r.acuseLimiteEn,
                nivelEscalamiento = r.nivelEscalamiento, sinAcuse = r.sinAcuse, ultimaUbicacion = r.ultimaUbicacion,
                emisor = if (vistaEmisor) null else Emisor(
                    rs.getString("emisor_nombre"), rs.getString("emisor_telefono"),
                    rs.getString("emisor_identificador"), rs.getString("emisor_escuela"),
                ),
                descripcion = rs.getString("descripcion"),
                detonador = rs.getString("detonador"),
                nivelBateria = rs.getInt("nivel_bateria").takeUnless { rs.wasNull() },
                coaccionDetectada = if (vistaEmisor) null else rs.getBoolean("coaccion_detectada"),
                prioridadMotivos = Json.parseToJsonElement(rs.getString("prioridad_motivos")).jsonArray.map { it.jsonPrimitive.content },
                asignaciones = if (vistaEmisor) emptyList() else asignaciones(conn, alertaId),
                registradaEn = rs.instante("registrada_en")!!.toString(),
                acusadaEn = rs.instante("acusada_en")?.toString(),
                cerradaEn = rs.instante("cerrada_en")?.toString(),
                resultadoCierre = rs.getString("resultado_cierre"),
                canceladaEn = (rs.instante("cancelada_en")
                    ?: if (vistaEmisor && rs.getBoolean("coaccion_detectada")) rs.instante("activada_en") else null)?.toString(),
            )
        } ?: throw noEncontrada()
    }

    private fun asignaciones(conn: Connection, alertaId: UUID) = conn.consultar(
        """SELECT s.unidad_id, u.nombre, c.nombre AS corporacion, s.rol::text AS rol, s.nivel_escalamiento,
                  s.distancia_m::double precision AS distancia, s.asignada_en, s.liberada_en
           FROM asignacion s JOIN unidad_atencion u ON u.id = s.unidad_id JOIN corporacion c ON c.id = u.corporacion_id
           WHERE s.alerta_id = ? ORDER BY s.asignada_en, s.id""",
        alertaId,
    ) {
        AsignacionJson(
            it.getObject("unidad_id").toString(), it.getString("nombre"), it.getString("corporacion"), it.getString("rol"),
            it.getInt("nivel_escalamiento"), it.dobleONulo("distancia"),
            it.instante("asignada_en")!!.toString(), it.instante("liberada_en")?.toString(),
        )
    }

    private fun resumen(rs: ResultSet, vistaEmisor: Boolean): AlertaResumen {
        val coaccion = rs.getBoolean("coaccion_detectada")
        val lat = rs.dobleONulo("ub_lat")
        return AlertaResumen(
            id = rs.getObject("id").toString(),
            folio = rs.getString("folio"),
            // El emisor que canceló con el PIN de coacción ve la alerta como cancelada (RF-13).
            estado = if (vistaEmisor && coaccion) "CANCELADA" else rs.getString("estado"),
            tipoIncidente = TipoIncidenteRef(
                rs.getInt("tipo_id"), rs.getString("tipo_nombre"), rs.getString("tipo_categoria"), rs.getBoolean("tipo_generica"),
            ),
            prioridad = rs.getInt("prioridad"),
            canalRecepcion = rs.getString("canal_recepcion"),
            activadaEn = rs.instante("activada_en")!!.toString(),
            acuseLimiteEn = rs.instante("acuse_limite_en")?.toString(),
            nivelEscalamiento = rs.getInt("nivel_escalamiento"),
            sinAcuse = rs.getBoolean("sin_acuse"),
            ultimaUbicacion = lat?.let {
                UbicacionJson(it, rs.getDouble("ub_lon"), rs.dobleONulo("ub_precision"), rs.getString("ub_fuente"), rs.instante("ub_capturada")!!.toString())
            },
        )
    }

    private fun noEncontrada() = ApiException(HttpStatusCode.NotFound, "NO_ENCONTRADO", "La alerta no existe")

    private fun escribirCursor(n: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString("o:$n".toByteArray())
    private fun leerCursor(c: String): Int = runCatching {
        String(Base64.getUrlDecoder().decode(c)).removePrefix("o:").toInt().also { require(it >= 0) }
    }.getOrElse { throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "El cursor no es válido") }

    private companion object {
        /** Prioridad para ordenar: la de IA-4 (0–100) si respondió; si no, la del catálogo (1 = máxima) pasada a 100–20. */
        const val SELECT_ALERTA = """
            SELECT a.id, a.folio, a.estado::text AS estado, a.canal_recepcion::text AS canal_recepcion,
                   a.activada_en, a.registrada_en, a.acuse_limite_en, a.nivel_escalamiento, a.sin_acuse,
                   a.descripcion, a.detonador::text AS detonador, a.nivel_bateria, a.coaccion_detectada,
                   a.prioridad_motivos::text AS prioridad_motivos, a.acusada_en, a.cerrada_en, a.resultado_cierre, a.cancelada_en,
                   tv.tipo_incidente_id AS tipo_id, tv.nombre AS tipo_nombre, tv.categoria::text AS tipo_categoria,
                   tv.es_generica AS tipo_generica,
                   COALESCE(a.prioridad_ia, (6 - tv.prioridad) * 20) AS prioridad,
                   us.nombre AS emisor_nombre, us.telefono_movil AS emisor_telefono,
                   us.identificador_institucional AS emisor_identificador, e.nombre AS emisor_escuela,
                   ub.latitud::double precision AS ub_lat, ub.longitud::double precision AS ub_lon,
                   ub.precision_m::double precision AS ub_precision, ub.fuente::text AS ub_fuente, ub.capturada_en AS ub_capturada
            FROM alerta a
            JOIN tipo_incidente_version tv ON tv.version_catalogo_id = a.version_catalogo_id AND tv.tipo_incidente_id = a.tipo_incidente_id
            JOIN usuario us ON us.id = a.usuario_id
            JOIN escuela e ON e.id = us.escuela_id
            LEFT JOIN v_ubicacion_actual_alerta ub ON ub.alerta_id = a.id"""
    }
}
