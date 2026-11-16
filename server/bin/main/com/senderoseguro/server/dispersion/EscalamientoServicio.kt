package com.senderoseguro.server.dispersion

import com.senderoseguro.server.plataforma.consultar
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.instante
import com.senderoseguro.server.plataforma.transaccion
import com.senderoseguro.server.plataforma.uuid
import com.senderoseguro.server.plataforma.uuidONulo
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Qué pasó al escalar una alerta. */
sealed interface ResultadoEscalamiento {
    /**
     * La alerta pasó al nivel [nivel] de la cadena. [unidad] es null cuando el destino es el Coordinador del círculo.
     * [acuseLimiteEn] es null cuando la alerta ya tenía acuse (escalamiento manual de apoyo): no corre plazo nuevo.
     */
    data class Escalada(
        val nivel: Int,
        val destino: String,
        val corporacion: String?,
        val operadorId: UUID?,
        val unidad: String?,
        val motivo: String,
        val escaladaEn: Instant,
        val acuseLimiteEn: Instant?,
    ) : ResultadoEscalamiento

    /** Ya no quedan pasos en la cadena: la alerta queda marcada como sin acuse y se avisa a los Administradores. */
    data object CadenaAgotada : ResultadoEscalamiento

    /** La alerta ya tiene acuse, ya está cerrada o su plazo todavía no vence. No se hace nada. */
    data object NoAplica : ResultadoEscalamiento
}

/**
 * Escalamiento (P5, RF-12, CU-19 2a, CU-20, A-03).
 *
 * El temporizador (ver `Temporizador.kt`) llama a [escalarVencidas], que busca las alertas cuyo `acuse_limite_en`
 * ya pasó sin acuse y las manda al siguiente paso de `paso_escalamiento` de su regla:
 * - CORPORACION: asigna la unidad disponible más cercana de esa corporación.
 * - OPERADOR: asigna la unidad del operador indicado.
 * - COORDINADOR_CIRCULO: avisa al Coordinador del círculo de quien envió la alerta.
 *
 * Si el destino no puede recibirla (sin unidad o sin coordinador), pasa al siguiente paso en la misma vuelta.
 * Por falta de acuse, la unidad anterior se libera y se reinicia el plazo con el mismo tiempo de la regla.
 * Al agotar la cadena, la alerta queda con `sin_acuse = true` y se avisa a los Administradores.
 *
 * El plazo vive en la base de datos, así que un reinicio del servidor no pierde ningún temporizador.
 * Con varias instancias, SKIP LOCKED evita que dos escalen la misma alerta.
 */
class EscalamientoServicio(private val dataSource: DataSource) {

    private val log = LoggerFactory.getLogger(EscalamientoServicio::class.java)

    /** Escala todas las alertas con el plazo vencido. Regresa cuántas procesó. */
    fun escalarVencidas(): Int {
        val vencidas = dataSource.connection.use { conn ->
            conn.consultar(
                """SELECT id FROM alerta
                   WHERE NOT sin_acuse AND acuse_limite_en <= now() AND estado IN ('ENVIADA', 'ASIGNADA', 'ESCALADA')
                   ORDER BY acuse_limite_en
                   LIMIT 100""",
            ) { it.uuid("id") }
        }
        var procesadas = 0
        for (id in vencidas) {
            try {
                val r = escalarPorFaltaDeAcuse(id)
                if (r != ResultadoEscalamiento.NoAplica) {
                    procesadas++
                    log.info("Escalamiento de {}: {}", id, r)
                }
            } catch (e: Exception) {
                log.error("Falló el escalamiento de {}", id, e)
            }
        }
        return procesadas
    }

    /** Escala una alerta si sigue sin acuse y con el plazo vencido. Todo en una transacción. */
    fun escalarPorFaltaDeAcuse(alertaId: UUID): ResultadoEscalamiento = dataSource.transaccion { conn ->
        val alerta = conn.consultarUno(
            """SELECT $COLUMNAS FROM alerta
               WHERE id = ? AND NOT sin_acuse AND acuse_limite_en <= now() AND estado IN ('ENVIADA', 'ASIGNADA', 'ESCALADA')
               FOR UPDATE SKIP LOCKED""",
            alertaId,
        ) { leer(it) } ?: return@transaccion ResultadoEscalamiento.NoAplica

        siguienteNivel(conn, alertaId, alerta, "SIN_ACUSE", conservarUnidades = false, actor = null, comentario = null)?.let {
            return@transaccion it
        }

        // Se agotó la cadena (o la regla no tiene pasos): CU-20 2a.
        Canalizacion.liberarAsignaciones(conn, alertaId)
        conn.ejecutar("UPDATE alerta SET sin_acuse = true, acuse_limite_en = NULL WHERE id = ?", alertaId)
        Canalizacion.registrarEvento(conn, alertaId, alerta.estado, alerta.estado, buildJsonObject { put("motivo", "CADENA_AGOTADA") })
        Canalizacion.avisarAdministradores(conn, alertaId, "SIN_ACUSE")
        ResultadoEscalamiento.CadenaAgotada
    }

