package com.senderoseguro.server.avisos

import com.senderoseguro.server.pasarela.Telefonos
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * SMS por Twilio (Programmable Messaging). Se autentica con el Account SID y el Auth Token de la cuenta; el
 * Auth Token es un secreto y solo se pone en la variable TWILIO_AUTH_TOKEN.
 *
 * Si hay [urlEstado] (la dirección pública de `/pasarela/sms/estado`), Twilio avisa ahí cómo va cada SMS y el
 * aviso pasa a ENTREGADA cuando llega. Sin ella, un SMS aceptado se queda ENVIADA, como el push.
 */
class ProveedorTwilio(
    private val cuenta: String,
    private val authToken: String,
    /** Número de Twilio que manda los SMS, en formato internacional (`+1…`). */
    private val numero: String,
    private val urlEstado: String? = null,
    private val urlApi: String = "https://api.twilio.com",
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : Proveedor {
    override val nombre = "twilio"
    override val confirmaEntrega = urlEstado != null

    override fun enviar(m: Mensaje): ResultadoEnvio {
        val campos = buildMap {
            put("To", Telefonos.e164(m.destino))
            put("From", numero)
            put("Body", m.texto)
            urlEstado?.let { put("StatusCallback", it) }
        }
        val formulario = campos.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, Charsets.UTF_8) }
        val respuesta = try {
            http.send(
                HttpRequest.newBuilder(URI.create("$urlApi/2010-04-01/Accounts/$cuenta/Messages.json"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("$cuenta:$authToken".toByteArray()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formulario))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        } catch (e: IOException) {
            return ResultadoEnvio.Fallo("No se pudo conectar con Twilio: ${e.message}")
        }
        val json = runCatching { Json.parseToJsonElement(respuesta.body()).jsonObject }.getOrNull()
        fun campo(nombre: String) = json?.get(nombre)?.jsonPrimitive?.contentOrNull
        if (respuesta.statusCode() in 200..299) {
            return ResultadoEnvio.Aceptado(campo("sid"))
        }
        val codigo = campo("code")
        val texto = "Twilio ${respuesta.statusCode()} ${codigo ?: ""}: ${campo("message") ?: respuesta.body().take(200)}"
        return when {
            respuesta.statusCode() == 429 -> ResultadoEnvio.Fallo(texto, esperarSeg = esperaPedida(respuesta))
            codigo in NUMERO_INVALIDO -> ResultadoEnvio.Fallo(texto, destinoInvalido = true)
            codigo in CUENTA_NO_PERMITE -> ResultadoEnvio.Fallo(texto, definitivo = true)
            else -> ResultadoEnvio.Fallo(texto)   // 401, 5xx, permisos de país...: se reintenta
        }
    }

    private companion object {
        /** Lo que pide el encabezado Retry-After (en segundos), entre 1 s y 5 min; 10 s si no viene. */
        fun esperaPedida(r: HttpResponse<*>): Long =
            r.headers().firstValue("Retry-After").map { it.trim().toLongOrNull() }.orElse(null)?.coerceIn(1, 300) ?: 10

        /**
         * El número no sirve para SMS: 21211 no es válido, 21614 no es celular, 21610 pidió no recibir (STOP) y
         * 21608 y 572002 no está verificado en una cuenta de prueba.
         */
        val NUMERO_INVALIDO = setOf("21211", "21608", "21610", "21614", "572002")

        /** La cuenta de prueba "Limited trial" solo manda las plantillas de Twilio, no un texto propio (572006). */
        val CUENTA_NO_PERMITE = setOf("572006")
    }
}
