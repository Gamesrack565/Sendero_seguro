package com.senderoseguro.server.dispersion

import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.UsuarioToken
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.transaccion
import com.senderoseguro.server.plataforma.uuid
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * Atención de la alerta desde la consola (P5, CU-18, CU-19, CU-19 2a, E-01):
 * acuse, cambios de estado (ACUSADA → EN_ATENCION → ATENDIDA → CERRADA) y escalamiento manual.
 *
 * Puede actuar el operador de una unidad con asignación abierta en la alerta. El Administrador puede cambiar
 * estados y escalar, pero el acuse es solo del operador. Cada acción deja su renglón en `evento_alerta`.
 * Al emisor se le avisa del acuse, de los cambios y del cierre, salvo que haya cancelado con el PIN de
 * coacción: en ese caso la alerta sigue en silencio y no se le manda nada.
 */
class AtencionServicio(
    private val dataSource: DataSource,
    private val consulta: ConsultaAlertas,
    private val escalamiento: EscalamientoServicio,
    private val enlaces: EnlacesServicio,
) {

    /** El operador confirma que recibió la alerta. Idempotente: si su unidad ya la acusó, regresa el detalle. */
    fun acusar(usuario: UsuarioToken, alertaId: UUID): AlertaDetalle {
        var tardio = false
        val detalle = dataSource.transaccion { conn -> acusarEn(conn, usuario, alertaId).also { tardio = it == null } }
        // El acuse tardío ya quedó guardado (la transacción terminó bien); ahora se responde el 409.
        if (tardio) throw ApiException(
            HttpStatusCode.Conflict, "VENCIDO",
            "El plazo de acuse ya venció y la alerta se escaló a otra unidad. El acuse quedó registrado.",
        )
        return detalle!!
    }

    /** Regresa null si el acuse fue tardío. */
    private fun acusarEn(conn: Connection, usuario: UsuarioToken, alertaId: UUID): AlertaDetalle? {
        if (usuario.rol != Rol.OPERADOR) throw sinPermiso("Solo el operador de la unidad asignada da el acuse")
        val alerta = bloquear(conn, alertaId)
        val unidad = Canalizacion.unidadDeOperador(conn, usuario.usuarioId) ?: throw noEncontrada()
        val asignacion = asignacionDe(conn, alertaId, unidad) ?: throw noEncontrada()

        when {
            alerta.estado in setOf("CERRADA", "CANCELADA") ->
                throw transicionInvalida(alerta.estado, "ACUSADA")

            !asignacion.abierta -> {
                // CU-18 1a: el acuse llega tarde, la alerta ya se escaló a otra unidad. Se registra igualmente.
                Canalizacion.registrarEvento(conn, alertaId, alerta.estado, alerta.estado, buildJsonObject {
                    put("accion", "ACUSE_TARDIO")
                    put("unidadId", unidad.toString())
                }, usuario.usuarioId)
                return null
            }

            alerta.estado in setOf("ENVIADA", "ASIGNADA", "ESCALADA") -> {
                // El primer acuse fija acusada_en (meta de 2 minutos, RNF4) y detiene el plazo.
                conn.ejecutar(
                    """UPDATE alerta SET estado = 'ACUSADA', acusada_en = COALESCE(acusada_en, now()),
                                         acusada_por = COALESCE(acusada_por, ?)
                       WHERE id = ?""",
                    usuario.usuarioId, alertaId,
                )
                Canalizacion.registrarEvento(conn, alertaId, alerta.estado, "ACUSADA", acuseDetalle(unidad), usuario.usuarioId)
                avisarEmisor(conn, alertaId, alerta, "ACUSE")
            }

            // Ya acusada (por esta unidad o por otra que llegó de apoyo): se registra el acuse de esta unidad una vez.
            !yaAcuso(conn, alertaId, unidad) ->
                Canalizacion.registrarEvento(conn, alertaId, alerta.estado, alerta.estado, acuseDetalle(unidad), usuario.usuarioId)
        }
        return consulta.detalle(conn, usuario, alertaId)
    }

    /** Cambia el estado de atención. Pedir el estado en el que ya está regresa el detalle sin cambiar nada. */
    fun cambiarEstado(usuario: UsuarioToken, alertaId: UUID, cambio: CambioEstado): AlertaDetalle = dataSource.transaccion { conn ->
        val siguiente = mapOf("ACUSADA" to "EN_ATENCION", "EN_ATENCION" to "ATENDIDA", "ATENDIDA" to "CERRADA")
        if (cambio.estado !in siguiente.values) {
            throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "El estado debe ser EN_ATENCION, ATENDIDA o CERRADA")
        }
        val resultado = cambio.resultadoCierre?.trim()?.takeIf { it.isNotEmpty() }
        if (cambio.estado == "CERRADA" && resultado == null) {
            throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "Para cerrar hace falta resultadoCierre")
        }
        if ((resultado?.length ?: 0) > 500 || (cambio.observaciones?.length ?: 0) > 2000) {
            throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "resultadoCierre admite 500 caracteres y observaciones 2000")
        }

        val alerta = bloquear(conn, alertaId)
        exigirQuienAtiende(conn, usuario, alertaId, alerta) { transicionInvalida(alerta.estado, cambio.estado) }
        if (alerta.estado == cambio.estado) return@transaccion consulta.detalle(conn, usuario, alertaId)
        if (siguiente[alerta.estado] != cambio.estado) throw transicionInvalida(alerta.estado, cambio.estado)

        when (cambio.estado) {
            "EN_ATENCION" -> {
                conn.ejecutar("UPDATE alerta SET estado = 'EN_ATENCION' WHERE id = ?", alertaId)
                conn.ejecutar(
                    """UPDATE unidad_atencion SET estado = 'ATENDIENDO'
                       WHERE id IN (SELECT unidad_id FROM asignacion WHERE alerta_id = ? AND liberada_en IS NULL)""",
                    alertaId,
                )
            }
            "ATENDIDA" -> conn.ejecutar("UPDATE alerta SET estado = 'ATENDIDA' WHERE id = ?", alertaId)
            "CERRADA" -> {
                conn.ejecutar(
                    """UPDATE alerta SET estado = 'CERRADA', cerrada_en = now(), cerrada_por = ?,
                                         resultado_cierre = ?, observaciones_cierre = ?, acuse_limite_en = NULL
                       WHERE id = ?""",
                    usuario.usuarioId, resultado, cambio.observaciones, alertaId,
                )
                Canalizacion.liberarAsignaciones(conn, alertaId)
                // RF-9 y CU-19 paso 4: el enlace deja de funcionar y la red de apoyo recibe el aviso de cierre.
                enlaces.revocar(conn, alertaId)
                RedDeApoyo.avisarFin(conn, alertaId, "CIERRE")
            }
        }
        Canalizacion.registrarEvento(conn, alertaId, alerta.estado, cambio.estado, buildJsonObject {
            resultado?.let { put("resultadoCierre", it) }
            cambio.observaciones?.let { put("observaciones", it) }
        }, usuario.usuarioId)
        avisarEmisor(conn, alertaId, alerta, if (cambio.estado == "CERRADA") "CIERRE" else "CAMBIO_ESTADO")
        consulta.detalle(conn, usuario, alertaId)
    }

    /** Escalamiento manual al siguiente nivel de la cadena (motivo MANUAL). */
    fun escalar(usuario: UsuarioToken, alertaId: UUID, comentario: String?): EscalamientoJson = dataSource.transaccion { conn ->
        if ((comentario?.length ?: 0) > 500) throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "El comentario admite 500 caracteres")
        val alerta = bloquear(conn, alertaId)
        val yaNoActiva = { ApiException(HttpStatusCode.Conflict, "CONFLICTO", "La alerta ya no está activa (${alerta.estado})") }
        exigirQuienAtiende(conn, usuario, alertaId, alerta, yaNoActiva)
        if (alerta.estado !in setOf("ENVIADA", "ASIGNADA", "ESCALADA", "ACUSADA", "EN_ATENCION")) {
            throw ApiException(HttpStatusCode.Conflict, "CONFLICTO", "La alerta ya no está activa (${alerta.estado})")
        }
        val r = escalamiento.escalarManual(conn, alertaId, usuario.usuarioId, comentario?.trim()?.takeIf { it.isNotEmpty() })
            ?: throw ApiException(HttpStatusCode.Conflict, "CONFLICTO", "No hay más pasos en la cadena de escalamiento")
        EscalamientoJson(r.nivel, r.destino, r.corporacion, r.operadorId?.toString(), r.motivo, r.escaladaEn.toString())
    }

    /**
     * El Administrador, o el operador de una unidad con asignación abierta. Si la unidad del operador sí estuvo
     * asignada pero la alerta ya terminó (CERRADA o CANCELADA), se responde con [siTermino] en vez de 403.
     */
    private fun exigirQuienAtiende(
        conn: Connection,
        usuario: UsuarioToken,
        alertaId: UUID,
        alerta: AlertaEnAtencion,
        siTermino: () -> ApiException,
    ) {
        when (usuario.rol) {
            Rol.ADMINISTRADOR -> {
                // Que exista; el bloqueo ya lo comprobó.
            }
            Rol.OPERADOR -> {
                val unidad = Canalizacion.unidadDeOperador(conn, usuario.usuarioId) ?: throw noEncontrada()
                val asignacion = asignacionDe(conn, alertaId, unidad) ?: throw noEncontrada()
                if (!asignacion.abierta) {
                    throw if (alerta.estado in setOf("CERRADA", "CANCELADA")) siTermino() else sinPermiso("Tu unidad ya no está asignada a esta alerta")
                }
            }
            else -> throw sinPermiso("Solo la consola puede atender alertas")
        }
    }

    private fun bloquear(conn: Connection, alertaId: UUID): AlertaEnAtencion = conn.consultarUno(
        "SELECT estado::text AS estado, usuario_id, coaccion_detectada FROM alerta WHERE id = ? FOR UPDATE",
        alertaId,
    ) { AlertaEnAtencion(it.getString("estado"), it.uuid("usuario_id"), it.getBoolean("coaccion_detectada")) } ?: throw noEncontrada()

    /** La asignación más reciente de la unidad en la alerta; `abierta` si todavía no se libera. */
    private fun asignacionDe(conn: Connection, alertaId: UUID, unidad: UUID): AsignacionUnidad? = conn.consultarUno(
        """SELECT liberada_en IS NULL AS abierta FROM asignacion
           WHERE alerta_id = ? AND unidad_id = ? ORDER BY (liberada_en IS NULL) DESC, asignada_en DESC LIMIT 1""",
        alertaId, unidad,
    ) { AsignacionUnidad(it.getBoolean("abierta")) }

    private fun yaAcuso(conn: Connection, alertaId: UUID, unidad: UUID): Boolean = conn.consultarUno(
        "SELECT 1 FROM evento_alerta WHERE alerta_id = ? AND detalle->>'accion' = 'ACUSE' AND detalle->>'unidadId' = ?",
        alertaId, unidad.toString(),
    ) { true } ?: false

    private fun acuseDetalle(unidad: UUID): JsonObject = buildJsonObject {
        put("accion", "ACUSE")
        put("unidadId", unidad.toString())
    }

    /** Aviso al emisor. Con coacción no se manda nada: la persona debe seguir viendo la alerta como cancelada. */
    private fun avisarEmisor(conn: Connection, alertaId: UUID, alerta: AlertaEnAtencion, tipo: String) {
        if (!alerta.coaccion) Canalizacion.avisar(conn, alertaId, alerta.usuarioId, tipo)
    }

    private fun noEncontrada() = ApiException(HttpStatusCode.NotFound, "NO_ENCONTRADO", "La alerta no existe")
    private fun sinPermiso(mensaje: String) = ApiException(HttpStatusCode.Forbidden, "SIN_PERMISO", mensaje)
    private fun transicionInvalida(actual: String, pedido: String) = ApiException(
        HttpStatusCode.Conflict, "TRANSICION_INVALIDA", "No se puede pasar de $actual a $pedido",
        buildJsonObject { put("estadoActual", actual); put("estadoPedido", pedido) },
    )

    private class AlertaEnAtencion(val estado: String, val usuarioId: UUID, val coaccion: Boolean)
    private class AsignacionUnidad(val abierta: Boolean)
}
