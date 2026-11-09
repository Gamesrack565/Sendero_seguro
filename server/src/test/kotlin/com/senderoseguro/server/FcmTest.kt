package com.senderoseguro.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.senderoseguro.server.avisos.CredencialesFcm
import com.senderoseguro.server.avisos.Mensaje
import com.senderoseguro.server.avisos.ProveedorFcm
import com.senderoseguro.server.avisos.ResultadoEnvio
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Proveedor de FCM contra un servidor falso que imita a Google (token OAuth y API HTTP v1). No necesita base. */
class FcmTest {

    private val llaves = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val servidor = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val url get() = "http://127.0.0.1:${servidor.address.port}"
    private var tokensPedidos = 0
    private val cuerpos = mutableListOf<String>()
    private var responder401 = false

    init {
        servidor.createContext("/token") { ex ->
            val form = String(ex.requestBody.readAllBytes()).split('&').associate {
                val (k, v) = it.split('=', limit = 2)
                k to URLDecoder.decode(v, Charsets.UTF_8)
            }
            // Google valida la firma con la llave pública de la cuenta de servicio.
            val jwt = JWT.require(Algorithm.RSA256(llaves.public as RSAPublicKey, null))
                .withIssuer("envios@proyecto-prueba.iam.gserviceaccount.com")
                .withAudience("$url/token")
                .withClaim("scope", "https://www.googleapis.com/auth/firebase.messaging")
                .build()
            val ok = form["grant_type"] == "urn:ietf:params:oauth:grant-type:jwt-bearer" && runCatching { jwt.verify(form["assertion"]) }.isSuccess
            tokensPedidos++
            responder(ex, if (ok) 200 else 400, if (ok) """{"access_token":"acceso-$tokensPedidos","expires_in":3599}""" else """{"error":"invalid_grant"}""")
        }
        servidor.createContext("/v1/projects/proyecto-prueba/messages:send") { ex ->
            val cuerpo = String(ex.requestBody.readAllBytes(), Charsets.UTF_8)
            cuerpos += cuerpo
            val token = Json.parseToJsonElement(cuerpo).jsonObject.getValue("message").jsonObject.getValue("token").jsonPrimitive.content
            when {
                ex.requestHeaders.getFirst("Authorization")?.startsWith("Bearer acceso-") != true -> responder(ex, 401, """{"error":{"code":401,"status":"UNAUTHENTICATED"}}""")
                responder401 -> { responder401 = false; responder(ex, 401, """{"error":{"code":401,"status":"UNAUTHENTICATED","message":"expired"}}""") }
                token == "token-viejo" -> responder(ex, 404, """{"error":{"code":404,"message":"Requested entity was not found.","status":"NOT_FOUND",
                    "details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"UNREGISTERED"}]}}""")
                token == "token-caido" -> responder(ex, 503, """{"error":{"code":503,"status":"UNAVAILABLE","message":"Servicio no disponible"}}""")
                else -> responder(ex, 200, """{"name":"projects/proyecto-prueba/messages/0:123"}""")
            }
        }
        servidor.start()
    }

    private fun responder(ex: com.sun.net.httpserver.HttpExchange, codigo: Int, cuerpo: String) {
        val bytes = cuerpo.toByteArray()
        ex.sendResponseHeaders(codigo, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    @AfterTest
    fun apagar() = servidor.stop(0)

    private fun proveedor() = ProveedorFcm(
        CredencialesFcm("proyecto-prueba", "envios@proyecto-prueba.iam.gserviceaccount.com", llaves.private as RSAPrivateKey, "$url/token"),
        urlFcm = url,
    )

    private fun mensaje(token: String) = Mensaje(1, "PUSH", token, "Tu alerta fue recibida", "Una unidad de atención recibió tu alerta.", mapOf("tipo" to "ACUSE"))

    @Test
    fun `envia el push con el token de acceso y lo reutiliza`() {
        val fcm = proveedor()
        val r = assertIs<ResultadoEnvio.Aceptado>(fcm.enviar(mensaje("token-bueno-1")))
        assertEquals("projects/proyecto-prueba/messages/0:123", r.proveedorMensajeId)
        assertFalse(r.entregado)   // FCM no confirma la entrega
        fcm.enviar(mensaje("token-bueno-2"))
        assertEquals(1, tokensPedidos)

        val m = Json.parseToJsonElement(cuerpos.first()).jsonObject.getValue("message").jsonObject
        assertEquals("Tu alerta fue recibida", m.getValue("notification").jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals("Una unidad de atención recibió tu alerta.", m.getValue("notification").jsonObject.getValue("body").jsonPrimitive.content)
        assertEquals("ACUSE", m.getValue("data").jsonObject.getValue("tipo").jsonPrimitive.content)
        assertEquals("HIGH", m.getValue("android").jsonObject.getValue("priority").jsonPrimitive.content)
    }

    @Test
    fun `un token desinstalado se marca como destino invalido`() {
        val r = assertIs<ResultadoEnvio.Fallo>(proveedor().enviar(mensaje("token-viejo")))
        assertTrue(r.destinoInvalido)
        assertTrue(r.error.contains("UNREGISTERED"), r.error)
    }

    @Test
    fun `una falla de FCM se reintenta y un 401 pide un token de acceso nuevo`() {
        val fcm = proveedor()
        val caido = assertIs<ResultadoEnvio.Fallo>(fcm.enviar(mensaje("token-caido")))
        assertFalse(caido.destinoInvalido || caido.definitivo)

        responder401 = true
        assertIs<ResultadoEnvio.Fallo>(fcm.enviar(mensaje("token-bueno")))
        assertIs<ResultadoEnvio.Aceptado>(fcm.enviar(mensaje("token-bueno")))
        assertEquals(2, tokensPedidos)
    }

    @Test
    fun `lee la cuenta de servicio del json de Google`() {
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(llaves.private.encoded) + "\n-----END PRIVATE KEY-----\n"
        val archivo = kotlin.io.path.createTempFile(suffix = ".json").toFile()
        try {
            archivo.writeText(
                """{"type":"service_account","project_id":"proyecto-prueba","private_key":${Json.encodeToString(pem)},
                   "client_email":"envios@proyecto-prueba.iam.gserviceaccount.com","token_uri":"$url/token"}""",
            )
            val c = CredencialesFcm.desdeArchivo(archivo.path)
            assertEquals("proyecto-prueba", c.proyecto)
            assertIs<ResultadoEnvio.Aceptado>(ProveedorFcm(c, urlFcm = url).enviar(mensaje("token-bueno")))
            // En la nube la variable trae el contenido en vez de la ruta.
            assertEquals(c.correo, CredencialesFcm.desdeTexto(archivo.readText()).correo)
        } finally {
            archivo.delete()
        }
    }
}
