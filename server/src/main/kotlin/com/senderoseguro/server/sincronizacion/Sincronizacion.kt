package com.senderoseguro.server.sincronizacion

import com.senderoseguro.server.dispersion.AlertasRecibidas
import com.senderoseguro.server.dispersion.Dispersion
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.ErrorApi
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.transaccion
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Tipos de la cola del teléfono, en el orden en que se procesan (contrato de `/sync/lote`), con su vencimiento:
 * un acuse sirve 2 horas, un reporte 6 días y lo demás 72 horas (`cola_sincronizacion` del teléfono).
 */
enum class TipoSync(val vigencia: Duration) {
    ALERTA(Duration.ofHours(72)),
    ACUSE(Duration.ofHours(2)),
    UBICACION(Duration.ofHours(72)),
    CANCELACION(Duration.ofHours(72)),
    REPORTE(Duration.ofDays(6)),
    TRAYECTO(Duration.ofHours(72)),
    CHECKIN(Duration.ofHours(72)),
}

data class ElementoSync(val tipo: TipoSync, val id: UUID, val creadoEn: Instant, val datos: JsonObject)

/** Quién manda el lote: la persona del token y su teléfono. */
data class ContextoSync(val usuarioId: UUID, val dispositivoId: UUID)

sealed interface ResultadoManejo {
    /** Se guardó. [despachar]: id de una alerta nueva, que la plataforma despacha después de confirmar la transacción. */
    data class Aceptado(val referencia: String? = null, val despachar: UUID? = null) : ResultadoManejo

    /** Ya estaba guardado (el teléfono reenvió). */
    data class Duplicado(val referencia: String? = null) : ResultadoManejo

    /**
     * No se guardó. Con [temporal] el teléfono lo conserva y lo vuelve a mandar; sin él, lo borra de su cola.
     * Lo que el manejador haya escrito se deshace.
     */
    data class Rechazado(val codigo: String, val mensaje: String, val temporal: Boolean = false) : ResultadoManejo
}

/**
 * Lo programa el módulo dueño de cada tipo y lo registra en la plataforma (`Application.kt`). Recibe la transacción
 * del elemento y los `datos` tal como los mandó el teléfono. Tiene que ser idempotente: el teléfono puede reenviar el
 * mismo elemento, o uno nuevo con lecturas que ya llegaron (por ejemplo, ubicaciones), y no debe duplicar nada.
 */
fun interface ManejadorSync {
    fun procesar(conn: Connection, contexto: ContextoSync, elemento: ElementoSync): ResultadoManejo
}

@Serializable
data class ResultadoSync(val id: String, val resultado: String, val error: ErrorApi? = null, val referencia: String? = null)

/**
 * Recepción del lote offline del teléfono (P14, RF-17, A-05). Ordena los elementos por tipo y por hora de creación,
 * descarta los vencidos y entrega cada uno a su [ManejadorSync], cada uno en su propia transacción: si uno falla,
 * los demás siguen. Responde en el mismo orden en que llegaron.
 *
 * Las alertas se revisan antes de llamar al manejador: si ya llegaron por internet, SMS o un lote anterior, salen
 * como DUPLICADO con su folio (la búsqueda es la misma que usa la pasarela SMS).
 */
