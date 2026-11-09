package com.senderoseguro.server

import com.senderoseguro.server.avisos.EnvioAvisos
import com.senderoseguro.server.avisos.ProveedorSimulado
import com.senderoseguro.server.desarrollo.AlertasPrueba
import com.senderoseguro.server.desarrollo.RegistroSmsPrueba
import com.senderoseguro.server.pasarela.ConfigPasarela
import com.senderoseguro.server.pasarela.DatosSs1
import com.senderoseguro.server.pasarela.FirmaTwilio
import com.senderoseguro.server.pasarela.RecepcionSms
import com.senderoseguro.server.pasarela.RegistroAlertasSms
import com.senderoseguro.server.pasarela.Ss1
import com.senderoseguro.server.pasarela.rutasPasarela
import com.senderoseguro.server.plataforma.configurarErrores
import com.senderoseguro.server.plataforma.configurarSerializacion
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Webhooks de Twilio: SMS de contingencia que llega (SS1) y estado de los SMS enviados. */
class PasarelaSmsTest : PruebaConBaseDatos() {

    private val authToken = "token-de-prueba-twilio"
    private val urlPublica = "https://sendero.example/api/v1"
    private val numeroAna = "+525510000001"

    private fun ApplicationTestBuilder.montar(registro: RegistroAlertasSms? = RegistroSmsPrueba) {
        application {
            configurarSerializacion()
            configurarErrores()
            val envio = EnvioAvisos(dataSource, ProveedorSimulado(), ProveedorSimulado(), enlaces)
            routing {
                route("/api/v1") {
                    rutasPasarela(RecepcionSms(dataSource, dispersion, registro), envio, ConfigPasarela(authToken, urlPublica, modoDesarrollo = false))
                }
            }
        }
    }

    /** Llama al webhook como Twilio: formulario firmado con el Auth Token sobre la URL pública. */
    private suspend fun ApplicationTestBuilder.twilio(ruta: String, campos: Map<String, String>, token: String = authToken): HttpResponse {
        val firma = FirmaTwilio.calcular(token, urlPublica + ruta, campos.mapValues { listOf(it.value) })
        return client.post("/api/v1$ruta") {
            header("X-Twilio-Signature", firma)
            header("Accept", "application/json")
            setBody(FormDataContent(parameters { campos.forEach { (k, v) -> append(k, v) } }))
        }
    }

    private fun ss1(hora: Instant = Instant.now(), idCorto: String = UUID.randomUUID().toString().take(8)) =
        Ss1.codificar(DatosSs1(idCorto, 1, 19.504507, -99.146911, 15, hora))

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun esperar(condicion: () -> Boolean) {
        val limite = System.currentTimeMillis() + 5_000
        while (!condicion()) {
            check(System.currentTimeMillis() < limite) { "No se cumplió a tiempo" }
            Thread.sleep(50)
        }
    }

