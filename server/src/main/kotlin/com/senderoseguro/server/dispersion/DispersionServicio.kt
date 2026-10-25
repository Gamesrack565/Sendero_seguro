package com.senderoseguro.server.dispersion

import com.senderoseguro.server.plataforma.consultar
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.instante
import com.senderoseguro.server.plataforma.transaccion
import com.senderoseguro.server.plataforma.uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID
import javax.sql.DataSource

/**
 * Canalización y asignación (P5, RF-10, CU-15).
 *
 * 1. Toma la regla de `regla_canalizacion` para el tipo de la alerta; si el tipo no tiene, usa la de la genérica.
 * 2. Busca la unidad DISPONIBLE más cercana de la corporación destino (`fn_distancia_m`).
 * 3. Crea la `asignacion`, pasa la unidad a ASIGNADA y la alerta a ASIGNADA con su plazo de acuse.
 *
 * Todo pasa en una sola transacción. La alerta y la unidad se bloquean (FOR UPDATE), así que dos despachos
 * al mismo tiempo no asignan la misma alerta dos veces ni la misma unidad a dos alertas.
 */
class DispersionServicio(
    private val dataSource: DataSource,
    private val scope: CoroutineScope,
) : Dispersion {

    private val log = LoggerFactory.getLogger(DispersionServicio::class.java)

    override fun despachar(alertaId: UUID) {
        scope.launch(Dispatchers.IO) {
            try {
                val resultado = despacharAhora(alertaId)
                log.info("Dispersión de {}: {}", alertaId, resultado)
            } catch (e: Exception) {
                log.error("Falló la dispersión de {}", alertaId, e)
            }
        }
    }

    /**
     * Despacha las alertas que se quedaron en ENVIADA sin plazo de acuse, por ejemplo porque el servidor se reinició
     * justo después de registrarlas. Lo llama el temporizador. Regresa cuántas despachó.
     */
    fun despacharPendientes(): Int {
        val pendientes = dataSource.connection.use { conn ->
            conn.consultar(
                """SELECT id FROM alerta
                   WHERE estado = 'ENVIADA' AND acuse_limite_en IS NULL AND NOT sin_acuse
                     AND registrada_en < now() - interval '10 seconds'
                   ORDER BY registrada_en
                   LIMIT 100""",
            ) { it.uuid("id") }
        }
        pendientes.forEach { id ->
            try {
                log.info("Dispersión pendiente de {}: {}", id, despacharAhora(id))
            } catch (e: Exception) {
                log.error("Falló la dispersión pendiente de {}", id, e)
            }
        }
        return pendientes.size
    }

    /** Hace la dispersión en el mismo hilo y regresa el resultado. Lo usan las pruebas y la ruta de desarrollo. */
    fun despacharAhora(alertaId: UUID): ResultadoDispersion = dataSource.transaccion { conn ->
        val alerta = conn.consultarUno(
            "SELECT estado::text AS estado, tipo_incidente_id, version_catalogo_id FROM alerta WHERE id = ? FOR UPDATE",
            alertaId,
        ) { Triple(it.getString("estado"), it.getShort("tipo_incidente_id"), it.getInt("version_catalogo_id")) }
            ?: return@transaccion ResultadoDispersion.NoExiste
        val (estado, tipoId, versionId) = alerta

        if (estado != "ENVIADA") return@transaccion ResultadoDispersion.YaDespachada(estado)

        val regla = Canalizacion.buscarRegla(conn, versionId, tipoId) ?: run {
            // Sin regla no hay a quién escalar: se marca sin acuse para que no se reintente y se avisa a los Administradores.
            conn.ejecutar("UPDATE alerta SET sin_acuse = true WHERE id = ?", alertaId)
            Canalizacion.registrarEvento(conn, alertaId, "ENVIADA", "ENVIADA", buildJsonObject { put("motivo", "SIN_REGLA") })
            Canalizacion.avisarAdministradores(conn, alertaId, "SIN_ACUSE")
            return@transaccion ResultadoDispersion.SinRegla
        }

        val unidad = Canalizacion.buscarUnidad(conn, regla.corporacionId, tipoId, Canalizacion.ubicacionActual(conn, alertaId))
            ?: run {
                // Sin unidad: el plazo vence ya, para que el escalamiento la tome en su siguiente vuelta (CU-15 2a).
                conn.ejecutar("UPDATE alerta SET acuse_limite_en = now() WHERE id = ?", alertaId)
                Canalizacion.registrarEvento(conn, alertaId, "ENVIADA", "ENVIADA", buildJsonObject {
                    put("motivo", "SIN_UNIDAD_DISPONIBLE")
                    put("corporacion", regla.corporacion)
                })
                Canalizacion.avisarOperadores(conn, alertaId, regla.corporacionId, unidadAsignada = null)
                Canalizacion.avisarAdministradores(conn, alertaId, "ALERTA_INFORMATIVA")
                return@transaccion ResultadoDispersion.SinUnidad(regla.corporacion)
            }

        Canalizacion.asignar(conn, alertaId, unidad, nivel = 0)
        Canalizacion.avisarOperadores(conn, alertaId, regla.corporacionId, unidad.id)
        val acuseLimite = conn.consultarUno(
            """UPDATE alerta SET estado = 'ASIGNADA', acuse_limite_en = now() + make_interval(secs => ?)
               WHERE id = ? RETURNING acuse_limite_en""",
            regla.tiempoMaxAcuseSeg.toDouble(), alertaId,
        ) { it.instante("acuse_limite_en")!! }!!

        Canalizacion.registrarEvento(conn, alertaId, "ENVIADA", "ASIGNADA", buildJsonObject {
            put("unidadId", unidad.id.toString())
            put("unidad", unidad.nombre)
            put("corporacion", regla.corporacion)
            put("distanciaM", unidad.distanciaM)
            put("tiempoMaxAcuseSeg", regla.tiempoMaxAcuseSeg)
        })

        ResultadoDispersion.Asignada(unidad.id, unidad.nombre, regla.corporacion, unidad.distanciaM, acuseLimite)
    }
}
