package com.senderoseguro.server.dispersion

import com.senderoseguro.server.plataforma.consultar
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.instante
import com.senderoseguro.server.plataforma.transaccion
import com.senderoseguro.server.plataforma.uuid
import com.senderoseguro.server.plataforma.uuidONulo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.time.Duration

/** Qué pasó al escalar una alerta. */
sealed interface ResultadoEscalamiento {
    /** La alerta pasó al nivel [nivel] de la cadena. [unidad] es null cuando el destino es el Coordinador del círculo. */
    data class Escalada(
        val nivel: Int,
        val destino: String,
        val corporacion: String?,
        val unidad: String?,
        val acuseLimiteEn: Instant,
    ) : ResultadoEscalamiento

    /** Ya no quedan pasos en la cadena: la alerta queda marcada como sin acuse y se avisa a los Administradores. */
    data object CadenaAgotada : ResultadoEscalamiento

    /** La alerta ya tiene acuse, ya está cerrada o su plazo todavía no vence. No se hace nada. */
    data object NoAplica : ResultadoEscalamiento
}

/**
 * Escalamiento por falta de acuse (P5, RF-12, CU-20, A-03).
 *
 * Cada [cada] tiempo busca las alertas cuyo `acuse_limite_en` ya pasó sin acuse y las manda al siguiente paso
 * de `paso_escalamiento` de su regla:
 * - CORPORACION: asigna la unidad disponible más cercana de esa corporación. La unidad anterior se libera.
 * - OPERADOR: asigna la unidad del operador indicado.
 * - COORDINADOR_CIRCULO: avisa al Coordinador del círculo de quien envió la alerta.
 *
 * Si el destino no puede recibirla (sin unidad o sin coordinador), pasa al siguiente paso en la misma vuelta.
 * En cada escalamiento se reinicia el plazo con el mismo tiempo de la regla y se avisa a los Administradores.
 * Al agotar la cadena, la alerta queda con `sin_acuse = true` y se avisa a los Administradores.
 *
 * El plazo vive en la base de datos, así que un reinicio del servidor no pierde ningún temporizador.
 * Con varias instancias, SKIP LOCKED evita que dos escalen la misma alerta.
 */
class EscalamientoServicio(private val dataSource: DataSource) {

    private val log = LoggerFactory.getLogger(EscalamientoServicio::class.java)

