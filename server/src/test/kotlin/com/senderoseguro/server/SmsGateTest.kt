package com.senderoseguro.server

import com.senderoseguro.server.avisos.EnvioAvisos
import com.senderoseguro.server.avisos.Mensaje
import com.senderoseguro.server.avisos.ProveedorSimulado
import com.senderoseguro.server.avisos.ProveedorSmsGate
import com.senderoseguro.server.avisos.ResultadoEnvio
import com.senderoseguro.server.desarrollo.AlertasPrueba
import com.senderoseguro.server.desarrollo.RegistroSmsPrueba
import com.senderoseguro.server.pasarela.ConfigSmsGate
import com.senderoseguro.server.pasarela.DatosSs1
import com.senderoseguro.server.pasarela.FirmaSmsGate
import com.senderoseguro.server.pasarela.RecepcionSms
import com.senderoseguro.server.pasarela.Ss1
import com.senderoseguro.server.pasarela.rutasSmsGate
import com.senderoseguro.server.plataforma.configurarErrores
import com.senderoseguro.server.plataforma.configurarSerializacion
import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.AfterAll
import java.net.InetSocketAddress
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Celular Android como pasarela SMS: envío por la nube de la app (servidor falso) y su webhook firmado. */
class SmsGateTest : PruebaConBaseDatos() {

    private val llave = "llave-de-firma-prueba"
    private val numeroAna = "+525510000001"

    // --- Envío ---

    private val servidor = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val cuerpos = mutableListOf<String>()