class Sincronizacion(
    private val dataSource: DataSource,
    private val dispersion: Dispersion,
    private val manejadores: Map<TipoSync, ManejadorSync>,
) {
    private val log = LoggerFactory.getLogger(Sincronizacion::class.java)

    fun procesar(contexto: ContextoSync, elementos: List<ElementoSync>, ahora: Instant = Instant.now()): List<ResultadoSync> {
        val resultados = elementos.sortedWith(compareBy({ it.tipo.ordinal }, { it.creadoEn }))
            .associateWith { procesarUno(contexto, it, ahora) }
        return elementos.map { resultados.getValue(it) }
    }

    private fun procesarUno(contexto: ContextoSync, e: ElementoSync, ahora: Instant): ResultadoSync {
        if (e.creadoEn.isAfter(ahora.plus(Duration.ofMinutes(10)))) {
            return rechazo(e, ResultadoManejo.Rechazado("VALIDACION", "creadoEn está en el futuro; revisa la hora del teléfono"))
        }
        if (e.creadoEn.plus(e.tipo.vigencia).isBefore(ahora)) {
            return ResultadoSync(e.id.toString(), "VENCIDO", ErrorApi("VENCIDO", "El elemento venció sin sincronizarse"))
        }
        val manejador = manejadores[e.tipo]
            ?: return rechazo(e, ResultadoManejo.Rechazado("ERROR_INTERNO", "El servidor todavía no procesa elementos ${e.tipo}", temporal = true))
        val resultado = try {
            dataSource.transaccion { conn ->
                val r = alertaYaRecibida(conn, contexto, e)?.let { ResultadoManejo.Duplicado(it) }
                    ?: manejador.procesar(conn, contexto, e)
                if (r is ResultadoManejo.Rechazado) conn.rollback()
                r
            }
        } catch (ex: ApiException) {
            ResultadoManejo.Rechazado(ex.codigo, ex.message ?: ex.codigo, temporal = ex.status.value >= 500)
        } catch (ex: Exception) {
            log.error("Sincronización: falló el elemento {} {}", e.tipo, e.id, ex)
            ResultadoManejo.Rechazado("ERROR_INTERNO", "No se pudo procesar; se reintentará", temporal = true)
        }
        return when (resultado) {
            is ResultadoManejo.Aceptado -> {
                resultado.despachar?.let(dispersion::despachar)
                ResultadoSync(e.id.toString(), "ACEPTADO", referencia = resultado.referencia)
            }
            is ResultadoManejo.Duplicado -> ResultadoSync(e.id.toString(), "DUPLICADO", referencia = resultado.referencia)
            is ResultadoManejo.Rechazado -> rechazo(e, resultado)
        }
    }

    /** Folio de la alerta si ya estaba registrada. Deja tomado el candado de la persona hasta el fin de la transacción. */
    private fun alertaYaRecibida(conn: Connection, contexto: ContextoSync, e: ElementoSync): String? {
        if (e.tipo != TipoSync.ALERTA) return null
        val id = e.datos["id"]?.jsonPrimitive?.contentOrNull?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: e.id
        val activadaEn = e.datos["activadaEn"]?.jsonPrimitive?.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: e.creadoEn
        AlertasRecibidas.bloquear(conn, contexto.usuarioId)
        val existente = AlertasRecibidas.buscar(conn, contexto.usuarioId, id.toString().take(8), activadaEn, id) ?: return null
        return conn.consultarUno("SELECT folio FROM alerta WHERE id = ?", existente) { it.getString(1) }
    }

    private fun rechazo(e: ElementoSync, r: ResultadoManejo.Rechazado) = ResultadoSync(
        e.id.toString(), "RECHAZADO",
        ErrorApi(r.codigo, r.mensaje, buildJsonObject { put("temporal", r.temporal) }),
    )

    /** El teléfono tiene que ser de la persona del token. Si lo es, se anota que se vio. */
    fun revisarDispositivo(usuarioId: UUID, dispositivoId: UUID) = dataSource.connection.use { conn ->
        val propio = conn.consultarUno("SELECT usuario_id = ? FROM dispositivo WHERE id = ?", usuarioId, dispositivoId) { it.getBoolean(1) }
        if (propio != true) throw ApiException(HttpStatusCode.Forbidden, "SIN_PERMISO", "Ese dispositivo no es tuyo")
        conn.ejecutar("UPDATE dispositivo SET ultimo_visto_en = now() WHERE id = ?", dispositivoId)
    }

    companion object {
        const val MAX_ELEMENTOS = 200
        const val MAX_BYTES = 1_048_576

        /** El texto de un elemento como lo manda el teléfono; null si no tiene la forma del contrato. */
        fun leerElemento(o: JsonObject): ElementoSync? = runCatching {
            ElementoSync(
                tipo = TipoSync.valueOf((o["tipo"] as JsonPrimitive).content),
                id = UUID.fromString((o["id"] as JsonPrimitive).content),
                creadoEn = Instant.parse((o["creadoEn"] as JsonPrimitive).content),
                datos = o["datos"] as JsonObject,
            )
        }.getOrNull()
    }
}
