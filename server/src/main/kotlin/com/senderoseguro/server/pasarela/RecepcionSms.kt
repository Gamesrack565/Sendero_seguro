package com.senderoseguro.server.pasarela

import com.senderoseguro.server.dispersion.AlertasRecibidas
import com.senderoseguro.server.dispersion.Dispersion
import com.senderoseguro.server.plataforma.consultar
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.transaccion
import com.senderoseguro.server.plataforma.uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.time.Duration as Plazo

/** Una alerta que llegó por SMS, ya validada: la cuenta que la mandó y lo que traía el `SS1`. */
data class AlertaSms(
    val mensajeId: Long,
    val usuarioId: UUID,
    val datos: DatosSs1,
)

/**
 * Registro de las alertas que llegan por SMS. Lo implementa quien registra alertas (Módulo 2, P4): inserta
 * `alerta` con `canal_recepcion = SMS`, su primera `ubicacion_alerta` y su primer `evento_alerta`, en la
 * transacción [conn] que recibe, y regresa el id. El id debe empezar con [DatosSs1.idCorto], para que la misma
 * alerta se reconozca si después llega por internet o por sincronización (contrato, decisión 2).
 * No llama a la dispersión: la pasarela la despacha cuando se confirma la transacción.
 */
fun interface RegistroAlertasSms {
    fun registrar(conn: Connection, sms: AlertaSms): UUID
}

/**
 * Pasarela de entrada del SMS de contingencia (RF-16, CU-25, S-06). Guarda cada mensaje en `mensaje_sms`, válido
 * o no. Es válido si es un `SS1` correcto, de una hora razonable, y si el número de origen es el `telefono_movil`
 * de una cuenta ACTIVA. Un mensaje válido de una alerta que ya existe (mismo dueño, mismo id corto y hora ±60 s)
 * se liga a ella; si no existe, se registra con [registro] y se despacha.
 *
 * Mientras no haya [registro], los mensajes válidos quedan pendientes (`procesado_en` nulo) y se registran
 * en cuanto exista.
 */