    /**
     * Escalamiento manual desde la consola (motivo MANUAL), dentro de la transacción de quien llama, que ya bloqueó la alerta.
     * - Sin acuse todavía (ENVIADA, ASIGNADA, ESCALADA): igual que por falta de acuse.
     * - Con acuse (ACUSADA, EN_ATENCION): la unidad que atiende se queda y el siguiente nivel llega como apoyo (CU-19 2a).
     * Regresa null si ya no quedan pasos; en ese caso no cambia nada.
     */
    internal fun escalarManual(conn: Connection, alertaId: UUID, actor: UUID, comentario: String?): ResultadoEscalamiento.Escalada? {
        val alerta = conn.consultarUno("SELECT $COLUMNAS FROM alerta WHERE id = ?", alertaId) { leer(it) } ?: return null
        val conAcuse = alerta.estado in setOf("ACUSADA", "EN_ATENCION")
        return siguienteNivel(conn, alertaId, alerta, "MANUAL", conservarUnidades = conAcuse, actor = actor, comentario = comentario)
    }

    /**
     * Recorre los pasos que siguen al nivel actual hasta que uno pueda recibir la alerta.
     * Regresa null si ningún paso pudo; los pasos intentados quedan registrados.
     */
    private fun siguienteNivel(
        conn: Connection,
        alertaId: UUID,
        alerta: AlertaPorEscalar,
        motivo: String,
        conservarUnidades: Boolean,
        actor: UUID?,
        comentario: String?,
    ): ResultadoEscalamiento.Escalada? {
        val regla = Canalizacion.buscarRegla(conn, alerta.versionCatalogoId, alerta.tipoIncidenteId) ?: return null
        val pasos = pasosDesde(conn, regla.id, alerta.nivel + 1)
        if (pasos.isEmpty()) return null
        val ubicacion = Canalizacion.ubicacionActual(conn, alertaId)
        var estado = alerta.estado

        for (paso in pasos) {
            // Cada paso intentado queda en `escalamiento`, aunque su destino no pueda recibir la alerta.
            val escaladaEn = conn.consultarUno(
                """INSERT INTO escalamiento (alerta_id, nivel, paso_escalamiento_id, destino_tipo, corporacion_id, operador_id, motivo)
                   VALUES (?, ?, ?, ?::destino_escalamiento, ?, ?, ?)
                   RETURNING escalada_en""",
                alertaId, paso.orden, paso.id, paso.destino, paso.corporacionId, paso.operadorId, motivo,
            ) { it.instante("escalada_en")!! }!!
            if (!conservarUnidades) Canalizacion.liberarAsignaciones(conn, alertaId)

            val recibio = when (paso.destino) {
                "CORPORACION" -> Canalizacion.buscarUnidad(conn, paso.corporacionId!!, alerta.tipoIncidenteId, ubicacion)
                    ?.also {
                        Canalizacion.asignar(conn, alertaId, it, paso.orden.toInt())
                        Canalizacion.avisarOperadores(conn, alertaId, paso.corporacionId, it.id)
                    }
                    ?.let { Destino(it.nombre) }
                "OPERADOR" -> unidadDelOperador(conn, paso.operadorId!!, ubicacion)
                    ?.also {
                        Canalizacion.asignar(conn, alertaId, it, paso.orden.toInt())
                        Canalizacion.avisar(conn, alertaId, paso.operadorId, "ALERTA_PRIORITARIA")
                    }
                    ?.let { Destino(it.nombre) }
                else -> coordinadores(conn, alerta.usuarioId)
                    .onEach { Canalizacion.avisar(conn, alertaId, it, "ALERTA_PRIORITARIA") }
                    .takeIf { it.isNotEmpty() }
                    ?.let { Destino(null) }
            }

            // Con acuse, la alerta conserva su estado (sigue EN_ATENCION); sin acuse pasa a ESCALADA.
            val estadoNuevo = if (conservarUnidades) estado else "ESCALADA"
            val detalle = buildJsonObject {
                put("motivo", motivo)
                put("nivel", paso.orden.toInt())
                put("destino", paso.destino)
                paso.corporacion?.let { put("corporacion", it) }
                recibio?.unidad?.let { put("unidad", it) }
                comentario?.let { put("comentario", it) }
                if (recibio == null) put("resultado", if (paso.destino == "COORDINADOR_CIRCULO") "SIN_COORDINADOR" else "SIN_UNIDAD_DISPONIBLE")
            }
            Canalizacion.registrarEvento(conn, alertaId, estado, estadoNuevo, detalle, actor)
            estado = estadoNuevo
            conn.ejecutar(
                "UPDATE alerta SET estado = ?::alerta_estado, nivel_escalamiento = ? WHERE id = ?",
                estadoNuevo, paso.orden, alertaId,
            )

            if (recibio != null) {
                val limite = if (conservarUnidades) null else conn.consultarUno(
                    "UPDATE alerta SET acuse_limite_en = now() + make_interval(secs => ?) WHERE id = ? RETURNING acuse_limite_en",
                    regla.tiempoMaxAcuseSeg.toDouble(), alertaId,
                ) { it.instante("acuse_limite_en")!! }
                Canalizacion.avisarAdministradores(conn, alertaId, "ESCALAMIENTO")
                return ResultadoEscalamiento.Escalada(
                    paso.orden.toInt(), paso.destino, paso.corporacion, paso.operadorId, recibio.unidad, motivo, escaladaEn, limite,
                )
            }
        }
        return null
    }

