package com.senderoseguro.server

import com.senderoseguro.server.alertas.RegistroAlertas
import com.senderoseguro.server.alertas.rutasRegistroAlertas
import com.senderoseguro.server.desarrollo.AlertasPrueba
import com.senderoseguro.server.plataforma.EmisorTokens
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.UsuarioToken
import com.senderoseguro.server.plataforma.configurarErrores
import com.senderoseguro.server.plataforma.configurarSeguridad
import com.senderoseguro.server.plataforma.configurarSerializacion
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** P4: la app registra la alerta en el servidor (POST /alertas). */
class RegistroAlertasTest : PruebaConBaseDatos() {

    private val dispositivoAna = UUID.fromString("00000000-0000-4000-8000-0000000000d1")
    private val tokens = EmisorTokens("secreto-alertas", "sendero-seguro", "sendero-seguro-api")
    private val tokenAna = tokens.emitir(UsuarioToken(AlertasPrueba.ANA, Rol.INTEGRANTE, dispositivoAna, null), 1.hours).first
    private val tokenBeto = tokens.emitir(
        UsuarioToken(UUID.fromString("00000000-0000-4000-8000-000000000002"), Rol.COORDINADOR, null, null), 1.hours,
    ).first
    private val tokenOperador = tokens.emitir(
        UsuarioToken(UUID.fromString("00000000-0000-4000-8000-000000000011"), Rol.OPERADOR, null, null), 1.hours,
    ).first

    private fun ApplicationTestBuilder.montar() {
        environment {
            config = MapApplicationConfig(
                "jwt.secreto" to "secreto-alertas", "jwt.emisor" to "sendero-seguro", "jwt.audiencia" to "sendero-seguro-api",
            )
        }
        application {
            configurarSerializacion()
            configurarErrores()
            configurarSeguridad()
            routing { route("/api/v1") { rutasRegistroAlertas(RegistroAlertas(dataSource, dispersion)) } }
        }
    }

    private suspend fun HttpClient.enviar(cuerpo: String, token: String? = tokenAna): HttpResponse = post("/api/v1/alertas") {
        token?.let { header("Authorization", "Bearer $it") }
        contentType(ContentType.Application.Json)
        setBody(cuerpo)
    }

    /** Lo que manda la app al activar la alerta. */
    private fun cuerpo(
        id: Any = UUID.randomUUID(), activadaEn: Any = Instant.now().minusSeconds(5), tipo: Int? = null,
        detonador: String = "BOTON", latitud: Double = 19.504507, extra: String = "",
    ) = """{"id":"$id","activadaEn":"$activadaEn",${tipo?.let { "\"tipoIncidenteId\":$it," } ?: ""}"detonador":"$detonador",
        "nivelBateria":64,"ubicacion":{"latitud":$latitud,"longitud":-99.146911,"precisionM":8.5,"fuente":"GNSS"}$extra}"""

    private fun respuesta(r: String) = Json.parseToJsonElement(r).jsonObject
    private fun tipo(codigo: String) = numero("SELECT id FROM tipo_incidente WHERE codigo = ?", codigo)
    private fun estado(id: UUID) = texto("SELECT estado::text FROM alerta WHERE id = ?", id)

    private fun esperarDespacho(id: UUID) {
        repeat(50) {
            if (estado(id) != "ENVIADA") return
            Thread.sleep(100)
        }
        error("La alerta $id no se despachó")
    }

