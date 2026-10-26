package com.senderoseguro.server.auditoria

import com.senderoseguro.server.plataforma.Bitacora
import com.senderoseguro.server.plataforma.consultar
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.transaccion
import com.senderoseguro.server.plataforma.uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource
import kotlin.time.Duration as Plazo

/**
 * Retención de trayectos (P13, decisión 3): un trayecto terminado hace más de [dias] días y que no tuvo alerta se
 * borra junto con sus check-ins, sus enlaces y sus avisos. Los que tuvieron alerta se quedan, porque son parte del
 * historial de esa alerta; los que siguen EN_CURSO tampoco se tocan. Cada borrado queda en la bitácora como
 * PURGAR_TRAYECTOS, con la cantidad (sin ids ni datos de la persona).
 */
class RetencionTrayectos(private val dataSource: DataSource, private val dias: Int) {

    private val log = LoggerFactory.getLogger(RetencionTrayectos::class.java)

    /** Borra por tandas para no tener la tabla bloqueada mucho tiempo. Regresa cuántos trayectos borró. */
    fun purgar(ahora: Instant = Instant.now()): Int {
        val limite = ahora.minus(Duration.ofDays(dias.toLong()))
        var total = 0
        while (true) {
            val borrados = tanda(limite)
            total += borrados
            if (borrados < TANDA) break
        }
        if (total > 0) log.info("Retención: {} trayectos de más de {} días sin alerta borrados", total, dias)
        return total
    }

    private fun tanda(limite: Instant): Int = dataSource.transaccion { conn ->
        val ids = conn.consultar(
            """SELECT t.id FROM trayecto t
               WHERE t.estado <> 'EN_CURSO' AND coalesce(t.finalizado_en, t.iniciado_en) < ?::timestamptz
                 AND NOT EXISTS (SELECT 1 FROM alerta a WHERE a.trayecto_id = t.id)
               ORDER BY t.iniciado_en LIMIT $TANDA FOR UPDATE OF t SKIP LOCKED""",
            limite,
        ) { it.uuid("id") }
        if (ids.isEmpty()) return@transaccion 0
        val arreglo = conn.createArrayOf("uuid", ids.toTypedArray())
        for (tabla in listOf("checkin_trayecto", "enlace_ubicacion", "notificacion")) {
            conn.ejecutar("DELETE FROM $tabla WHERE trayecto_id = ANY(?)", arreglo)
        }
        conn.ejecutar("DELETE FROM trayecto WHERE id = ANY(?)", arreglo)
        Bitacora.registrar(
            conn, "PURGAR_TRAYECTOS", null, entidad = "trayecto",
            detalle = buildJsonObject {
                put("cantidad", ids.size)
                put("retencionDias", dias)
                put("terminadosAntesDe", limite.toString())
            },
        )
        ids.size
    }

    /** Revisa al arrancar y luego cada [cada]. */
    fun iniciar(scope: CoroutineScope, cada: Plazo) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    purgar()
                } catch (e: Exception) {
                    log.error("Retención de trayectos: falló la revisión", e)
                }
                delay(cada)
            }
        }
    }

    private companion object {
        const val TANDA = 500
    }
}