    init {
        servidor.createContext("/3rdparty/v1/messages") { ex ->
            val cuerpo = String(ex.requestBody.readAllBytes())
            cuerpos += cuerpo
            val numero = Json.parseToJsonElement(cuerpo).jsonObject["phoneNumbers"]!!.jsonArray[0].jsonPrimitive.content
            val esperado = "Basic " + Base64.getEncoder().encodeToString("usuario:clave".toByteArray())
            val (codigo, respuesta) = when {
                ex.requestHeaders.getFirst("Authorization") != esperado -> 401 to """{"message":"Unauthorized"}"""
                numero == "+525500000000" -> 400 to """{"message":"invalid phone number"}"""
                numero == "+525522222222" -> 503 to """{"message":"QueueLimitExceeded"}"""
                else -> 202 to """{"id":"${Json.parseToJsonElement(cuerpo).jsonObject["id"]!!.jsonPrimitive.content}","state":"Pending"}"""
            }
            val bytes = respuesta.toByteArray()
            ex.sendResponseHeaders(codigo, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        servidor.start()
    }

    // La clase base usa una sola instancia para todas las pruebas: el servidor falso se apaga al final.
    @AfterAll
    fun apagar() = servidor.stop(0)

    private fun pasarela(clave: String = "clave") =
        ProveedorSmsGate("usuario", clave, vigencia = 9.minutes, confirmaEntrega = true, urlApi = "http://127.0.0.1:${servidor.address.port}")

    private fun sms(destino: String) = Mensaje(41, "SMS", destino, "Sendero Seguro", "Ana activó una alerta")

    @Test
    fun `manda el SMS con su texto, numero E164 y vigencia, cada intento con su propio id`() {
        val primero = assertIs<ResultadoEnvio.Aceptado>(pasarela().enviar(sms("5512345678")))
        val enviado = Json.parseToJsonElement(cuerpos.last()).jsonObject
        assertEquals("Ana activó una alerta", enviado["textMessage"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("+525512345678", enviado["phoneNumbers"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(540, enviado["ttl"]!!.jsonPrimitive.int, "Vence en la nube antes del plazo de confirmación")
        assertEquals(enviado["id"]!!.jsonPrimitive.content, primero.proveedorMensajeId)
        assertTrue(primero.proveedorMensajeId!!.startsWith("ss41-"))
        assertFalse(primero.entregado)

        val reintento = assertIs<ResultadoEnvio.Aceptado>(pasarela().enviar(sms("5512345678")))
        assertNotEquals(primero.proveedorMensajeId, reintento.proveedorMensajeId)
    }

    @Test
    fun `numero invalido se descarta, cola llena espera y credenciales malas se reintentan`() {
        assertTrue(assertIs<ResultadoEnvio.Fallo>(pasarela().enviar(sms("+525500000000"))).destinoInvalido)
        assertEquals(60, assertIs<ResultadoEnvio.Fallo>(pasarela().enviar(sms("+525522222222"))).esperarSeg)
        val mala = assertIs<ResultadoEnvio.Fallo>(pasarela(clave = "otra").enviar(sms("5512345678")))
        assertFalse(mala.definitivo || mala.destinoInvalido)
        assertTrue(mala.error.contains("401"))
    }

    // --- Webhook ---

    @Test
    fun `la firma es HMAC-SHA256 en hexadecimal del cuerpo seguido del timestamp, y vence`() {
        val cuerpo = """{"event":"system:ping","payload":{}}""".toByteArray()
        // Calculado aparte con Python: hmac.new(llave, cuerpo + timestamp, sha256).hexdigest()
        val esperada = "e1335db8dd94bb12941e99a93b2d6baf60441753b1151d334716bb39bda46d04"
        assertEquals(esperada, FirmaSmsGate.calcular("llave-de-prueba", cuerpo, "1767225600"))
        val entonces = Instant.ofEpochSecond(1767225600)
        assertTrue(FirmaSmsGate.valida("llave-de-prueba", cuerpo, "1767225600", esperada.uppercase(), entonces.plusSeconds(60)))
        assertFalse(FirmaSmsGate.valida("llave-de-prueba", cuerpo, "1767225600", esperada, entonces.plusSeconds(301)), "Más de 5 minutos")
        assertFalse(FirmaSmsGate.valida("otra-llave", cuerpo, "1767225600", esperada, entonces))
        assertFalse(FirmaSmsGate.valida("llave-de-prueba", cuerpo, null, esperada, entonces))
    }

    private fun ApplicationTestBuilder.montar(config: ConfigSmsGate = ConfigSmsGate(llave, modoDesarrollo = false)) {
        application {
            configurarSerializacion()
            configurarErrores()
            val envio = EnvioAvisos(dataSource, ProveedorSimulado(), ProveedorSimulado(), enlaces)
            routing { route("/api/v1") { rutasSmsGate(RecepcionSms(dataSource, dispersion, RegistroSmsPrueba), envio, config) } }
        }
    }

    /** Llama al webhook como la app: JSON firmado con la llave y la hora actual. */
    private suspend fun ApplicationTestBuilder.app(evento: String, campos: Map<String, String>, firmaCon: String = llave): HttpResponse {
        val cuerpo = buildJsonObject {
            put("deviceId", "celular-prueba")
            put("event", evento)
            put("id", UUID.randomUUID().toString())
            putJsonObject("payload") { campos.forEach { (k, v) -> put(k, v) } }
            put("webhookId", "wh-prueba")
        }.toString().toByteArray()
        return firmado(cuerpo, firmaCon)
    }

    private suspend fun ApplicationTestBuilder.firmado(cuerpo: ByteArray, firmaCon: String = llave): HttpResponse {
        val ts = Instant.now().epochSecond.toString()
        return client.post("/api/v1/pasarela/smsgate") {
            header("X-Timestamp", ts)
            header("X-Signature", FirmaSmsGate.calcular(firmaCon, cuerpo, ts))
            contentType(ContentType.Application.Json)
            setBody(cuerpo)
        }
    }

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun `un SS1 que llega al celular registra la alerta igual que por Twilio`() = testApplication {
        montar()
        val texto = Ss1.codificar(DatosSs1(UUID.randomUUID().toString().take(8), 1, 19.504507, -99.146911, 15, Instant.now()))
        val r = app("sms:received", mapOf("messageId" to "m1", "message" to texto, "sender" to numeroAna, "receivedAt" to Instant.now().toString()))
        assertEquals(HttpStatusCode.OK, r.status)
        val j = r.json()
        assertEquals(true, j["valido"]?.jsonPrimitive?.booleanOrNull)
        val alertaId = assertNotNull(j["alertaId"]?.jsonPrimitive?.contentOrNull)
        assertEquals("SMS", texto("SELECT canal_recepcion::text FROM alerta WHERE id = ?::uuid", alertaId))

        // Un número que no es de una cuenta se guarda, pero no es válido.
        val ajeno = app("sms:received", mapOf("messageId" to "m2", "message" to texto, "sender" to "+525599999999")).json()
        assertEquals(false, ajeno["valido"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `el estado del celular confirma la entrega o marca la falla`() = testApplication {
        montar()
        val ids = listOf("ss1-entregado", "ss1-fallido").map { id ->
            numero(
                """INSERT INTO notificacion (usuario_id, tipo, canal, estado, proveedor_mensaje_id, enviada_en, proximo_reintento_en)
                   VALUES (?, 'ACUSE', 'SMS', 'ENVIADA', ?, now(), now() + interval '10 minutes') RETURNING id""",
                AlertasPrueba.ANA, id,
            )
        }
        try {
            fun estado(id: Int) = texto("SELECT estado::text || '|' || coalesce(proximo_reintento_en::text, '-') FROM notificacion WHERE id = ?", id)
            assertEquals(HttpStatusCode.NoContent, app("sms:sent", mapOf("messageId" to "ss1-entregado")).status)
            assertEquals("ENVIADA|-", estado(ids[0]), "Ya salió del celular: no vence por falta de confirmación")
            app("sms:delivered", mapOf("messageId" to "ss1-entregado"))
            assertEquals(true, estado(ids[0])?.startsWith("ENTREGADA"))

            app("sms:failed", mapOf("messageId" to "ss1-fallido", "reason" to "RESULT_ERROR_NO_SERVICE"))
            assertEquals(true, estado(ids[1])?.startsWith("FALLIDA"), "Se reintenta")
            assertEquals(
                "SMS Gateway: no se pudo enviar (RESULT_ERROR_NO_SERVICE)",
                texto("SELECT ultimo_error FROM notificacion WHERE id = ?", ids[1]),
            )
            assertEquals(HttpStatusCode.NoContent, app("system:ping", emptyMap()).status)
        } finally {
            ids.forEach { sql("DELETE FROM notificacion WHERE id = ?", it) }
        }
    }

    @Test
    fun `sin firma valida no entra nada, y un aviso firmado sin forma es 400`() = testApplication {
        montar()
        val antes = numero("SELECT count(*) FROM mensaje_sms")
        assertEquals(HttpStatusCode.Unauthorized, app("sms:received", mapOf("message" to "hola", "sender" to numeroAna), firmaCon = "otra").status)
        val sinFirma = client.post("/api/v1/pasarela/smsgate") {
            contentType(ContentType.Application.Json)
            setBody("""{"event":"sms:received","payload":{"message":"hola","sender":"$numeroAna"}}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, sinFirma.status)
        assertEquals(antes, numero("SELECT count(*) FROM mensaje_sms"))
        assertEquals(HttpStatusCode.BadRequest, firmado("""{"hola":1}""".toByteArray()).status)
    }

    @Test
    fun `en la nube sin llave de firma la ruta responde 503`() = testApplication {
        montar(ConfigSmsGate("", modoDesarrollo = false))
        assertEquals(HttpStatusCode.ServiceUnavailable, app("system:ping", emptyMap()).status)
    }
}
