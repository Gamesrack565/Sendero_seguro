package com.senderoseguro.server.alertas

import com.senderoseguro.server.cuentas.errorValidacion
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.transaccion
import com.senderoseguro.server.plataforma.uuid
import com.senderoseguro.server.sincronizacion.ManejadorSync
import com.senderoseguro.server.sincronizacion.ResultadoManejo
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.sql.Connection
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import javax.sql.DataSource

/**
 * P6: lecturas de ubicación de una alerta activa, mientras la persona se mueve. Llegan por `POST
 * /alertas/{id}/ubicaciones` (con internet) o en el lote de sincronización (tipo UBICACION, lo que se juntó sin
 * señal). Se guardan en `ubicacion_alerta` sin repetir (alerta, hora y fuente), y la consola y el enlace las reciben
 * en tiempo real porque [com.senderoseguro.server.tiemporeal.CentroTiempoReal] lee esa tabla.
 *
 * Si la alerta llegó antes por SMS o por la malla, en el servidor tiene otro UUID con el mismo inicio (id corto), y
 * también se encuentra.
 */
class UbicacionesAlerta(private val dataSource: DataSource) {

    /** `POST /alertas/{id}/ubicaciones`. */
    fun agregar(usuarioId: UUID, alertaRuta: String, lote: LoteUbicaciones): UbicacionesAgregadas {
        val idTelefono = runCatching { UUID.fromString(alertaRuta) }.getOrNull() ?: throw noEncontrada()
        val lecturas = validar(lote)
        return dataSource.transaccion { conn ->
            val (alertaId, estado) = buscar(conn, usuarioId, idTelefono) ?: throw noEncontrada()
            if (estado in TERMINADAS) throw cerrada(estado)
            guardar(conn, alertaId, lecturas)
        }
    }

    /** Manejador de UBICACION en `/sync/lote`. Los `datos` son `{ alertaId, lecturas }`. */
    val manejadorSync = ManejadorSync { conn, contexto, elemento ->
        val datos = runCatching { json.decodeFromJsonElement<UbicacionesSync>(elemento.datos) }.getOrNull()
            ?: return@ManejadorSync ResultadoManejo.Rechazado("VALIDACION", "Los datos necesitan alertaId y lecturas")
        val idTelefono = runCatching { UUID.fromString(datos.alertaId) }.getOrNull()
            ?: return@ManejadorSync ResultadoManejo.Rechazado("VALIDACION", "alertaId no es un UUID")
        val lecturas = try {
            validar(LoteUbicaciones(datos.lecturas))
        } catch (e: ApiException) {
            return@ManejadorSync ResultadoManejo.Rechazado(e.codigo, e.message ?: e.codigo)
        }
        // La alerta puede llegar después (por ejemplo, por SMS): el teléfono lo vuelve a intentar.
        val (alertaId, estado) = buscar(conn, contexto.usuarioId, idTelefono)
            ?: return@ManejadorSync ResultadoManejo.Rechazado("NO_ENCONTRADO", "La alerta todavía no está registrada", temporal = true)
        if (estado in TERMINADAS) {
            return@ManejadorSync ResultadoManejo.Rechazado("CONFLICTO", "La alerta ya terminó ($estado)")
        }
        val r = guardar(conn, alertaId, lecturas)
        if (r.insertadas > 0) ResultadoManejo.Aceptado(referencia = alertaId.toString())
        else ResultadoManejo.Duplicado(referencia = alertaId.toString())
    }

    /** La alerta de la persona con ese UUID o, si llegó por SMS o malla, con su mismo id corto (últimas 72 h). */
    private fun buscar(conn: Connection, usuarioId: UUID, id: UUID): Pair<UUID, String>? =
        conn.consultarUno(
            "SELECT id, estado::text AS estado FROM alerta WHERE id = ? AND usuario_id = ?", id, usuarioId,
        ) { it.uuid("id") to it.getString("estado") }
            ?: conn.consultarUno(
                """SELECT id, estado::text AS estado FROM alerta
                   WHERE usuario_id = ? AND id::text LIKE ? || '%' AND canal_recepcion IN ('SMS', 'MALLA')
                     AND registrada_en > now() - interval '72 hours'
                   ORDER BY registrada_en DESC LIMIT 1""",
                usuarioId, id.toString().take(8),
            ) { it.uuid("id") to it.getString("estado") }