    @Test
    fun `la alerta se registra con folio, se despacha y un reenvio no la duplica`() = testApplication {
        montar()
        val id = UUID.randomUUID()
        val r = client.enviar(cuerpo(id, detonador = "VOZ"))
        assertEquals(HttpStatusCode.Created, r.status)
        val json = respuesta(r.bodyAsText())
        val folio = json.getValue("folio").jsonPrimitive.content
        assertTrue(folio.startsWith("ALR-"))
        assertEquals(id.toString(), json.getValue("id").jsonPrimitive.content)
        assertEquals("ENVIADA", json.getValue("estado").jsonPrimitive.content)

        assertEquals(AlertasPrueba.ANA.toString(), texto("SELECT usuario_id::text FROM alerta WHERE id = ?", id))
        assertEquals(dispositivoAna.toString(), texto("SELECT dispositivo_id::text FROM alerta WHERE id = ?", id))
        assertEquals("INTERNET VOZ 64", texto("SELECT canal_recepcion || ' ' || detonador || ' ' || nivel_bateria FROM alerta WHERE id = ?", id))
        assertEquals(1, numero("SELECT count(*) FROM ubicacion_alerta WHERE alerta_id = ? AND precision_m = 8.5 AND bateria = 64", id))
        assertEquals(1, numero("SELECT count(*) FROM evento_alerta WHERE alerta_id = ? AND estado_anterior IS NULL AND estado_nuevo = 'ENVIADA'", id))
        assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE entidad_id = ? AND accion = 'ENVIAR_ALERTA'", id.toString()))
        esperarDespacho(id)

        // La app reintenta desde su cola: misma alerta, mismo folio, 200.
        val reenvio = client.enviar(cuerpo(id, detonador = "VOZ"))
        assertEquals(HttpStatusCode.OK, reenvio.status)
        assertEquals(folio, respuesta(reenvio.bodyAsText()).getValue("folio").jsonPrimitive.content)
        assertEquals(1, numero("SELECT count(*) FROM alerta WHERE id = ?", id))
        assertEquals(1, numero("SELECT count(*) FROM ubicacion_alerta WHERE alerta_id = ?", id))
    }

    @Test
    fun `sin tipo o con uno que no existe se registra como generica`() = testApplication {
        montar()
        val generica = tipo("GENERICA")
        val conTipo = UUID.randomUUID()
        val sinTipo = UUID.randomUUID()
        val inventado = UUID.randomUUID()
        assertEquals(HttpStatusCode.Created, client.enviar(cuerpo(conTipo, tipo = tipo("AGRESION_FISICA"))).status)
        assertEquals(HttpStatusCode.Created, client.enviar(cuerpo(sinTipo)).status)
        assertEquals(HttpStatusCode.Created, client.enviar(cuerpo(inventado, tipo = 999)).status)
        assertEquals(tipo("AGRESION_FISICA"), numero("SELECT tipo_incidente_id FROM alerta WHERE id = ?", conTipo))
        assertEquals(generica, numero("SELECT tipo_incidente_id FROM alerta WHERE id = ?", sinTipo))
        assertEquals(generica, numero("SELECT tipo_incidente_id FROM alerta WHERE id = ?", inventado))
    }

    @Test
    fun `con el reloj del telefono adelantado la hora queda en el presente`() = testApplication {
        montar()
        val id = UUID.randomUUID()
        assertEquals(HttpStatusCode.Created, client.enviar(cuerpo(id, activadaEn = Instant.now().plusSeconds(3600))).status)
        assertEquals(0, numero("SELECT count(*) FROM alerta WHERE id = ? AND activada_en > now()", id))
    }

    @Test
    fun `datos invalidos, sin token, un operador o un id ajeno no registran nada`() = testApplication {
        montar()
        val id = UUID.randomUUID()
        assertEquals(HttpStatusCode.BadRequest, client.enviar(cuerpo("basura")).status)
        assertEquals(HttpStatusCode.BadRequest, client.enviar(cuerpo(id, activadaEn = "ayer")).status)
        assertEquals(HttpStatusCode.BadRequest, client.enviar(cuerpo(id, latitud = 200.0)).status)
        assertEquals(HttpStatusCode.BadRequest, client.enviar(cuerpo(id, detonador = "BOTON_SOS")).status)
        assertEquals(HttpStatusCode.BadRequest, client.enviar(cuerpo(id, extra = ""","canalRecepcion":"COLA_LOCAL"""")).status)
        assertEquals(HttpStatusCode.BadRequest, client.enviar("""{"id":"$id"}""").status)
        assertEquals(HttpStatusCode.Unauthorized, client.enviar(cuerpo(id), token = null).status)
        assertEquals(HttpStatusCode.Forbidden, client.enviar(cuerpo(id), token = tokenOperador).status)
        assertEquals(0, numero("SELECT count(*) FROM alerta WHERE id = ?", id))

        // El id ya es de una alerta de Ana: Beto no puede usarlo.
        assertEquals(HttpStatusCode.Created, client.enviar(cuerpo(id)).status)
        assertEquals(HttpStatusCode.Conflict, client.enviar(cuerpo(id), token = tokenBeto).status)
        assertEquals(AlertasPrueba.ANA.toString(), texto("SELECT usuario_id::text FROM alerta WHERE id = ?", id))
    }
}
