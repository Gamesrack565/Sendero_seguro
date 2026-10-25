package com.senderoseguro.server.avisos

import com.senderoseguro.server.dispersion.EnlacesServicio
import com.senderoseguro.server.plataforma.consultar
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.transaccion
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
import java.util.UUID
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Entrega los avisos de `notificacion` (RF-8, RF-9) siguiendo E-03:
 *
 * - PENDIENTE: se elige el canal. PUSH si la cuenta tiene un teléfono con token FCM; si no, SMS a su número.
 *   SMS y WHATSAPP van al teléfono (WhatsApp sale por SMS mientras no haya proveedor de WhatsApp).
 * - Envío al proveedor: si lo acepta, ENVIADA (o ENTREGADA si ya confirma la entrega).
 * - Error, o ENVIADA sin confirmación dentro del plazo: FALLIDA, se cuenta el reintento y se espera
 *   [Politica.esperaBase] × 2^(reintentos − 1) antes de volver a intentar.
 * - Al llegar a [Politica.maxReintentos] (o con un error que no tiene caso reintentar): DESCARTADA y se avisa
 *   a los Administradores con el evento ENTREGA_DESCARTADA de la alerta, que la consola recibe por WebSocket.
 *
 * Varios servidores pueden correrlo a la vez: cada vuelta aparta sus filas con `FOR UPDATE SKIP LOCKED`.
 */