    private fun guardar(conn: Connection, alertaId: UUID, lecturas: List<Lectura>): UbicacionesAgregadas {
        var insertadas = 0
        for (l in lecturas) {
            insertadas += conn.ejecutar(
                """INSERT INTO ubicacion_alerta (alerta_id, latitud, longitud, precision_m, velocidad_ms, rumbo_grados, bateria, fuente, capturada_en)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?::fuente_ubicacion, ?)
                   ON CONFLICT DO NOTHING""",
                alertaId, l.latitud, l.longitud, l.precisionM, l.velocidadMs, l.rumboGrados, l.bateria, l.fuente, l.capturadaEn,
            )
        }
        return UbicacionesAgregadas(insertadas, lecturas.size - insertadas)
    }

    private data class Lectura(
        val latitud: Double, val longitud: Double, val precisionM: Double?, val velocidadMs: Double?,
        val rumboGrados: Int?, val bateria: Int?, val fuente: String, val capturadaEn: Instant,
    )

    private fun validar(lote: LoteUbicaciones): List<Lectura> {
        if (lote.lecturas.isEmpty() || lote.lecturas.size > MAX_LECTURAS) {
            throw errorValidacion(mapOf("lecturas" to "Deben ser de 1 a $MAX_LECTURAS"))
        }
        val ahora = Instant.now()
        val errores = mutableMapOf<String, String>()
        val lecturas = lote.lecturas.mapIndexedNotNull { i, u ->
            val campo = "lecturas[$i]"
            val hora = try { Instant.parse(u.capturadaEn) } catch (_: DateTimeParseException) { null }
            val fuente = u.fuente ?: "GNSS"
            when {
                u.latitud !in -90.0..90.0 || u.longitud !in -180.0..180.0 -> errores[campo] = "Coordenadas fuera de rango"
                hora == null -> errores["$campo.capturadaEn"] = "Debe ser una fecha ISO 8601"
                fuente !in FUENTES -> errores["$campo.fuente"] = "Debe ser ${FUENTES.joinToString()}"
                (u.precisionM ?: 0.0) < 0 || (u.velocidadMs ?: 0.0) < 0 -> errores[campo] = "Precisión y velocidad no pueden ser negativas"
                u.rumboGrados != null && u.rumboGrados !in 0..359 -> errores["$campo.rumboGrados"] = "Debe estar entre 0 y 359"
                u.bateria != null && u.bateria !in 0..100 -> errores["$campo.bateria"] = "Debe estar entre 0 y 100"
                else -> return@mapIndexedNotNull Lectura(
                    u.latitud, u.longitud, u.precisionM, u.velocidadMs, u.rumboGrados, u.bateria, fuente,
                    // Al milisegundo, y si el reloj del teléfono va adelantado, ahora.
                    Instant.ofEpochMilli(minOf(hora, ahora).toEpochMilli()),
                )
            }
            null
        }
        if (errores.isNotEmpty()) throw errorValidacion(errores)
        return lecturas
    }

    private fun noEncontrada() = ApiException(HttpStatusCode.NotFound, "NO_ENCONTRADO", "La alerta no existe")

    private fun cerrada(estado: String) = ApiException(HttpStatusCode.Conflict, "CONFLICTO", "La alerta ya terminó ($estado)")

    companion object {
        const val MAX_LECTURAS = 100
        private val FUENTES = setOf("GNSS", "WIFI", "CELULAR", "ULTIMA_CONOCIDA")
        private val TERMINADAS = setOf("ATENDIDA", "CERRADA", "CANCELADA", "EXPIRADA")
        private val json = Json { ignoreUnknownKeys = true }
    }
}
