package com.senderoseguro.server.avisos

import com.senderoseguro.server.pasarela.Telefonos
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.UUID
import kotlin.time.Duration as Plazo

/**
 * SMS por un celular Android con la app SMS Gateway for Android (https://docs.sms-gate.app), en su modo nube: el
 * servidor deja el mensaje en `api.sms-gate.app` y el celular lo manda con su chip. Se autentica con el usuario y la
 * contraseña que muestra la app; la contraseña es un secreto y solo va en la variable SMSGATE_PASSWORD.
 *
 * Cada intento lleva su propio id y una [vigencia] menor al plazo de confirmación: si el celular está sin internet,
 * el mensaje vence en la nube antes de que el servidor lo reintente, y no sale dos veces.
 *
 * Con [confirmaEntrega] (los webhooks `sms:sent`, `sms:delivered` y `sms:failed` registrados hacia
 * `/pasarela/smsgate`), el aviso pasa a ENTREGADA cuando llega. Sin ella, un SMS aceptado se queda ENVIADA.
 */
class ProveedorSmsGate(
    private val usuario: String,
    private val password: String,
    private val vigencia: Plazo,
    override val confirmaEntrega: Boolean,
    private val urlApi: String = "https://api.sms-gate.app",
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : Proveedor {
    override val nombre = "smsgate"

    override fun enviar(m: Mensaje): ResultadoEnvio {
        val id = "ss${m.notificacionId}-" + UUID.randomUUID().toString().take(8)
        val cuerpo = buildJsonObject {
            put("id", id)
            putJsonObject("textMessage") { put("text", m.texto) }
            putJsonArray("phoneNumbers") { add(Telefonos.e164(m.destino)) }
            put("ttl", vigencia.inWholeSeconds)
        }
        val respuesta = try {
            http.send(
                HttpRequest.newBuilder(URI.create(urlApi.trimEnd('/') + "/3rdparty/v1/messages"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("$usuario:$password".toByteArray()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(cuerpo.toString()))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        } catch (e: IOException) {
            return ResultadoEnvio.Fallo("No se pudo conectar con SMS Gateway: ${e.message}")
        }
        val json = runCatching { Json.parseToJsonElement(respuesta.body()).jsonObject }.getOrNull()
        if (respuesta.statusCode() in 200..299) {
            // Los webhooks de estado traen el id con que quedó el mensaje: el nuestro, salvo que la nube lo cambie.
            return ResultadoEnvio.Aceptado(json?.get("id")?.jsonPrimitive?.contentOrNull ?: id)
        }
        val texto = "SMS Gateway ${respuesta.statusCode()}: " +
            (json?.get("message")?.jsonPrimitive?.contentOrNull ?: respuesta.body().take(200))
        return when (respuesta.statusCode()) {
            // Lo único que cambia de un aviso a otro es el número: un 400 es un número que no sirve.
            400 -> ResultadoEnvio.Fallo(texto, destinoInvalido = true)
            429 -> ResultadoEnvio.Fallo(texto, esperarSeg = esperaPedida(respuesta, porDefecto = 10))
            // Cola llena: el celular lleva rato sin conectarse. Se espera sin gastar reintentos.
            503 -> ResultadoEnvio.Fallo(texto, esperarSeg = esperaPedida(respuesta, porDefecto = 60))
            else -> ResultadoEnvio.Fallo(texto)   // 401 (usuario o contraseña), 5xx...: se reintenta
        }
    }

    private companion object {
        /** Lo que pide el encabezado Retry-After (en segundos), entre 1 s y 5 min. */
        fun esperaPedida(r: HttpResponse<*>, porDefecto: Long): Long =
            r.headers().firstValue("Retry-After").map { it.trim().toLongOrNull() }.orElse(null)?.coerceIn(1, 300) ?: porDefecto
    }
}
