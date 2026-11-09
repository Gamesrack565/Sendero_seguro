package com.senderoseguro.server

import com.senderoseguro.server.desarrollo.AlertasPrueba
import com.senderoseguro.server.desarrollo.SyncAlertaPrueba
import com.senderoseguro.server.plataforma.EmisorTokens
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.UsuarioToken
import com.senderoseguro.server.plataforma.configurarErrores
import com.senderoseguro.server.plataforma.configurarSeguridad
import com.senderoseguro.server.plataforma.configurarSerializacion
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.sincronizacion.ContextoSync
import com.senderoseguro.server.sincronizacion.ElementoSync
import com.senderoseguro.server.sincronizacion.ManejadorSync
import com.senderoseguro.server.sincronizacion.ResultadoManejo
import com.senderoseguro.server.sincronizacion.ResultadoSync
import com.senderoseguro.server.sincronizacion.Sincronizacion
import com.senderoseguro.server.sincronizacion.TipoSync
import com.senderoseguro.server.sincronizacion.rutasSync
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
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** Recepción del lote offline del teléfono (P14, RF-17). */
class SyncTest : PruebaConBaseDatos() {

    private val dispositivoAna = UUID.fromString("00000000-0000-4000-8000-0000000000d1")
    private val ana = ContextoSync(AlertasPrueba.ANA, dispositivoAna)

    private fun sincronizacion(manejadores: Map<TipoSync, ManejadorSync> = mapOf(TipoSync.ALERTA to SyncAlertaPrueba)) =
        Sincronizacion(dataSource, dispersion, manejadores)

    private fun datosAlerta(id: UUID, activadaEn: Instant) = buildJsonObject {
        put("id", id.toString())
        put("activadaEn", activadaEn.toString())
        putJsonObject("ubicacion") { put("latitud", 19.504507); put("longitud", -99.146911); put("precisionM", 8) }
    }

    private fun alertaSync(id: UUID = UUID.randomUUID(), activadaEn: Instant = Instant.now().minusSeconds(120)) =
        ElementoSync(TipoSync.ALERTA, id, activadaEn, datosAlerta(id, activadaEn))

    private fun elemento(tipo: TipoSync, hace: Duration = Duration.ofMinutes(5)) =
        ElementoSync(tipo, UUID.randomUUID(), Instant.now().minus(hace), JsonObject(emptyMap()))

    private fun esperarAsignada(id: UUID) {
        repeat(50) {
            if (texto("SELECT estado::text FROM alerta WHERE id = ?", id) == "ASIGNADA") return
            Thread.sleep(100)
        }
        error("La alerta $id no quedó ASIGNADA")
    }

    @Test
    fun `una alerta que llega por el lote se registra, se despacha y si se reenvia sale DUPLICADO`() {
        val e = alertaSync()
        val primero = sincronizacion().procesar(ana, listOf(e)).single()
        assertEquals("ACEPTADO", primero.resultado)
        assertTrue(primero.referencia!!.startsWith("ALR-"))
        esperarAsignada(e.id)

        val reenvio = sincronizacion().procesar(ana, listOf(e)).single()
        assertEquals(ResultadoSync(e.id.toString(), "DUPLICADO", referencia = primero.referencia), reenvio)
        assertEquals(1, numero("SELECT count(*) FROM alerta WHERE id = ?", e.id))
    }

    @Test
    fun `si la alerta ya llego por SMS, la del lote sale DUPLICADO con el folio de esa`() {
        val activadaEn = Instant.now().minusSeconds(300)
        // Por SMS solo llegan los primeros 8 caracteres del id: el registro inventa el resto.
        val idTelefono = UUID.randomUUID()
        val idSms = UUID.fromString(idTelefono.toString().take(8) + UUID.randomUUID().toString().substring(8))
        sincronizacion().procesar(ana, listOf(alertaSync(idSms, activadaEn.plusSeconds(20))))
        val folioSms = texto("SELECT folio FROM alerta WHERE id = ?", idSms)

        val r = sincronizacion().procesar(ana, listOf(alertaSync(idTelefono, activadaEn))).single()
        assertEquals("DUPLICADO", r.resultado)
        assertEquals(folioSms, r.referencia)
        assertEquals(0, numero("SELECT count(*) FROM alerta WHERE id = ?", idTelefono))
    }

    @Test
    fun `procesa por tipo y hora, responde en el orden de llegada y descarta lo vencido`() {
        val orden = mutableListOf<TipoSync>()
        val anota = ManejadorSync { _, _, e -> orden += e.tipo; ResultadoManejo.Aceptado() }
        val manejadores = TipoSync.entries.associateWith { anota }
        val reporte = elemento(TipoSync.REPORTE, Duration.ofDays(5))
        val acuseViejo = elemento(TipoSync.ACUSE, Duration.ofHours(3))
        val trayecto = elemento(TipoSync.TRAYECTO)
        val ubicacion = elemento(TipoSync.UBICACION)
        val checkin = elemento(TipoSync.CHECKIN, Duration.ofMinutes(1))
        val alertaNueva = elemento(TipoSync.ALERTA, Duration.ofMinutes(1))

        val r = sincronizacion(manejadores).procesar(ana, listOf(reporte, acuseViejo, trayecto, ubicacion, checkin, alertaNueva))
        assertEquals(listOf(TipoSync.ALERTA, TipoSync.UBICACION, TipoSync.REPORTE, TipoSync.TRAYECTO, TipoSync.CHECKIN), orden)
        assertEquals(listOf(reporte, acuseViejo, trayecto, ubicacion, checkin, alertaNueva).map { it.id.toString() }, r.map { it.id })
        assertEquals("VENCIDO", r[1].resultado, "Un acuse vale 2 horas")
        assertEquals("ACEPTADO", r[0].resultado, "Un reporte vale 6 días")
    }

