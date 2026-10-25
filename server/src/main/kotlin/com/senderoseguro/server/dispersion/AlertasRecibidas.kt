package com.senderoseguro.server.dispersion

import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.uuid
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * Una misma alerta puede llegar por internet, por SMS y por el lote de sincronización. Por SMS solo viaja el id
 * corto (los primeros 8 caracteres del UUID), así que se reconoce por dueño, id corto y hora de activación ±60 s.
 */
object AlertasRecibidas {

    /** Se llama antes de buscar y registrar: dos llegadas de la misma persona a la vez no registran la alerta dos veces. */
    fun bloquear(conn: Connection, usuarioId: UUID) {
        conn.consultarUno("SELECT pg_advisory_xact_lock(hashtext(?::text))", usuarioId.toString()) { }
    }

    /** La alerta ya registrada con ese [id] completo, o con ese id corto y hora. Null si es nueva. */
    fun buscar(conn: Connection, usuarioId: UUID, idCorto: String, activadaEn: Instant, id: UUID? = null): UUID? =
        id?.let { conn.consultarUno("SELECT id FROM alerta WHERE id = ? AND usuario_id = ?", it, usuarioId) { rs -> rs.uuid("id") } }
            ?: conn.consultarUno(
                """SELECT id FROM alerta WHERE usuario_id = ? AND id::text LIKE ? || '%'
                   AND activada_en BETWEEN ?::timestamptz - interval '60 seconds' AND ?::timestamptz + interval '60 seconds'
                   ORDER BY registrada_en LIMIT 1""",
                usuarioId, idCorto, activadaEn, activadaEn,
            ) { it.uuid("id") }
}
