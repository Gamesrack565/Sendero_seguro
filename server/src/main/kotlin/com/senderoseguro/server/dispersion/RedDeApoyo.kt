package com.senderoseguro.server.dispersion

import com.senderoseguro.server.plataforma.consultar
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.uuid
import com.senderoseguro.server.plataforma.uuidONulo
import java.sql.Connection
import java.util.UUID

/**
 * Red de apoyo de quien envía la alerta (RF-3, RF-9, CU-16): sus contactos de confianza y su círculo.
 *
 * - Contactos activos y CONFIRMADOS, por su canal preferido. Si el canal es PUSH, el aviso va a la cuenta vinculada.
 * - Integrantes activos y coordinador de sus círculos, por push.
 * Si una persona aparece dos veces (por ejemplo, contacto con app y además coordinador), recibe un solo aviso.
 * Los contactos SOLO_AVISO reciben el aviso sin el enlace de ubicación; los demás, con enlace.
 */
internal object RedDeApoyo {

    data class Destinatario(val usuarioId: UUID?, val contactoId: UUID?, val canal: String, val conEnlace: Boolean)

    fun destinatarios(conn: Connection, emisorId: UUID): List<Destinatario> {
        val contactos = conn.consultar(
            """SELECT id, usuario_vinculado_id, canal_preferido::text AS canal, nivel_acceso::text AS acceso
               FROM contacto_confianza
               WHERE usuario_id = ? AND activo AND estado_confirmacion = 'CONFIRMADO'
               ORDER BY prioridad, creado_en""",
            emisorId,
        ) {
            val canal = it.getString("canal")
            val vinculado = it.uuidONulo("usuario_vinculado_id")
            val conEnlace = it.getString("acceso") == "SEGUIMIENTO"
            if (canal == "PUSH") Destinatario(vinculado, null, canal, conEnlace)
            else Destinatario(null, it.uuid("id"), canal, conEnlace)
        }
        // Las cuentas que ya reciben aviso como contacto (por push o porque su contacto está vinculado) no se repiten.
        val yaAvisados = conn.consultar(
            """SELECT usuario_vinculado_id FROM contacto_confianza
               WHERE usuario_id = ? AND activo AND estado_confirmacion = 'CONFIRMADO' AND usuario_vinculado_id IS NOT NULL""",
            emisorId,
        ) { it.uuid("usuario_vinculado_id") }.toSet()

        val circulo = conn.consultar(
            """SELECT DISTINCT p.usuario_id
               FROM miembro_circulo m
               JOIN circulo c ON c.id = m.circulo_id
               JOIN (SELECT circulo_id, usuario_id FROM miembro_circulo WHERE estado = 'ACTIVA'
                     UNION SELECT id, coordinador_id FROM circulo) p ON p.circulo_id = c.id
               JOIN usuario u ON u.id = p.usuario_id
               WHERE m.usuario_id = ? AND m.estado = 'ACTIVA' AND p.usuario_id <> ? AND u.estado = 'ACTIVA'""",
            emisorId, emisorId,
        ) { it.uuid("usuario_id") }
            .filter { it !in yaAvisados }
            .map { Destinatario(it, null, "PUSH", conEnlace = true) }

        return contactos + circulo
    }

    /** Deja un aviso RED_APOYO pendiente para cada destinatario. Regresa a cuántos. */
    fun avisar(conn: Connection, alertaId: UUID, emisorId: UUID): Int {
        val lista = destinatarios(conn, emisorId)
        lista.forEach { d ->
            conn.ejecutar(
                """INSERT INTO notificacion (alerta_id, usuario_id, contacto_id, tipo, canal)
                   VALUES (?, ?, ?, 'RED_APOYO', ?::notificacion_canal)""",
                alertaId, d.usuarioId, d.contactoId, d.canal,
            )
        }
        return lista.size
    }

    /**
     * Avisa del final de la alerta a quienes recibieron el aviso RED_APOYO, por el mismo canal:
     * CIERRE (CU-19 paso 4) o CANCELACION (CU-21 paso 4).
     */
    fun avisarFin(conn: Connection, alertaId: UUID, tipo: String): Int = conn.ejecutar(
        """INSERT INTO notificacion (alerta_id, usuario_id, contacto_id, tipo, canal)
           SELECT DISTINCT alerta_id, usuario_id, contacto_id, ?::notificacion_tipo, canal
           FROM notificacion WHERE alerta_id = ? AND tipo = 'RED_APOYO'""",
        tipo, alertaId,
    )
}
