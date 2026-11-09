package com.senderoseguro.server

import com.senderoseguro.server.avisos.Mensaje
import com.senderoseguro.server.avisos.ProveedorTwilio
import com.senderoseguro.server.avisos.ResultadoEnvio
import com.senderoseguro.server.pasarela.DatosSs1
import com.senderoseguro.server.pasarela.FirmaTwilio
import com.senderoseguro.server.pasarela.Ss1
import com.senderoseguro.server.pasarela.Telefonos
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Instant
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Formato SS1, números, firma de Twilio y envío por Twilio contra un servidor falso. No necesita base. */
class TwilioTest {

    private val servidor = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val url get() = "http://127.0.0.1:${servidor.address.port}"
    private val formularios = mutableListOf<Map<String, String>>()

    init {
        servidor.createContext("/2010-04-01/Accounts/AC123/Messages.json") { ex ->
            val form = String(ex.requestBody.readAllBytes()).split('&').associate {
                val (k, v) = it.split('=', limit = 2)
                k to URLDecoder.decode(v, Charsets.UTF_8)
            }
            formularios += form
            val esperado = "Basic " + Base64.getEncoder().encodeToString("AC123:token-secreto".toByteArray())
            val (codigo, cuerpo) = when {
                ex.requestHeaders.getFirst("Authorization") != esperado -> 401 to """{"code":20003,"message":"Authenticate","status":401}"""
                form["To"] == "+525500000000" -> 400 to """{"code":21211,"message":"The 'To' number +525500000000 is not a valid phone number.","status":400}"""
                form["To"] == "+525533333333" -> 422 to """{"code":572002,"message":"No Twilio trial phone number is assigned for messaging to this destination number.","status":422}"""
                form["To"] == "+525544444444" -> 400 to """{"code":572006,"message":"Invalid template name. Trial accounts can only use predefined SMS templates.","status":400}"""
                form["To"] == "+525522222222" -> {
                    ex.responseHeaders.add("Retry-After", "7")
                    429 to """{"code":20429,"message":"Too many requests","status":429}"""
                }
                form["To"] == "+525511111111" -> 503 to """{"code":20500,"message":"Internal Server Error","status":503}"""
                else -> 201 to """{"sid":"SM0123456789abcdef","status":"queued"}"""
            }
            val bytes = cuerpo.toByteArray()
            ex.sendResponseHeaders(codigo, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        servidor.start()
    }

    @AfterTest
    fun apagar() = servidor.stop(0)

    private fun twilio(token: String = "token-secreto", urlEstado: String? = "https://sendero.example/api/v1/pasarela/sms/estado") =
        ProveedorTwilio("AC123", token, "+15005550006", urlEstado, urlApi = url)

    private fun sms(destino: String) = Mensaje(1, "SMS", destino, "Sendero Seguro", "Sendero Seguro: Ana activo una alerta.")

    @Test
    fun `decodifica el ejemplo del contrato y lo vuelve a armar igual`() {
        val ejemplo = "SS1|3f9a2c7101+1950431-09914632012TNJXWPe08c9d"
        val d = Ss1.decodificar(ejemplo).getOrThrow()
        assertEquals(DatosSs1("3f9a2c71", 1, 19.50431, -99.14632, 12, Instant.ofEpochSecond("TNJXWP".toLong(36))), d)
        assertEquals(ejemplo, Ss1.codificar(d))
    }

    @Test
    fun `rechaza un SS1 alterado, corto o con campos invalidos`() {
        val bueno = Ss1.codificar(DatosSs1("0a1b2c3d", 5, -33.5, 151.25, 999, Instant.ofEpochSecond(1_793_000_000)))
        assertTrue(Ss1.decodificar(bueno).isSuccess)
        // Cambiar un dígito de la latitud rompe la verificación.
        val alterado = bueno.substring(0, 16) + (if (bueno[16] == '9') '8' else '9') + bueno.substring(17)
        assertEquals("La verificación no coincide", Ss1.decodificar(alterado).exceptionOrNull()?.message)
        assertTrue(Ss1.decodificar(bueno.dropLast(1)).isFailure)
        assertTrue(Ss1.decodificar("Hola, ya llegue").isFailure)
        // Latitud fuera de rango, aunque la verificación sea correcta.
        val fuera = Ss1.codificar(DatosSs1("0a1b2c3d", 5, 95.0, 151.25, 10, Instant.ofEpochSecond(1_793_000_000)))
        assertEquals("Latitud fuera de rango", Ss1.decodificar(fuera).exceptionOrNull()?.message)
    }

    @Test
    fun `los numeros de Mexico se pasan a formato internacional`() {
        assertEquals("+525512345678", Telefonos.e164("5512345678"))
        assertEquals("+525512345678", Telefonos.e164("+52 55 1234 5678"))
        assertEquals("+525512345678", Telefonos.e164("+5215512345678"))
        assertEquals("+15005550006", Telefonos.e164("+15005550006"))
        assertEquals(
            listOf("5512345678", "525512345678", "+525512345678", "5215512345678", "+5215512345678"),
            Telefonos.variantes("+5215512345678"),
        )
    }

    @Test
    fun `la firma coincide con el ejemplo de la documentacion de Twilio`() {
        val parametros = mapOf(
            "CallSid" to "CA1234567890ABCDE", "Caller" to "+12349013030", "Digits" to "1234",
            "From" to "+12349013030", "To" to "+18005551212",
        ).mapValues { listOf(it.value) }
        val url = "https://mycompany.com/myapp.php?foo=1&bar=2"
        assertEquals("0/KCTR6DLpKmkAf8muzZqo1nDgQ=", FirmaTwilio.calcular("12345", url, parametros))
        assertTrue(FirmaTwilio.valida("12345", url, parametros, "0/KCTR6DLpKmkAf8muzZqo1nDgQ="))
        assertFalse(FirmaTwilio.valida("otro-token", url, parametros, "0/KCTR6DLpKmkAf8muzZqo1nDgQ="))
        assertFalse(FirmaTwilio.valida("12345", url, parametros, null))
    }

    @Test
    fun `envia el SMS al numero en formato internacional y pide el aviso de entrega`() {
        val r = twilio().enviar(sms("5512345678"))
        assertEquals(ResultadoEnvio.Aceptado("SM0123456789abcdef"), r)
        val form = formularios.single()
        assertEquals("+525512345678", form["To"])
        assertEquals("+15005550006", form["From"])
        assertEquals("Sendero Seguro: Ana activo una alerta.", form["Body"])
        assertEquals("https://sendero.example/api/v1/pasarela/sms/estado", form["StatusCallback"])
        assertTrue(twilio().confirmaEntrega)
        // Sin dirección pública no hay aviso de entrega.
        assertFalse(twilio(urlEstado = null).confirmaEntrega)
        twilio(urlEstado = null).enviar(sms("5512345678"))
        assertFalse("StatusCallback" in formularios.last())
    }

    @Test
    fun `numero invalido se marca, saturado pide esperar, y una falla de Twilio o de credenciales se reintenta`() {
        val invalido = assertIs<ResultadoEnvio.Fallo>(twilio().enviar(sms("5500000000")))
        assertTrue(invalido.destinoInvalido, invalido.error)
        assertTrue(invalido.error.contains("21211"))

        // Cuenta "Limited trial": número sin verificar, y texto propio en lugar de una plantilla.
        assertTrue(assertIs<ResultadoEnvio.Fallo>(twilio().enviar(sms("5533333333"))).destinoInvalido)
        assertTrue(assertIs<ResultadoEnvio.Fallo>(twilio().enviar(sms("5544444444"))).definitivo)

        val saturado = assertIs<ResultadoEnvio.Fallo>(twilio().enviar(sms("5522222222")))
        assertEquals(7L, saturado.esperarSeg)
        assertFalse(saturado.destinoInvalido || saturado.definitivo)

        val caido = assertIs<ResultadoEnvio.Fallo>(twilio().enviar(sms("5511111111")))
        assertEquals(null, caido.esperarSeg)
        assertFalse(caido.destinoInvalido || caido.definitivo)

        val sinPermiso = assertIs<ResultadoEnvio.Fallo>(twilio(token = "equivocado").enviar(sms("5512345678")))
        assertFalse(sinPermiso.destinoInvalido || sinPermiso.definitivo)
        assertTrue(sinPermiso.error.startsWith("Twilio 401"), sinPermiso.error)
    }
}
