package com.senderoseguro.server.pasarela

import com.senderoseguro.server.avisos.EnvioAvisos
import com.senderoseguro.server.plataforma.ApiException
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/** Cómo se validan los webhooks del celular pasarela (SMS Gateway for Android). */
class ConfigSmsGate(
    /** Llave de firma de la app (Settings → Webhooks → Signing Key). Vacía: sin firma, solo se permite en desarrollo. */
    val llaveFirma: String,
    val modoDesarrollo: Boolean,
)

/**
 * Firma con la que la app llama a los webhooks: `X-Signature` es el HMAC-SHA256 en hexadecimal, con la llave de
 * firma, del cuerpo tal como llegó seguido de `X-Timestamp` (segundos Unix).
 */
object FirmaSmsGate {
    /** Diferencia máxima entre `X-Timestamp` y la hora del servidor, para que no sirva reenviar un aviso viejo. */
    const val TOLERANCIA_SEG = 300L

    fun calcular(llave: String, cuerpo: ByteArray, timestamp: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(llave.toByteArray(), "HmacSHA256")) }
        return mac.doFinal(cuerpo + timestamp.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun valida(llave: String, cuerpo: ByteArray, timestamp: String?, firma: String?, ahora: Instant = Instant.now()): Boolean {
        val segundos = timestamp?.toLongOrNull() ?: return false
        if (firma == null || abs(ahora.epochSecond - segundos) > TOLERANCIA_SEG) return false
        return MessageDigest.isEqual(calcular(llave, cuerpo, timestamp).toByteArray(), firma.trim().lowercase().toByteArray())
    }
}

private val log = LoggerFactory.getLogger("com.senderoseguro.server.pasarela.RutasSmsGate")

/**
 * Webhook del celular pasarela (P16), `POST /pasarela/smsgate`, con la firma `X-Signature`. Una sola ruta para
 * todos los eventos que se registren en la app:
 * - `sms:received`: un SMS que llegó al chip del celular. Pasa por la misma recepción que el de Twilio.
 * - `sms:sent`, `sms:delivered`, `sms:failed`: el estado de un SMS que mandó el servidor.
 * Lo demás (por ejemplo `system:ping`) se contesta 204 y se ignora. La app reintenta si no recibe un 2xx.
 */
fun Route.rutasSmsGate(recepcion: RecepcionSms, envio: EnvioAvisos, config: ConfigSmsGate) {
    post("/pasarela/smsgate") {
        val cuerpo = call.receive<ByteArray>()
        if (config.llaveFirma.isBlank()) {
            if (!config.modoDesarrollo) {
                throw ApiException(HttpStatusCode.ServiceUnavailable, "SIN_CONFIGURAR", "La pasarela SMS no tiene SMSGATE_LLAVE_FIRMA")
            }
        } else if (!FirmaSmsGate.valida(config.llaveFirma, cuerpo, call.request.headers["X-Timestamp"], call.request.headers["X-Signature"])) {
            log.warn("Webhook de SMS Gateway con firma inválida o vencida")
            throw ApiException(HttpStatusCode.Unauthorized, "FIRMA_INVALIDA", "La firma del proveedor no coincide")
        }

        val aviso = runCatching { Json.parseToJsonElement(cuerpo.decodeToString()) as JsonObject }.getOrNull()
        val datos = aviso?.get("payload") as? JsonObject
        val evento = aviso?.get("event")?.jsonPrimitive?.contentOrNull
        if (datos == null || evento == null) {
            throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "El aviso debe traer event y payload")
        }
        fun campo(nombre: String) = datos[nombre]?.jsonPrimitive?.contentOrNull
        val mensajeId = campo("messageId")

        when (evento) {
            "sms:received" -> {
                val r = recepcion.recibir(campo("sender").orEmpty(), campo("message").orEmpty())
                call.respond(SmsRecibido(r.mensajeId, r.valido, r.alertaId?.toString()))
                return@post
            }
            "sms:sent" -> mensajeId?.let(envio::aceptadoPorOperador)
            "sms:delivered" -> mensajeId?.let { envio.confirmarEntrega(it, entregada = true) }
            "sms:failed" -> mensajeId?.let {
                envio.confirmarEntrega(it, entregada = false, error = "SMS Gateway: no se pudo enviar (${campo("reason") ?: "sin detalle"})")
            }
        }
        call.respond(HttpStatusCode.NoContent)
    }
}