    @Test
    fun `un SS1 valido registra la alerta por SMS, se despacha y un reenvio no la duplica`() = testApplication {
        montar()
        val texto = ss1()
        val r = twilio("/pasarela/sms/entrante", mapOf("From" to numeroAna, "Body" to texto, "MessageSid" to "SMentrante1"))
        assertEquals(HttpStatusCode.OK, r.status)
        val j = r.json()
        assertEquals(true, j["valido"]?.jsonPrimitive?.booleanOrNull)
        val alertaId = assertNotNull(j["alertaId"]?.jsonPrimitive?.contentOrNull)
        assertTrue(alertaId.startsWith(texto.substring(4, 12)), "El id empieza con el id corto")
        assertEquals("SMS", texto("SELECT canal_recepcion::text FROM alerta WHERE id = ?::uuid", alertaId))
        assertEquals(AlertasPrueba.ANA.toString(), texto("SELECT usuario_id::text FROM alerta WHERE id = ?::uuid", alertaId))
        // El SS1 lleva 5 decimales (unos 1.1 m).
        assertEquals("19.50451", texto("SELECT round(latitud::numeric, 5)::text FROM ubicacion_alerta WHERE alerta_id = ?::uuid", alertaId))
        // Sigue el mismo flujo de dispersión: se le asigna una unidad.
        esperar { texto("SELECT estado::text FROM alerta WHERE id = ?::uuid", alertaId) == "ASIGNADA" }

        // El teléfono reintenta el mismo SMS: se guarda, pero se liga a la misma alerta.
        val otra = twilio("/pasarela/sms/entrante", mapOf("From" to "+5215510000001", "Body" to texto)).json()
        assertEquals(alertaId, otra["alertaId"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1, numero("SELECT count(*) FROM alerta WHERE usuario_id = ? AND id::text LIKE ? || '%'", AlertasPrueba.ANA, texto.substring(4, 12)))
        assertEquals(2, numero("SELECT count(*) FROM mensaje_sms WHERE alerta_id = ?::uuid AND procesado_en IS NOT NULL", alertaId))
    }

    @Test
    fun `se guarda pero no es valido si no es SS1, la verificacion falla, el numero no es de una cuenta o la hora es vieja`() = testApplication {
        montar()
        val bueno = ss1()
        val casos = listOf(
            numeroAna to "Hola, ya voy para alla",
            numeroAna to bueno.dropLast(1) + (if (bueno.last() == '0') '1' else '0'),
            "+525599999999" to ss1(),
            numeroAna to ss1(hora = Instant.now().minusSeconds(2 * 86_400)),
        )
        casos.forEach { (de, cuerpo) ->
            val j = twilio("/pasarela/sms/entrante", mapOf("From" to de, "Body" to cuerpo)).json()
            assertEquals(false, j["valido"]?.jsonPrimitive?.booleanOrNull, cuerpo)
            assertNull(j["alertaId"]?.jsonPrimitive?.contentOrNull)
            val id = j["mensajeId"]!!.jsonPrimitive.content.toLong()
            assertEquals("f|t", texto("SELECT (CASE WHEN valido THEN 't' ELSE 'f' END) || '|' || (procesado_en IS NOT NULL)::text::char FROM mensaje_sms WHERE id = ?", id))
        }
    }

    @Test
    fun `firma invalida responde 401 y no guarda nada`() = testApplication {
        montar()
        val antes = numero("SELECT count(*) FROM mensaje_sms")
        val r = twilio("/pasarela/sms/entrante", mapOf("From" to numeroAna, "Body" to ss1()), token = "token-equivocado")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertEquals(antes, numero("SELECT count(*) FROM mensaje_sms"))
        // Sin pedir JSON (como Twilio), la respuesta es TwiML vacío.
        val firma = FirmaTwilio.calcular(authToken, "$urlPublica/pasarela/sms/entrante", mapOf("From" to listOf(numeroAna), "Body" to listOf("hola")))
        val twiml = client.post("/api/v1/pasarela/sms/entrante") {
            header("X-Twilio-Signature", firma)
            setBody(FormDataContent(parameters { append("From", numeroAna); append("Body", "hola") }))
        }
        assertEquals(HttpStatusCode.OK, twiml.status)
        assertTrue(twiml.bodyAsText().contains("<Response></Response>"))
    }

    @Test
    fun `sin registro conectado el SMS valido queda pendiente y se registra despues`() = testApplication {
        montar(registro = null)
        val j = twilio("/pasarela/sms/entrante", mapOf("From" to numeroAna, "Body" to ss1())).json()
        assertEquals(true, j["valido"]?.jsonPrimitive?.booleanOrNull)
        assertNull(j["alertaId"]?.jsonPrimitive?.contentOrNull)
        val id = j["mensajeId"]!!.jsonPrimitive.content.toLong()
        assertNull(texto("SELECT procesado_en::text FROM mensaje_sms WHERE id = ?", id))

        // Cuando el registro existe, la revisión periódica lo toma.
        assertTrue(RecepcionSms(dataSource, dispersion, RegistroSmsPrueba).procesarPendientes() >= 1)
        val alertaId = assertNotNull(texto("SELECT alerta_id::text FROM mensaje_sms WHERE id = ? AND procesado_en IS NOT NULL", id))
        // Se despacha en segundo plano; se espera a que termine para que sus avisos no queden fuera de la limpieza.
        esperar { texto("SELECT estado::text FROM alerta WHERE id = ?::uuid", alertaId) == "ASIGNADA" }
    }

    @Test
    fun `el estado de Twilio confirma la entrega, para el plazo con sent y descarta un numero inexistente`() = testApplication {
        montar()
        val ids = listOf("SMprueba-entregado", "SMprueba-inexistente").map { sid ->
            numero(
                """INSERT INTO notificacion (usuario_id, tipo, canal, estado, proveedor_mensaje_id, enviada_en, proximo_reintento_en)
                   VALUES (?, 'ACUSE', 'SMS', 'ENVIADA', ?, now(), now() + interval '10 minutes') RETURNING id""",
                AlertasPrueba.ANA, sid,
            )
        }
        try {
            fun estado(id: Int) = texto("SELECT estado::text || '|' || coalesce(proximo_reintento_en::text, '-') FROM notificacion WHERE id = ?", id)
            assertEquals(HttpStatusCode.NoContent, twilio("/pasarela/sms/estado", mapOf("MessageSid" to "SMprueba-entregado", "MessageStatus" to "sent")).status)
            assertEquals("ENVIADA|-", estado(ids[0]))
            twilio("/pasarela/sms/estado", mapOf("MessageSid" to "SMprueba-entregado", "MessageStatus" to "delivered"))
            assertEquals("ENTREGADA|-", estado(ids[0]))

            twilio("/pasarela/sms/estado", mapOf("MessageSid" to "SMprueba-inexistente", "MessageStatus" to "undelivered", "ErrorCode" to "30005"))
            assertEquals("DESCARTADA|-", estado(ids[1]))
            assertEquals("Twilio: undelivered (error 30005)", texto("SELECT ultimo_error FROM notificacion WHERE id = ?", ids[1]))
            // Un id que no es nuestro se ignora; una firma mala no.
            assertEquals(HttpStatusCode.NoContent, twilio("/pasarela/sms/estado", mapOf("MessageSid" to "SMotro", "MessageStatus" to "delivered")).status)
            assertEquals(HttpStatusCode.Unauthorized, twilio("/pasarela/sms/estado", mapOf("MessageSid" to "SMotro"), token = "x").status)
        } finally {
            ids.forEach { sql("DELETE FROM notificacion WHERE id = ?", it) }
        }
    }
}