class RecepcionSms(
    private val dataSource: DataSource,
    private val dispersion: Dispersion,
    private val registro: RegistroAlertasSms?,
) {
    data class Recibido(val mensajeId: Long?, val valido: Boolean, val alertaId: UUID?)

    private val log = LoggerFactory.getLogger(RecepcionSms::class.java)

    /** Revisa cada cierto tiempo los mensajes válidos que quedaron sin registrar (por ejemplo, si falló el registro). */
    fun iniciar(scope: CoroutineScope, cada: Plazo) {
        if (registro == null) return
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    procesarPendientes()
                } catch (e: Exception) {
                    log.error("Falló la revisión de SMS pendientes", e)
                }
                delay(cada)
            }
        }
    }

    /** Lo que llamó el webhook: guarda el mensaje y, si es válido, registra o liga la alerta. */
    fun recibir(numero: String, texto: String): Recibido {
        val digitos = numero.filter { it.isDigit() }
        if (digitos.length !in 10..15) {
            // La tabla solo acepta números de 10 a 15 dígitos; un remitente de texto o un código corto no es una cuenta.
            log.warn("SMS ignorado: el remitente '{}' no es un número de teléfono", numero.take(20))
            return Recibido(null, false, null)
        }
        val origen = if (numero.trim().startsWith("+")) "+$digitos" else digitos
        val contenido = texto.trim().take(160)
        val decodificado = Ss1.decodificar(contenido)
        val usuario = decodificado.getOrNull()?.let { dataSource.connection.use { conn -> cuenta(conn, origen) } }
        val motivo = when {
            decodificado.isFailure -> decodificado.exceptionOrNull()?.message
            usuario == null -> "El número no es de una cuenta activa"
            else -> horaFueraDeRango(decodificado.getOrThrow().activadaEn)
        }
        val valido = motivo == null
        val id = dataSource.connection.use { conn ->
            conn.consultarUno(
                """INSERT INTO mensaje_sms (numero_origen, contenido, valido, procesado_en)
                   VALUES (?, ?, ?, CASE WHEN ? THEN NULL ELSE now() END) RETURNING id""",
                origen, contenido, valido, valido,
            ) { it.getLong("id") }!!
        }
        if (!valido) {
            log.info("SMS {} de {} no válido: {}", id, origen, motivo)
            return Recibido(id, false, null)
        }
        val alertaId = try {
            procesar(id)
        } catch (e: Exception) {
            log.error("No se pudo registrar la alerta del SMS {}; queda pendiente", id, e)
            null
        }
        return Recibido(id, true, alertaId)
    }

    /** Registra los mensajes válidos pendientes de las últimas 24 horas. Regresa cuántos ligó a una alerta. */
    fun procesarPendientes(): Int {
        val ids = dataSource.connection.use { conn ->
            conn.consultar(
                """SELECT id FROM mensaje_sms WHERE procesado_en IS NULL AND valido AND recibido_en > now() - interval '24 hours'
                   ORDER BY recibido_en LIMIT 50""",
            ) { it.getLong("id") }
        }
        return ids.count { id ->
            try {
                procesar(id) != null
            } catch (e: Exception) {
                log.error("No se pudo registrar la alerta del SMS {}", id, e)
                false
            }
        }
    }

    /** Liga el mensaje a su alerta, o la registra y la despacha. Regresa null si queda pendiente. */
    private fun procesar(mensajeId: Long): UUID? {
        var nueva = false
        val alertaId = dataSource.transaccion { conn ->
            val (numero, contenido) = conn.consultarUno(
                """SELECT numero_origen, contenido FROM mensaje_sms
                   WHERE id = ? AND valido AND procesado_en IS NULL FOR UPDATE SKIP LOCKED""",
                mensajeId,
            ) { it.getString("numero_origen") to it.getString("contenido") } ?: return@transaccion null
            val datos = Ss1.decodificar(contenido).getOrThrow()
            val usuarioId = cuenta(conn, numero) ?: run {
                // La cuenta se desactivó entre la llegada del SMS y su registro.
                conn.ejecutar("UPDATE mensaje_sms SET procesado_en = now() WHERE id = ?", mensajeId)
                return@transaccion null
            }
            // Dos SMS de la misma alerta (el teléfono reintenta) no deben registrarla dos veces.
            AlertasRecibidas.bloquear(conn, usuarioId)
            val existente = AlertasRecibidas.buscar(conn, usuarioId, datos.idCorto, datos.activadaEn)
            val id = existente ?: registro?.registrar(conn, AlertaSms(mensajeId, usuarioId, datos))?.also { nueva = true }
                ?: return@transaccion null
            conn.ejecutar("UPDATE mensaje_sms SET alerta_id = ?, procesado_en = now() WHERE id = ?", id, mensajeId)
            id
        }
        if (alertaId != null) {
            log.info("SMS {} {} la alerta {}", mensajeId, if (nueva) "registró" else "se ligó a", alertaId)
            if (nueva) dispersion.despachar(alertaId)
        }
        return alertaId
    }

    private fun cuenta(conn: Connection, numero: String): UUID? = conn.consultarUno(
        "SELECT id FROM usuario WHERE telefono_movil = ANY(?) AND estado = 'ACTIVA'",
        conn.createArrayOf("varchar", Telefonos.variantes(numero).toTypedArray()),
    ) { it.uuid("id") }

    /** Un SMS de hace más de un día, o de más de 10 minutos en el futuro, se toma como repetido o falso. */
    private fun horaFueraDeRango(activadaEn: Instant): String? {
        val ahora = Instant.now()
        return when {
            activadaEn.isBefore(ahora.minus(Duration.ofHours(24))) -> "La hora del mensaje tiene más de 24 horas"
            activadaEn.isAfter(ahora.plus(Duration.ofMinutes(10))) -> "La hora del mensaje está en el futuro"
            else -> null
        }
    }
}
