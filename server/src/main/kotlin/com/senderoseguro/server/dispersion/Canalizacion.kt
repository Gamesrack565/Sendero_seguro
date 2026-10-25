package com.senderoseguro.server.dispersion

import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.dobleONulo
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.uuid
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import java.util.UUID
import kotlin.math.round

/** Consultas que comparten la asignación inicial y el escalamiento. Todas corren dentro de la transacción de quien llama. */
internal object Canalizacion {

    data class Regla(val id: Int, val corporacionId: Short, val corporacion: String, val tiempoMaxAcuseSeg: Int)
    data class Unidad(val id: UUID, val nombre: String, val distanciaM: Double?)

    /** La última ubicación conocida de la alerta, o null si todavía no llega ninguna. */
    fun ubicacionActual(conn: Connection, alertaId: UUID): Pair<Double, Double>? = conn.consultarUno(
        "SELECT latitud::double precision AS lat, longitud::double precision AS lon FROM v_ubicacion_actual_alerta WHERE alerta_id = ?",
        alertaId,
    ) { it.getDouble("lat") to it.getDouble("lon") }

    /** La regla del tipo si existe; si no, la de la genérica de la misma versión del catálogo. */
    fun buscarRegla(conn: Connection, versionCatalogoId: Int, tipoIncidenteId: Short): Regla? = conn.consultarUno(
        """SELECT r.id, r.corporacion_destino_id, c.nombre, r.tiempo_max_acuse_seg
           FROM regla_canalizacion r
           JOIN corporacion c ON c.id = r.corporacion_destino_id
           JOIN tipo_incidente t ON t.id = r.tipo_incidente_id
           WHERE r.version_catalogo_id = ? AND (r.tipo_incidente_id = ? OR t.es_generica)
           ORDER BY (r.tipo_incidente_id = ?) DESC
           LIMIT 1""",
        versionCatalogoId, tipoIncidenteId, tipoIncidenteId,
    ) { Regla(it.getInt("id"), it.getShort("corporacion_destino_id"), it.getString("nombre"), it.getInt("tiempo_max_acuse_seg")) }

    /**
     * La unidad DISPONIBLE más cercana de la corporación. Si la unidad tiene tipos en `unidad_tipo_atencion`,
     * solo atiende esos; si no tiene ninguno, atiende todos. SKIP LOCKED salta las unidades que otro despacho
     * está asignando en ese momento.
     */
    fun buscarUnidad(conn: Connection, corporacionId: Short, tipoId: Short, ubicacion: Pair<Double, Double>?): Unidad? =
        conn.consultarUno(
            """SELECT u.id, u.nombre,
                      fn_distancia_m(?::double precision, ?::double precision,
                                     u.ultima_latitud::double precision, u.ultima_longitud::double precision) AS distancia
               FROM unidad_atencion u
               JOIN corporacion c ON c.id = u.corporacion_id
               WHERE u.corporacion_id = ? AND u.activa AND c.activa AND u.estado = 'DISPONIBLE'
                 AND (NOT EXISTS (SELECT 1 FROM unidad_tipo_atencion x WHERE x.unidad_id = u.id)
                      OR EXISTS (SELECT 1 FROM unidad_tipo_atencion x WHERE x.unidad_id = u.id AND x.tipo_incidente_id = ?))
               ORDER BY distancia NULLS LAST, u.nombre
               LIMIT 1
               FOR UPDATE OF u SKIP LOCKED""",
            ubicacion?.first, ubicacion?.second, corporacionId, tipoId,
        ) { Unidad(it.uuid("id"), it.getString("nombre"), it.dobleONulo("distancia")?.let { d -> round(d * 10) / 10 }) }

    /** Crea la asignación PRINCIPAL y marca la unidad como ASIGNADA. */
    fun asignar(conn: Connection, alertaId: UUID, unidad: Unidad, nivel: Int) {
        conn.ejecutar(
            """INSERT INTO asignacion (alerta_id, unidad_id, rol, nivel_escalamiento, distancia_m, metodo_distancia)
               VALUES (?, ?, 'PRINCIPAL', ?, ?, 'EUCLIDIANA')""",
            alertaId, unidad.id, nivel.toShort(), unidad.distanciaM,
        )
        conn.ejecutar("UPDATE unidad_atencion SET estado = 'ASIGNADA' WHERE id = ?", unidad.id)
    }

