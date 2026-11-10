package com.senderoseguro.server.cancelacion

import com.senderoseguro.server.cuentas.errorValidacion
import com.senderoseguro.server.dispersion.Dispersion
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.Bitacora
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.transaccion
import com.senderoseguro.server.plataforma.uuid
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import javax.sql.DataSource

/** Lo que manda la app al cancelar (o intentar cancelar) una alerta. `intentadoEn` es la hora del teléfono. */
@Serializable
data class CancelacionEntrada(
    val dispositivoId: String? = null,
    val motivo: String? = null,
    val metodo: String,
    val exitoso: Boolean,
    val intentadoEn: String? = null,
)

/**
 * P8 (Módulo 1, CU-21): cancelación de una alerta.
 *
 * - `exitoso = true` (PIN o huella correctos): la alerta pasa a CANCELADA y [Dispersion.cancelada] avisa a la
 *   unidad y a la red de apoyo, libera la unidad y apaga el enlace.
 * - `exitoso = false` (PIN que no coincide): se toma como posible coacción. La alerta SIGUE ACTIVA, queda
 *   `coaccion_detectada` y la consola lo ve en tiempo real. A quien la envió se le muestra como cancelada.
 * - Es idempotente: la app reintenta desde su cola sin señal, y el mismo intento (misma alerta y misma hora del
 *   teléfono) no se guarda dos veces. Si la alerta ya terminó, el intento se guarda y no cambia nada.
 */
class CancelacionServicio(private val dataSource: DataSource, private val dispersion: Dispersion) {

    fun registrar(usuarioId: UUID, alertaRuta: String, entrada: CancelacionEntrada, ip: String?) {
        val alertaId = runCatching { UUID.fromString(alertaRuta) }.getOrNull() ?: throw noEncontrada()
        val errores = buildMap {
            if (entrada.metodo !in METODOS) put("metodo", "Debe ser PIN o BIOMETRIA")
            if (entrada.motivo != null && entrada.motivo !in MOTIVOS) put("motivo", "Motivo desconocido")
        }
        if (errores.isNotEmpty()) throw errorValidacion(errores)
        val intentadoEn = hora(entrada.intentadoEn)

        val cancelada = dataSource.transaccion { conn ->
            val (dueno, estado) = conn.consultarUno(
                "SELECT usuario_id, estado::text AS estado FROM alerta WHERE id = ? FOR UPDATE", alertaId,
            ) { it.uuid("usuario_id") to it.getString("estado") } ?: throw noEncontrada()
            if (dueno != usuarioId) throw noEncontrada()

            val repetido = conn.consultarUno(
                "SELECT 1 FROM intento_cancelacion WHERE alerta_id = ? AND intentado_en = ? AND exitoso = ?",
                alertaId, intentadoEn, entrada.exitoso,
            ) { true } ?: false
            if (repetido) return@transaccion false

            // Solo se acepta un teléfono de la misma persona.
            val dispositivo = entrada.dispositivoId?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.takeIf { d ->
                conn.consultarUno("SELECT 1 FROM dispositivo WHERE id = ? AND usuario_id = ?", d, usuarioId) { true } != null
            }
            val activa = estado in ACTIVAS
            val coaccion = !entrada.exitoso && activa
            conn.ejecutar(
                """INSERT INTO intento_cancelacion (alerta_id, usuario_id, dispositivo_id, motivo, metodo, exitoso, es_coaccion, intentado_en)
                   VALUES (?, ?, ?, ?::cancelacion_motivo, ?::metodo_autenticacion, ?, ?, ?)""",
                alertaId, usuarioId, dispositivo, entrada.motivo, entrada.metodo, entrada.exitoso, coaccion, intentadoEn,
            )
            when {
                !activa -> {
                    false
                }
                entrada.exitoso -> {
                    conn.ejecutar(
                        """UPDATE alerta SET estado = 'CANCELADA', cancelada_en = now(), motivo_cancelacion = ?::cancelacion_motivo
                           WHERE id = ?""",
                        entrada.motivo, alertaId,
                    )
                    conn.ejecutar(
                        """INSERT INTO evento_alerta (alerta_id, estado_anterior, estado_nuevo, actor_id, detalle)
                           VALUES (?, ?::alerta_estado, 'CANCELADA', ?, ?::jsonb)""",
                        alertaId, estado, usuarioId,
                        buildJsonObject { put("motivo", "CANCELACION"); entrada.motivo?.let { put("motivoCancelacion", it) }; put("metodo", entrada.metodo) }.toString(),
                    )
                    Bitacora.registrar(conn, "CANCELAR_ALERTA", usuarioId, "alerta", alertaId.toString(),
                        buildJsonObject { put("metodo", entrada.metodo); entrada.motivo?.let { put("motivo", it) } }, ip)
                    true
                }
                else -> {
                    // Posible coacción: la alerta sigue y la consola lo ve (evento sin cambio de estado).
                    conn.ejecutar("UPDATE alerta SET coaccion_detectada = true WHERE id = ?", alertaId)
                    conn.ejecutar(
                        """INSERT INTO evento_alerta (alerta_id, estado_anterior, estado_nuevo, actor_id, detalle)
                           VALUES (?, ?::alerta_estado, ?::alerta_estado, ?, '{"coaccion":true}'::jsonb)""",
                        alertaId, estado, estado, usuarioId,
                    )
                    Bitacora.registrar(conn, "POSIBLE_COACCION", usuarioId, "alerta", alertaId.toString(),
                        buildJsonObject { put("metodo", entrada.metodo) }, ip)
                    false
                }
            }
        }
        // Avisos, unidades y enlace, después del commit (como pide Dispersion).
        if (cancelada) dispersion.cancelada(alertaId)
    }

    /** La hora del teléfono; si no llega, es inválida o es del futuro, ahora. Se guarda al milisegundo. */
    private fun hora(valor: String?): Instant {
        val ahora = Instant.now()
        val h = try { valor?.let(Instant::parse) } catch (_: DateTimeParseException) { null } ?: return ahora.truncadoMs()
        return (if (h.isAfter(ahora)) ahora else h).truncadoMs()
    }

    private fun Instant.truncadoMs() = Instant.ofEpochMilli(toEpochMilli())

    private fun noEncontrada() = ApiException(HttpStatusCode.NotFound, "NO_ENCONTRADO", "La alerta no existe")

    private companion object {
        val METODOS = setOf("PIN", "BIOMETRIA")
        val MOTIVOS = setOf("YA_ESTOY_A_SALVO", "FALSA_ALARMA", "ACTIVACION_ACCIDENTAL")
        val ACTIVAS = setOf("CREADA", "EN_CUENTA_REGRESIVA", "PENDIENTE_ENVIO", "ENVIADA", "ASIGNADA", "ESCALADA", "ACUSADA", "EN_ATENCION")
    }
}