    @Test
    fun `si un elemento falla los demas siguen, y lo que escribio se deshace`() {
        val viaje = UUID.randomUUID()
        val rechaza = ManejadorSync { conn, _, _ ->
            conn.ejecutar(
                """INSERT INTO trayecto (id, usuario_id, origen_latitud, origen_longitud, destino_latitud, destino_longitud, iniciado_en)
                   VALUES (?, ?, 19.5, -99.1, 19.6, -99.2, now())""",
                viaje, AlertasPrueba.ANA,
            )
            ResultadoManejo.Rechazado("VALIDACION", "Trayecto sin destino")
        }
        val truena = ManejadorSync { _, _, _ -> error("falla de prueba") }
        val r = sincronizacion(
            mapOf(TipoSync.ALERTA to SyncAlertaPrueba, TipoSync.TRAYECTO to rechaza, TipoSync.CHECKIN to truena),
        ).procesar(
            ana,
            listOf(
                elemento(TipoSync.TRAYECTO), elemento(TipoSync.CHECKIN), alertaSync(), elemento(TipoSync.REPORTE),
                elemento(TipoSync.UBICACION).copy(creadoEn = Instant.now().plus(Duration.ofHours(1))),
            ),
        )
        assertEquals(listOf("RECHAZADO", "RECHAZADO", "ACEPTADO", "RECHAZADO", "RECHAZADO"), r.map { it.resultado })
        assertEquals(0, numero("SELECT count(*) FROM trayecto WHERE id = ?", viaje), "El rechazo deshizo el INSERT")
        fun temporal(i: Int) = r[i].error!!.detalle!!.getValue("temporal").jsonPrimitive.content
        assertEquals("false", temporal(0), "Un error de datos: el teléfono lo borra")
        assertEquals("ERROR_INTERNO" to "true", r[1].error!!.codigo to temporal(1), "Una falla del servidor: se reintenta")
        assertEquals("true", temporal(3), "Sin manejador todavía: se reintenta")
        assertEquals("VALIDACION" to "false", r[4].error!!.codigo to temporal(4), "Hora del teléfono en el futuro")
        assertNull(r[2].error)
    }

    @Test
    fun `por la ruta, el lote tiene que ser del telefono de quien lo manda y no pasar del limite`() = testApplication {
        environment {
            config = MapApplicationConfig("jwt.secreto" to "secreto-sync", "jwt.emisor" to "sendero-seguro", "jwt.audiencia" to "sendero-seguro-api")
        }
        application {
            configurarSerializacion()
            configurarErrores()
            configurarSeguridad()
            routing { route("/api/v1") { rutasSync(sincronizacion()) } }
        }
        val tokens = EmisorTokens("secreto-sync", "sendero-seguro", "sendero-seguro-api")
        suspend fun mandar(cuerpo: String, cuenta: String? = "ana"): HttpResponse = client.post("/api/v1/sync/lote") {
            cuenta?.let {
                header("Authorization", "Bearer " + tokens.emitir(UsuarioToken(AlertasPrueba.CUENTAS.getValue(it), Rol.INTEGRANTE, null, null), 1.hours).first)
            }
            contentType(ContentType.Application.Json)
            setBody(cuerpo)
        }
        fun lote(vararg elementos: String) = """{"dispositivoId":"$dispositivoAna","elementos":[${elementos.joinToString(",")}]}"""
        val e = alertaSync()
        val bien = """{"tipo":"ALERTA","id":"${e.id}","creadoEn":"${e.creadoEn}","datos":${e.datos}}"""
        val sinTipo = """{"id":"${UUID.randomUUID()}","creadoEn":"${Instant.now()}","datos":{}}"""

        val r = mandar(lote(sinTipo, bien))
        assertEquals(HttpStatusCode.OK, r.status)
        val resultados = Json.parseToJsonElement(r.bodyAsText()).jsonObject.getValue("resultados").jsonArray.map { it.jsonObject }
        assertEquals(listOf("RECHAZADO", "ACEPTADO"), resultados.map { it.getValue("resultado").jsonPrimitive.content })

        assertEquals(HttpStatusCode.Unauthorized, mandar(lote(bien), cuenta = null).status)
        assertEquals(HttpStatusCode.Forbidden, mandar(lote(bien), cuenta = "beto").status, "El teléfono es de Ana")
        assertEquals(HttpStatusCode.BadRequest, mandar("""{"elementos":[$bien]}""").status)
        assertEquals(HttpStatusCode.BadRequest, mandar(lote()).status, "Lote vacío")
        assertEquals(HttpStatusCode.BadRequest, mandar(lote("""{"tipo":"ALERTA","id":"no-uuid"}""")).status)
        assertEquals(HttpStatusCode.PayloadTooLarge, mandar(lote(*Array(201) { sinTipo })).status)
        assertEquals(HttpStatusCode.PayloadTooLarge, mandar(lote("""{"relleno":"${"x".repeat(1_100_000)}"}""")).status)
    }
}