    /** Arranca la revisión periódica. Se detiene sola cuando se apaga el servidor. */
    fun iniciar(scope: CoroutineScope, cada: Duration) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val n = escalarVencidas()
                    if (n > 0) log.info("Escalamiento: {} alertas procesadas", n)
                } catch (e: Exception) {
                    log.error("Falló la revisión de escalamiento", e)
                }
                delay(cada)
            }
        }
    }

    /** Escala todas las alertas con el plazo vencido. Regresa cuántas procesó. */
    fun escalarVencidas(): Int {
        val vencidas = dataSource.connection.use { conn ->
            conn.consultar(
                """SELECT id FROM alerta
                   WHERE acusada_en IS NULL AND NOT sin_acuse AND acuse_limite_en <= now()
                     AND estado IN ('ENVIADA', 'ASIGNADA', 'ESCALADA')
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
            """SELECT estado::text AS estado, usuario_id, tipo_incidente_id, version_catalogo_id, nivel_escalamiento
               FROM alerta
               WHERE id = ? AND acusada_en IS NULL AND NOT sin_acuse AND acuse_limite_en <= now()
                 AND estado IN ('ENVIADA', 'ASIGNADA', 'ESCALADA')
               FOR UPDATE SKIP LOCKED""",
            alertaId,
        ) {
            AlertaVencida(
                it.getString("estado"), it.uuid("usuario_id"), it.getShort("tipo_incidente_id"),
                it.getInt("version_catalogo_id"), it.getInt("nivel_escalamiento"),
            )
        } ?: return@transaccion ResultadoEscalamiento.NoAplica

        val regla = Canalizacion.buscarRegla(conn, alerta.versionCatalogoId, alerta.tipoIncidenteId)
        val pasos = regla?.let { pasosDesde(conn, it.id, alerta.nivel + 1) }.orEmpty()
        val ubicacion = Canalizacion.ubicacionActual(conn, alertaId)

        for (paso in pasos) {
            // Cada paso intentado queda en `escalamiento`, aunque su destino no pueda recibir la alerta.
            conn.ejecutar(
                """INSERT INTO escalamiento (alerta_id, nivel, paso_escalamiento_id, destino_tipo, corporacion_id, operador_id, motivo)
                   VALUES (?, ?, ?, ?::destino_escalamiento, ?, ?, 'SIN_ACUSE')""",
                alertaId, paso.orden, paso.id, paso.destino, paso.corporacionId, paso.operadorId,
            )
            Canalizacion.liberarAsignaciones(conn, alertaId)

            val recibio = when (paso.destino) {
                "CORPORACION" -> Canalizacion.buscarUnidad(conn, paso.corporacionId!!, alerta.tipoIncidenteId, ubicacion)
                    ?.also { Canalizacion.asignar(conn, alertaId, it, paso.orden.toInt()) }
                    ?.let { Destino(it.nombre) }
                "OPERADOR" -> unidadDelOperador(conn, paso.operadorId!!, ubicacion)
                    ?.also { Canalizacion.asignar(conn, alertaId, it, paso.orden.toInt()) }
                    ?.let { Destino(it.nombre) }
                else -> coordinadores(conn, alerta.usuarioId)
                    .onEach { Canalizacion.avisar(conn, alertaId, it, "ALERTA_PRIORITARIA") }
                    .takeIf { it.isNotEmpty() }
                    ?.let { Destino(null) }
            }

            val detalle = buildJsonObject {
                put("motivo", "SIN_ACUSE")
                put("nivel", paso.orden.toInt())
                put("destino", paso.destino)
                paso.corporacion?.let { put("corporacion", it) }
                recibio?.unidad?.let { put("unidad", it) }
                if (recibio == null) put("resultado", if (paso.destino == "COORDINADOR_CIRCULO") "SIN_COORDINADOR" else "SIN_UNIDAD_DISPONIBLE")
            }
            Canalizacion.registrarEvento(conn, alertaId, alerta.estado, "ESCALADA", detalle)
            alerta.estado = "ESCALADA"
            conn.ejecutar("UPDATE alerta SET estado = 'ESCALADA', nivel_escalamiento = ? WHERE id = ?", paso.orden, alertaId)

            if (recibio != null) {
                val limite = conn.consultarUno(
                    "UPDATE alerta SET acuse_limite_en = now() + make_interval(secs => ?) WHERE id = ? RETURNING acuse_limite_en",
                    regla!!.tiempoMaxAcuseSeg.toDouble(), alertaId,
                ) { it.instante("acuse_limite_en")!! }!!
                Canalizacion.avisarAdministradores(conn, alertaId, "ESCALAMIENTO")
                return@transaccion ResultadoEscalamiento.Escalada(
                    paso.orden.toInt(), paso.destino, paso.corporacion, recibio.unidad, limite,
                )
            }
        }

        // Se agotó la cadena (o la regla no tiene pasos): CU-20 2a.
        Canalizacion.liberarAsignaciones(conn, alertaId)
        conn.ejecutar("UPDATE alerta SET sin_acuse = true, acuse_limite_en = NULL WHERE id = ?", alertaId)
        Canalizacion.registrarEvento(conn, alertaId, alerta.estado, alerta.estado, buildJsonObject { put("motivo", "CADENA_AGOTADA") })
        Canalizacion.avisarAdministradores(conn, alertaId, "SIN_ACUSE")
        ResultadoEscalamiento.CadenaAgotada
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

    private class AlertaVencida(
        var estado: String,
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
}