    /** Cierra las asignaciones abiertas de la alerta y regresa sus unidades a DISPONIBLE. */
    fun liberarAsignaciones(conn: Connection, alertaId: UUID) {
        conn.ejecutar(
            """UPDATE unidad_atencion SET estado = 'DISPONIBLE'
               WHERE estado IN ('ASIGNADA', 'ATENDIENDO')
                 AND id IN (SELECT unidad_id FROM asignacion WHERE alerta_id = ? AND liberada_en IS NULL)""",
            alertaId,
        )
        conn.ejecutar("UPDATE asignacion SET liberada_en = now() WHERE alerta_id = ? AND liberada_en IS NULL", alertaId)
    }

    /** La unidad del operador activo, o null si el usuario no es operador de ninguna unidad. */
    fun unidadDeOperador(conn: Connection, usuarioId: UUID): UUID? = conn.consultarUno(
        "SELECT unidad_id FROM operador WHERE usuario_id = ? AND activo",
        usuarioId,
    ) { it.uuid("unidad_id") }

    /** Agrega un renglón al historial de la alerta (`evento_alerta`, solo inserción). */
    fun registrarEvento(conn: Connection, alertaId: UUID, anterior: String?, nuevo: String, detalle: JsonObject, actor: UUID? = null) {
        conn.ejecutar(
            """INSERT INTO evento_alerta (alerta_id, estado_anterior, estado_nuevo, detalle, actor_id)
               VALUES (?, ?::alerta_estado, ?::alerta_estado, ?::jsonb, ?)""",
            alertaId, anterior, nuevo, detalle.toString(), actor,
        )
    }

    /**
     * Deja un aviso PENDIENTE por push para un usuario con cuenta. Lo entrega el servicio de avisos;
     * mientras ese servicio no exista, el aviso se queda guardado.
     */
    fun avisar(conn: Connection, alertaId: UUID, usuarioId: UUID, tipo: String) {
        conn.ejecutar(
            "INSERT INTO notificacion (alerta_id, usuario_id, tipo, canal) VALUES (?, ?, ?::notificacion_tipo, 'PUSH')",
            alertaId, usuarioId, tipo,
        )
    }

    /**
     * Avisa a los operadores de la corporación (CU-15 pasos 4 y 5): ALERTA_PRIORITARIA a los de la unidad asignada
     * y ALERTA_INFORMATIVA al resto. Sin [unidadAsignada], todos reciben la informativa (CU-15 2a).
     */
    fun avisarOperadores(conn: Connection, alertaId: UUID, corporacionId: Short, unidadAsignada: UUID?) {
        conn.ejecutar(
            """INSERT INTO notificacion (alerta_id, usuario_id, tipo, canal)
               SELECT ?, o.usuario_id,
                      CASE WHEN o.unidad_id = ? THEN 'ALERTA_PRIORITARIA' ELSE 'ALERTA_INFORMATIVA' END::notificacion_tipo,
                      'PUSH'
               FROM operador o
               JOIN unidad_atencion u ON u.id = o.unidad_id
               JOIN usuario us ON us.id = o.usuario_id
               WHERE o.activo AND u.activa AND u.corporacion_id = ? AND us.estado = 'ACTIVA'""",
            alertaId, unidadAsignada, corporacionId,
        )
    }

    /** Avisa a todos los administradores activos (RF-12). Regresa a cuántos avisó. */
    fun avisarAdministradores(conn: Connection, alertaId: UUID, tipo: String): Int = conn.ejecutar(
        """INSERT INTO notificacion (alerta_id, usuario_id, tipo, canal)
           SELECT ?, id, ?::notificacion_tipo, 'PUSH' FROM usuario WHERE rol = 'ADMINISTRADOR' AND estado = 'ACTIVA'""",
        alertaId, tipo,
    )
}