class EnvioAvisos(
    private val dataSource: DataSource,
    private val push: Proveedor,
    private val sms: Proveedor,
    private val enlaces: EnlacesServicio,
    private val politica: Politica = Politica(),
    /** Dirección de la página de seguimiento para la red de apoyo, sin el token. Null: el aviso va sin enlace. */
    private val urlEnlace: String? = null,
) {
    data class Politica(
        val maxReintentos: Int = 5,
        val esperaBase: Duration = 15.seconds,
        /** Cuánto se espera la confirmación de entrega de un proveedor que la manda. */
        val plazoConfirmacion: Duration = 10.minutes,
        /** Un aviso más viejo que esto ya no sirve (por ejemplo, si el servidor estuvo apagado): se descarta sin enviarlo. */
        val vigencia: Duration = 24.hours,
        val lote: Int = 50,
    )

    private val log = LoggerFactory.getLogger(EnvioAvisos::class.java)

    fun iniciar(scope: CoroutineScope, cada: Duration) {
        scope.launch(Dispatchers.IO) {
            var ultimaRevision = 0L
            while (isActive) {
                try {
                    val n = procesarPendientes()
                    if (n > 0) log.info("Avisos: {} procesados", n)
                    if (System.nanoTime() - ultimaRevision > 60.seconds.inWholeNanoseconds) {
                        vencerSinConfirmacion()
                        ultimaRevision = System.nanoTime()
                    }
                } catch (e: Exception) {
                    log.error("Falló la vuelta del envío de avisos", e)
                }
                delay(cada)
            }
        }
    }

    /** Aparta los avisos que toca enviar y los envía. Regresa cuántos procesó. */
    fun procesarPendientes(): Int {
        // Se apartan por 2 minutos: si el servidor se cae a la mitad, otro los toma después.
        val ids = dataSource.transaccion { conn ->
            conn.consultar(
                """UPDATE notificacion n SET proximo_reintento_en = now() + interval '2 minutes'
                   FROM (SELECT id FROM notificacion
                         WHERE estado IN ('PENDIENTE', 'FALLIDA') AND (proximo_reintento_en IS NULL OR proximo_reintento_en <= now())
                         ORDER BY alerta_id IS NULL, id LIMIT ? FOR UPDATE SKIP LOCKED) x
                   WHERE n.id = x.id
                   RETURNING n.id""",
                politica.lote,
            ) { it.getLong("id") }.sorted()
        }
        ids.forEach { id ->
            try {
                enviar(id)
            } catch (e: Exception) {
                log.error("Falló el envío del aviso {}", id, e)
            }
        }
        return ids.size
    }

    /** Pasa a FALLIDA los envíos que no recibieron confirmación a tiempo, para reintentarlos (E-03). */
    fun vencerSinConfirmacion(): Int = dataSource.transaccion { conn ->
        val vencidos = conn.consultar(
            """SELECT id FROM notificacion WHERE estado = 'ENVIADA' AND proximo_reintento_en <= now()
               FOR UPDATE SKIP LOCKED""",
        ) { it.getLong("id") }
        vencidos.forEach { fallo(conn, it, "Sin confirmación de entrega", definitivo = false) }
        vencidos.size
    }

    /**
     * Confirmación del proveedor (webhook de estado del SMS). Regresa false si no hay un aviso con ese id del
     * proveedor. Una confirmación repetida o que llega tarde no cambia un aviso ya ENTREGADO o DESCARTADO.
     */
    fun confirmarEntrega(proveedorMensajeId: String, entregada: Boolean, error: String? = null): Boolean =
        dataSource.transaccion { conn ->
            val aviso = conn.consultarUno(
                "SELECT id, estado::text AS estado FROM notificacion WHERE proveedor_mensaje_id = ? FOR UPDATE",
                proveedorMensajeId,
            ) { it.getLong("id") to it.getString("estado") } ?: return@transaccion false
            val (id, estado) = aviso
            if (estado !in setOf("ENVIADA", "FALLIDA")) return@transaccion true
            if (entregada) {
                conn.ejecutar(
                    """UPDATE notificacion SET estado = 'ENTREGADA', entregada_en = now(), proximo_reintento_en = NULL,
                              ultimo_error = NULL WHERE id = ?""",
                    id,
                )
            } else if (estado == "ENVIADA") {
                fallo(conn, id, error ?: "El proveedor no pudo entregarlo", definitivo = false)
            }
            true
        }

    private fun enviar(id: Long) {
        val a = dataSource.connection.use { leer(it, id) } ?: return
        if (a.estado !in setOf("PENDIENTE", "FALLIDA")) return

        if (a.antiguedadSeg > politica.vigencia.inWholeSeconds) {
            return dataSource.transaccion { descartar(it, a, a.reintentos, "Aviso vencido: tiene más de ${politica.vigencia}", avisar = false) }
        }
        // Lo que es solo para atender la alerta ya no sirve si la alerta terminó.
        if (a.estadoAlerta in setOf("CERRADA", "CANCELADA") && a.tipo in SOLO_ALERTA_ACTIVA) {
            return dataSource.transaccion { descartar(it, a, a.reintentos, "La alerta ya terminó", avisar = false) }
        }

        // E-03, PENDIENTE: elegir canal.
        val mensaje = when {
            a.canal == "PUSH" && a.tokenFcm != null -> {
                val (titulo, texto) = Textos.push(a.contexto)
                Mensaje(id, "PUSH", a.tokenFcm, titulo, texto, a.datosPush)
            }
            a.canal == "PUSH" && a.tipo == "ALERTA_INFORMATIVA" ->
                return dataSource.transaccion {
                    descartar(it, a, a.reintentos, "Sin teléfono con push; la alerta informativa se ve en la consola", avisar = false)
                }
            else -> {
                val telefono = a.telefonoContacto ?: a.telefonoCuenta
                    ?: return dataSource.transaccion { descartar(it, a, a.reintentos, "Sin teléfono para el SMS", avisar = true) }
                Mensaje(id, "SMS", telefono, "Sendero Seguro", Textos.sms(a.contexto))
            }
        }
        val proveedor = if (mensaje.canal == "PUSH") push else sms
        val resultado = try {
            proveedor.enviar(mensaje)
        } catch (e: Exception) {
            ResultadoEnvio.Fallo(e.message ?: e.javaClass.simpleName)
        }

        dataSource.transaccion { conn ->
            when (resultado) {
                is ResultadoEnvio.Aceptado -> {
                    val esperaConfirmacion = proveedor.confirmaEntrega && !resultado.entregado
                    conn.ejecutar(
                        """UPDATE notificacion
                           SET estado = ?::notificacion_estado, canal = ?::notificacion_canal, enviada_en = now(),
                               entregada_en = CASE WHEN ? THEN now() END, proveedor_mensaje_id = ?, ultimo_error = NULL,
                               proximo_reintento_en = CASE WHEN ? THEN now() + make_interval(secs => ?) END
                           WHERE id = ?""",
                        if (resultado.entregado) "ENTREGADA" else "ENVIADA", mensaje.canal, resultado.entregado,
                        resultado.proveedorMensajeId, esperaConfirmacion, politica.plazoConfirmacion.inWholeSeconds.toDouble(), id,
                    )
                }
                is ResultadoEnvio.Fallo -> {
                    conn.ejecutar("UPDATE notificacion SET canal = ?::notificacion_canal WHERE id = ?", mensaje.canal, id)
                    if (resultado.destinoInvalido && mensaje.canal == "PUSH") {
                        // El token ya no sirve: se borra y el siguiente intento elige otra vez canal (SMS), sin esperar.
                        conn.ejecutar("UPDATE dispositivo SET fcm_token = NULL WHERE fcm_token = ?", mensaje.destino)
                        fallo(conn, id, resultado.error, definitivo = false, sinEspera = true)
                    } else {
                        fallo(conn, id, resultado.error, resultado.definitivo || resultado.destinoInvalido)
                    }
                }
            }
        }
    }

    /** E-03, FALLIDA: cuenta el reintento y programa el siguiente, o descarta si ya no quedan. */
    private fun fallo(conn: Connection, id: Long, error: String, definitivo: Boolean, sinEspera: Boolean = false) {
        val a = leer(conn, id) ?: return
        val reintentos = a.reintentos + 1
        if (definitivo || reintentos >= politica.maxReintentos) {
            descartar(conn, a, reintentos, error, avisar = true)
            return
        }
        val espera = if (sinEspera) Duration.ZERO else politica.esperaBase * (1 shl (reintentos - 1))
        conn.ejecutar(
            """UPDATE notificacion SET estado = 'FALLIDA', reintentos = ?, ultimo_error = ?,
                      proximo_reintento_en = now() + make_interval(secs => ?) WHERE id = ?""",
            reintentos, error.take(500), espera.inWholeMilliseconds / 1000.0, id,
        )
        log.warn("Aviso {} ({} por {}) falló, reintento {} de {}: {}", id, a.tipo, a.canal, reintentos, politica.maxReintentos, error)
    }

    /** E-03, DESCARTADA: ya no se intenta. Con [avisar], los Administradores lo ven en la historia de la alerta. */
    private fun descartar(conn: Connection, a: Aviso, reintentos: Int, error: String, avisar: Boolean) {
        conn.ejecutar(
            """UPDATE notificacion SET estado = 'DESCARTADA', reintentos = ?, ultimo_error = ?, proximo_reintento_en = NULL
               WHERE id = ?""",
            reintentos, error.take(500), a.id,
        )
        if (!avisar) return
        log.warn("Aviso {} ({} por {}) descartado: {}", a.id, a.tipo, a.canal, error)
        val alertaId = a.alertaId ?: return
        conn.ejecutar(
            """INSERT INTO evento_alerta (alerta_id, estado_anterior, estado_nuevo, detalle)
               SELECT id, estado, estado, ?::jsonb FROM alerta WHERE id = ?""",
            buildJsonObject {
                put("accion", "ENTREGA_DESCARTADA")
                put("notificacionId", a.id)
                put("tipo", a.tipo)
                put("canal", a.canal)
                put("error", error.take(200))
            }.toString(),
            alertaId,
        )
    }

    /** Un aviso con lo necesario para elegir canal y redactarlo. */
    private inner class Aviso(
        val id: Long,
        val tipo: String,
        val canal: String,
        val estado: String,
        val reintentos: Int,
        val antiguedadSeg: Long,
        val alertaId: UUID?,
        val estadoAlerta: String?,
        val tokenFcm: String?,
        val telefonoCuenta: String?,
        val telefonoContacto: String?,
        val contexto: Contexto,
    ) {
        val datosPush: Map<String, String>
            get() = buildMap {
                put("tipo", tipo)
                alertaId?.let { put("alertaId", it.toString()) }
                contexto.folio?.let { put("folio", it) }
            }
    }

    private fun leer(conn: Connection, id: Long): Aviso? = conn.consultarUno(
        """SELECT n.id, n.tipo::text AS tipo, n.canal::text AS canal, n.estado::text AS estado, n.reintentos, n.alerta_id,
                  extract(epoch FROM now() - n.creada_en)::bigint AS antiguedad,
                  n.usuario_id = a.usuario_id AS para_emisor,
                  a.folio, a.estado::text AS estado_alerta, a.nivel_escalamiento,
                  -- El estado al que cambió la alerta cuando se creó el aviso (se guardan en la misma transacción).
                  (SELECT ev.estado_nuevo::text FROM evento_alerta ev
                   WHERE ev.alerta_id = n.alerta_id AND ev.ocurrido_en <= n.creada_en
                     AND ev.estado_anterior IS DISTINCT FROM ev.estado_nuevo
                   ORDER BY ev.id DESC LIMIT 1) AS estado_aviso,
                  split_part(e.nombre, ' ', 1) AS emisor_nombre, tv.nombre AS tipo_nombre,
                  du.rol::text AS rol, du.telefono_movil AS telefono_cuenta, c.telefono AS telefono_contacto,
                  (SELECT d.fcm_token FROM dispositivo d WHERE d.usuario_id = n.usuario_id AND d.fcm_token IS NOT NULL
                   ORDER BY d.ultimo_visto_en DESC NULLS LAST, d.creado_en DESC LIMIT 1) AS fcm,
                  NOT EXISTS (SELECT 1 FROM contacto_confianza cc
                              WHERE cc.usuario_id = a.usuario_id AND cc.nivel_acceso = 'SOLO_AVISO'
                                AND (cc.id = n.contacto_id OR cc.usuario_vinculado_id = n.usuario_id)) AS con_enlace
           FROM notificacion n
           LEFT JOIN alerta a ON a.id = n.alerta_id
           LEFT JOIN usuario e ON e.id = a.usuario_id
           LEFT JOIN tipo_incidente_version tv ON tv.version_catalogo_id = a.version_catalogo_id AND tv.tipo_incidente_id = a.tipo_incidente_id
           LEFT JOIN usuario du ON du.id = n.usuario_id
           LEFT JOIN contacto_confianza c ON c.id = n.contacto_id
           WHERE n.id = ?""",
        id,
    ) { rs ->
        val tipo = rs.getString("tipo")
        val alertaId = rs.uuidONulo("alerta_id")
        val enlace = if (tipo == "RED_APOYO" && rs.getBoolean("con_enlace") && urlEnlace != null && alertaId != null) {
            enlaces.tokenDeAlerta(alertaId)?.let { "${urlEnlace.trimEnd('/')}/$it" }
        } else null
        Aviso(
            id = rs.getLong("id"),
            tipo = tipo,
            canal = rs.getString("canal"),
            estado = rs.getString("estado"),
            reintentos = rs.getInt("reintentos"),
            antiguedadSeg = rs.getLong("antiguedad"),
            alertaId = alertaId,
            estadoAlerta = rs.getString("estado_alerta"),
            tokenFcm = rs.getString("fcm"),
            telefonoCuenta = rs.getString("telefono_cuenta"),
            telefonoContacto = rs.getString("telefono_contacto"),
            contexto = Contexto(
                tipo = tipo,
                folio = rs.getString("folio"),
                tipoIncidente = rs.getString("tipo_nombre"),
                emisorNombre = rs.getString("emisor_nombre"),
                estadoAlerta = rs.getString("estado_aviso") ?: rs.getString("estado_alerta"),
                nivelEscalamiento = rs.getInt("nivel_escalamiento").takeUnless { rs.wasNull() },
                paraEmisor = rs.getBoolean("para_emisor"),
                rolDestinatario = rs.getString("rol"),
                enlace = enlace,
            ),
        )
    }

    private companion object {
        /** Avisos para atender la alerta: si ya se cerró o canceló no se mandan. */
        val SOLO_ALERTA_ACTIVA = setOf("ALERTA_PRIORITARIA", "ALERTA_INFORMATIVA", "ESCALAMIENTO", "SIN_ACUSE")
    }
}