    private fun pasosDesde(conn: Connection, reglaId: Int, orden: Int): List<Paso> = conn.consultar(
        """SELECT p.id, p.orden, p.destino_tipo::text AS destino, p.corporacion_id, c.nombre AS corporacion, p.operador_id
           FROM paso_escalamiento p LEFT JOIN corporacion c ON c.id = p.corporacion_id
           WHERE p.regla_id = ? AND p.orden >= ?
           ORDER BY p.orden""",
        reglaId, orden.toShort(),
    ) {
        Paso(
            it.getInt("id"), it.getShort("orden"), it.getString("destino"),
            it.getShort("corporacion_id").takeUnless { _ -> it.wasNull() }, it.getString("corporacion"), it.uuidONulo("operador_id"),
        )
    }

    /** La unidad del operador, si está activo y su unidad está DISPONIBLE. */
    private fun unidadDelOperador(conn: Connection, operadorId: UUID, ubicacion: Pair<Double, Double>?): Canalizacion.Unidad? =
        conn.consultarUno(
            """SELECT u.id, u.nombre,
                      fn_distancia_m(?::double precision, ?::double precision,
                                     u.ultima_latitud::double precision, u.ultima_longitud::double precision) AS distancia
               FROM operador o JOIN unidad_atencion u ON u.id = o.unidad_id
               WHERE o.usuario_id = ? AND o.activo AND u.activa AND u.estado = 'DISPONIBLE'
               FOR UPDATE OF u SKIP LOCKED""",
            ubicacion?.first, ubicacion?.second, operadorId,
        ) { Canalizacion.Unidad(it.uuid("id"), it.getString("nombre"), it.getDouble("distancia").takeUnless { _ -> it.wasNull() }) }

    /** Coordinadores activos de los círculos donde la persona es integrante activa. */
    private fun coordinadores(conn: Connection, usuarioId: UUID): List<UUID> = conn.consultar(
        """SELECT DISTINCT c.coordinador_id
           FROM miembro_circulo m
           JOIN circulo c ON c.id = m.circulo_id
           JOIN usuario u ON u.id = c.coordinador_id
           WHERE m.usuario_id = ? AND m.estado = 'ACTIVA' AND c.coordinador_id <> ? AND u.estado = 'ACTIVA'""",
        usuarioId, usuarioId,
    ) { it.uuid("coordinador_id") }

    private fun leer(rs: java.sql.ResultSet) = AlertaPorEscalar(
        rs.getString("estado"), rs.uuid("usuario_id"), rs.getShort("tipo_incidente_id"),
        rs.getInt("version_catalogo_id"), rs.getInt("nivel_escalamiento"),
    )

    private class AlertaPorEscalar(
        val estado: String,
        val usuarioId: UUID,
        val tipoIncidenteId: Short,
        val versionCatalogoId: Int,
        val nivel: Int,
    )
    private data class Paso(
        val id: Int, val orden: Short, val destino: String,
        val corporacionId: Short?, val corporacion: String?, val operadorId: UUID?,
    )
    private data class Destino(val unidad: String?)

    private companion object {
        const val COLUMNAS = "estado::text AS estado, usuario_id, tipo_incidente_id, version_catalogo_id, nivel_escalamiento"
    }
}
