package com.senderoseguro.server

import com.senderoseguro.server.alertas.RegistroAlertas
import com.senderoseguro.server.alertas.UbicacionesAlerta
import com.senderoseguro.server.alertas.rutasRegistroAlertas
import com.senderoseguro.server.desarrollo.AlertasPrueba
import com.senderoseguro.server.sincronizacion.TipoSync
import com.senderoseguro.server.sincronizacion.Sincronizacion
import com.senderoseguro.server.sincronizacion.ElementoSync
import com.senderoseguro.server.sincronizacion.ContextoSync
import com.senderoseguro.server.plataforma.EmisorTokens
import com.senderoseguro.server.plataforma.ejecutar
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
            routing { route("/api/v1") { rutasRegistroAlertas(RegistroAlertas(dataSource, dispersion), UbicacionesAlerta(dataSource)) } }
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

    private suspend fun HttpClient.relevar(cuerpo: String, token: String? = tokenBeto): HttpResponse = post("/api/v1/malla/relevos") {
        token?.let { header("Authorization", "Bearer $it") }
        contentType(ContentType.Application.Json)
        setBody(cuerpo)
    }

    /** Lo que manda el teléfono que recibió el paquete por Bluetooth: ids cortos de la alerta y de Ana ("00000001"). */
    private fun relevo(idCorto: String, activadaEn: Instant, persona: String = "00000001") =
        """{"idCortoAlerta":"$idCorto","idCortoUsuario":"$persona","latitud":19.504507,"longitud":-99.146911,
            "nivelBateria":40,"ttl":3,"saltosMalla":2,"activadaEn":"$activadaEn"}"""

    @Test
    fun `el relevo de la malla registra la alerta a nombre de quien la activo y no se duplica`() = testApplication {
        montar()
        val idTelefono = UUID.randomUUID()
        val idCorto = idTelefono.toString().take(8)
        val activadaEn = Instant.now().minusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)

        val r = client.relevar(relevo(idCorto, activadaEn))
        assertEquals(HttpStatusCode.Created, r.status)
        val json = respuesta(r.bodyAsText())
        val folio = json.getValue("folio").jsonPrimitive.content
        val id = UUID.fromString(json.getValue("id").jsonPrimitive.content)
        assertTrue(id.toString().startsWith(idCorto))
        assertEquals(AlertasPrueba.ANA.toString(), texto("SELECT usuario_id::text FROM alerta WHERE id = ?", id))
        assertEquals("MALLA 2 40", texto("SELECT canal_recepcion || ' ' || saltos_malla || ' ' || nivel_bateria FROM alerta WHERE id = ?", id))
        assertEquals(
            "00000000-0000-4000-8000-000000000002",
            texto("SELECT detalle->>'retransmitidaPor' FROM bitacora WHERE entidad_id = ? AND accion = 'ENVIAR_ALERTA'", id.toString()),
        )
        esperarDespacho(id)

        // Otro teléfono entrega el mismo paquete, y después llega la alerta de Ana por internet: es la misma.
        assertEquals(HttpStatusCode.OK, client.relevar(relevo(idCorto, activadaEn), token = tokenOperador).status)
        val internet = client.enviar(cuerpo(idTelefono, activadaEn = activadaEn.plusMillis(400)))
        assertEquals(HttpStatusCode.OK, internet.status)
        assertEquals(folio, respuesta(internet.bodyAsText()).getValue("folio").jsonPrimitive.content)
        assertEquals(1, numero("SELECT count(*) FROM alerta WHERE id::text LIKE ? || '%'", idCorto))
    }

    @Test
    fun `un relevo con datos invalidos, viejo, de alguien que no existe o sin token no registra nada`() = testApplication {
        montar()
        val idCorto = UUID.randomUUID().toString().take(8)
        val ahora = Instant.now()
        assertEquals(HttpStatusCode.BadRequest, client.relevar(relevo("SOS-1234", ahora)).status)
        assertEquals(HttpStatusCode.BadRequest, client.relevar(relevo(idCorto, ahora, persona = "ana")).status)
        assertEquals(HttpStatusCode.BadRequest, client.relevar(relevo(idCorto, ahora.minusSeconds(25 * 3600))).status)
        assertEquals(HttpStatusCode.NotFound, client.relevar(relevo(idCorto, ahora, persona = "abcdef99")).status)
        assertEquals(HttpStatusCode.Unauthorized, client.relevar(relevo(idCorto, ahora), token = null).status)
        assertEquals(0, numero("SELECT count(*) FROM alerta WHERE id::text LIKE ? || '%'", idCorto))
    }

    private suspend fun HttpClient.ubicaciones(alerta: Any, cuerpo: String, token: String? = tokenAna): HttpResponse =
        post("/api/v1/alertas/$alerta/ubicaciones") {
            token?.let { header("Authorization", "Bearer $it") }
            contentType(ContentType.Application.Json)
            setBody(cuerpo)
        }

    /** Lecturas como las manda la app cada 10 s mientras la alerta sigue activa. */
    private fun lecturas(vararg horas: Instant, latitud: Double = 19.5051) = horas.joinToString(",", """{"lecturas":[""", "]}") {
        """{"latitud":$latitud,"longitud":-99.1470,"precisionM":6.0,"velocidadMs":1.2,"rumboGrados":90,"bateria":63,"fuente":"GNSS","capturadaEn":"$it"}"""
    }

    @Test
    fun `las ubicaciones de la alerta se guardan sin repetirse y solo mientras sigue activa`() = testApplication {
        montar()
        val id = UUID.randomUUID()
        assertEquals(HttpStatusCode.Created, client.enviar(cuerpo(id)).status)
        val t1 = Instant.now().minusSeconds(20).truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        val t2 = t1.plusSeconds(10)

        val r = client.ubicaciones(id, lecturas(t1, t2))
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("2 0", respuesta(r.bodyAsText()).let { "${it.getValue("insertadas").jsonPrimitive.content} ${it.getValue("duplicadas").jsonPrimitive.content}" })
        // La app reintenta con lo que no supo si llegó y una lectura nueva.
        val r2 = respuesta(client.ubicaciones(id, lecturas(t1, t2, t2.plusSeconds(10))).bodyAsText())
        assertEquals("1 2", "${r2.getValue("insertadas").jsonPrimitive.content} ${r2.getValue("duplicadas").jsonPrimitive.content}")
        assertEquals(4, numero("SELECT count(*) FROM ubicacion_alerta WHERE alerta_id = ?", id), "La primera más tres")
        assertEquals(1, numero("SELECT count(*) FROM ubicacion_alerta WHERE alerta_id = ? AND capturada_en = ? AND rumbo_grados = 90", id, t1))

        assertEquals(HttpStatusCode.NotFound, client.ubicaciones(id, lecturas(t1), token = tokenBeto).status, "No es su alerta")
        assertEquals(HttpStatusCode.NotFound, client.ubicaciones(UUID.randomUUID(), lecturas(t1)).status)
        assertEquals(HttpStatusCode.BadRequest, client.ubicaciones(id, lecturas(t1, latitud = 120.0)).status)
        assertEquals(HttpStatusCode.BadRequest, client.ubicaciones(id, """{"lecturas":[]}""").status)
        assertEquals(HttpStatusCode.Unauthorized, client.ubicaciones(id, lecturas(t1), token = null).status)

        esperarDespacho(id)
        dataSource.connection.use { it.ejecutar("UPDATE alerta SET estado = 'CANCELADA', cancelada_en = now() WHERE id = ?", id) }
        assertEquals(HttpStatusCode.Conflict, client.ubicaciones(id, lecturas(Instant.now())).status)
        assertEquals(4, numero("SELECT count(*) FROM ubicacion_alerta WHERE alerta_id = ?", id))
    }

    @Test
    fun `las ubicaciones juntadas sin senal entran por el lote, tambien si la alerta llego por la malla`() = testApplication {
        montar()
        val idTelefono = UUID.randomUUID()
        val activadaEn = Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        // La alerta llegó por la malla: en el servidor tiene otro UUID con el mismo id corto.
        val relevada = UUID.fromString(respuesta(client.relevar(relevo(idTelefono.toString().take(8), activadaEn)).bodyAsText()).getValue("id").jsonPrimitive.content)

        val manejador = UbicacionesAlerta(dataSource).manejadorSync
        val sync = Sincronizacion(dataSource, dispersion, mapOf(TipoSync.UBICACION to manejador))
        val ana = ContextoSync(AlertasPrueba.ANA, dispositivoAna)
        fun elemento(alerta: UUID, vararg horas: Instant) = ElementoSync(
            TipoSync.UBICACION, UUID.randomUUID(), horas.last(),
            Json.parseToJsonElement(lecturas(*horas).dropLast(1) + ""","alertaId":"$alerta"}""").jsonObject,
        )
        val t = activadaEn.plusSeconds(10)
        val r = sync.procesar(ana, listOf(elemento(idTelefono, t, t.plusSeconds(10)), elemento(idTelefono, t, t.plusSeconds(10)), elemento(UUID.randomUUID(), t)))
        assertEquals(listOf("ACEPTADO", "DUPLICADO", "RECHAZADO"), r.map { it.resultado })
        assertEquals(relevada.toString(), r[0].referencia)
        assertEquals(2, numero("SELECT count(*) FROM ubicacion_alerta WHERE alerta_id = ? AND capturada_en >= ?", relevada, t))
        assertEquals("NO_ENCONTRADO" to "true", r[2].error!!.codigo to r[2].error!!.detalle!!.getValue("temporal").jsonPrimitive.content,
            "La alerta puede llegar después: el teléfono lo reintenta")
    }
}
