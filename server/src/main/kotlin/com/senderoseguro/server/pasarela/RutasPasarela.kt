package com.senderoseguro.server.pasarela

import com.senderoseguro.server.avisos.EnvioAvisos
import com.senderoseguro.server.plataforma.ApiException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.accept
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/** Cómo se validan los webhooks de Twilio. */
class ConfigPasarela(
    /** Auth Token de la cuenta de Twilio. Vacío: sin firma, solo se permite en desarrollo. */
    val authToken: String,
    /**
     * Dirección pública con la que Twilio llama al servidor, hasta `/api/v1` (por ejemplo `https://<dominio>/api/v1`).
     * La firma se calcula sobre esa URL. Vacía: se usa la URL que llegó en la petición (sirve en local).
     */
    val urlPublica: String,
    val modoDesarrollo: Boolean,
)

@Serializable
data class SmsRecibido(val mensajeId: Long?, val valido: Boolean, val alertaId: String?)

private val log = LoggerFactory.getLogger("com.senderoseguro.server.pasarela.RutasPasarela")

/** Respuesta vacía en TwiML: Twilio no contesta nada a quien mandó el SMS. */
private const val TWIML_VACIO = """<?xml version="1.0" encoding="UTF-8"?><Response></Response>"""

/** Estados de Twilio que dicen que el SMS no llegó y que no tiene caso reintentar al mismo número. */
private val ERRORES_DEFINITIVOS = setOf("30004", "30005", "30006")

/**
 * Webhooks de Twilio (P16), con la firma `X-Twilio-Signature`:
 * - `POST /pasarela/sms/entrante`: un SMS que llegó al número de Twilio (`From`, `Body`). Siempre responde 200.
 * - `POST /pasarela/sms/estado`: el estado de un SMS que mandó el servidor (`MessageSid`, `MessageStatus`).
 */
fun Route.rutasPasarela(recepcion: RecepcionSms, envio: EnvioAvisos, config: ConfigPasarela) {
    post("/pasarela/sms/entrante") {
        val p = call.parametrosFirmados(config, "/pasarela/sms/entrante")
        val r = recepcion.recibir(p["From"].orEmpty(), p["Body"].orEmpty())
        // Twilio espera TwiML; las pruebas piden JSON para ver el resultado.
        if (call.request.accept()?.contains("application/json") == true) {
            call.respond(SmsRecibido(r.mensajeId, r.valido, r.alertaId?.toString()))
        } else {
            call.respondText(TWIML_VACIO, ContentType.Text.Xml)
        }
    }

    post("/pasarela/sms/estado") {
        val p = call.parametrosFirmados(config, "/pasarela/sms/estado")
        val sid = p["MessageSid"]
        val codigo = p["ErrorCode"]?.takeIf { it.isNotBlank() }
        if (sid != null) {
            when (val estado = p["MessageStatus"]) {
                "sent" -> envio.aceptadoPorOperador(sid)
                "delivered" -> envio.confirmarEntrega(sid, entregada = true)
                "undelivered", "failed" -> envio.confirmarEntrega(
                    sid, entregada = false, error = "Twilio: $estado${codigo?.let { " (error $it)" } ?: ""}",
                    definitivo = codigo in ERRORES_DEFINITIVOS,
                )
                else -> Unit   // queued, sending...: todavía no hay resultado
            }
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

/** Lee el formulario de Twilio y valida su firma. */
private suspend fun ApplicationCall.parametrosFirmados(config: ConfigPasarela, ruta: String): Map<String, String> {
    val formulario = receiveParameters()
    if (config.authToken.isBlank()) {
        if (!config.modoDesarrollo) {
            throw ApiException(HttpStatusCode.ServiceUnavailable, "SIN_CONFIGURAR", "La pasarela SMS no tiene TWILIO_AUTH_TOKEN")
        }
    } else {
        val url = config.urlPublica.takeIf { it.isNotBlank() }?.let { it.trimEnd('/') + ruta }
            ?: "http://${request.headers["Host"]}${request.uri}"
        val parametros = formulario.names().associateWith { formulario.getAll(it).orEmpty() }
        if (!FirmaTwilio.valida(config.authToken, url, parametros, request.headers["X-Twilio-Signature"])) {
            log.warn("Webhook {} con firma inválida (URL usada para validar: {})", ruta, url)
            throw ApiException(HttpStatusCode.Unauthorized, "FIRMA_INVALIDA", "La firma del proveedor no coincide")
        }
    }
    return formulario.names().associateWith { formulario[it].orEmpty() }
}
