package com.senderoseguro.server

import com.senderoseguro.server.alertas.RegistroAlertas
import com.senderoseguro.server.cancelacion.CancelacionServicio
import com.senderoseguro.server.catalogo.CatalogoServicio
import com.senderoseguro.server.catalogo.rutasCatalogo
import com.senderoseguro.server.desarrollo.AlertasPrueba
import com.senderoseguro.server.dispersion.AcusesSinSenal
import com.senderoseguro.server.dispersion.ResultadoDispersion
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.configurarErrores
import com.senderoseguro.server.plataforma.configurarSeguridad
import com.senderoseguro.server.plataforma.configurarSerializacion
import com.senderoseguro.server.sincronizacion.ContextoSync
import com.senderoseguro.server.sincronizacion.ElementoSync
import com.senderoseguro.server.sincronizacion.Sincronizacion
import com.senderoseguro.server.sincronizacion.TipoSync
import com.senderoseguro.server.trayectos.CheckinEntrada
import com.senderoseguro.server.trayectos.TrayectoCambio
import com.senderoseguro.server.trayectos.TrayectoEntrada
import com.senderoseguro.server.trayectos.TrayectosServicio
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Catálogo para la app y lo que la cola sin señal ya procesa: acuse del operador, cancelación, trayecto y check-in. */
class ColaSinSenalTest : PruebaConBaseDatos() {

    private val dispositivoAna = UUID.fromString("00000000-0000-4000-8000-0000000000d1")
    private val ana = ContextoSync(AlertasPrueba.ANA, dispositivoAna)
    private val caseta = ContextoSync(AlertasPrueba.CUENTAS.getValue("caseta"), dispositivoAna)
    private val c5 = ContextoSync(AlertasPrueba.CUENTAS.getValue("c5"), dispositivoAna)
    private val beto = AlertasPrueba.CUENTAS.getValue("beto")
    private val trayectos by lazy { TrayectosServicio(dataSource) }

    private fun sincronizacion() = Sincronizacion(
        dataSource, dispersion,
        mapOf(
            TipoSync.ALERTA to RegistroAlertas(dataSource, dispersion).manejadorSync,
            TipoSync.ACUSE to AcusesSinSenal().manejadorSync,
            TipoSync.CANCELACION to CancelacionServicio(dataSource, dispersion).manejadorSync,
            TipoSync.TRAYECTO to trayectos.manejadorTrayecto,
            TipoSync.CHECKIN to trayectos.manejadorCheckin,
        ),
    )

    /** Una alerta de Ana asignada a la Caseta ESCOM. Regresa su id y la hora de activación. */
    private fun alertaEnCaseta(): Pair<UUID, Instant> {
        val id = alerta()
        assertEquals("Caseta ESCOM", assertIs<ResultadoDispersion.Asignada>(dispersion.despacharAhora(id)).unidadNombre)
        // Activada hace 2 minutos, para que un acuse "40 s después" no quede en el futuro.
        sql("UPDATE alerta SET activada_en = activada_en - interval '2 minutes' WHERE id = ?", id)
        return id to dataSource.connection.use { c ->
            c.prepareStatement("SELECT activada_en FROM alerta WHERE id = ?").use { ps -> ps.setObject(1, id); ps.executeQuery().use { it.next(); it.getTimestamp(1).toInstant() } }
        }
    }

    private fun acuse(alertaId: UUID, activadaEn: Instant, acusadaEn: Instant) = ElementoSync(
        TipoSync.ACUSE, UUID.randomUUID(), acusadaEn,
        buildJsonObject {
            put("idCortoAlerta", alertaId.toString().take(8))
            put("idCortoUsuario", AlertasPrueba.ANA.toString().replace("-", "").takeLast(8))
            put("activadaEn", activadaEn.toString())
            put("acusadaEn", acusadaEn.toString())
        },
    )

    @Test
    fun `la app descarga el catalogo vigente y si ya lo tiene recibe 304`() = testApplication {
        environment {
            config = MapApplicationConfig("jwt.secreto" to "s", "jwt.emisor" to "e", "jwt.audiencia" to "a")
        }
        application {
            configurarSerializacion(); configurarErrores(); configurarSeguridad()
            routing { route("/api/v1") { rutasCatalogo(CatalogoServicio(dataSource)) } }
        }
        val r = client.get("/api/v1/catalogo?aplicaA=ALERTA")
        assertEquals(HttpStatusCode.OK, r.status)
        val cuerpo = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        val tipos = cuerpo["tipos"]!!.jsonArray.map { it.jsonObject }
        assertTrue(tipos.any { it["codigo"]!!.jsonPrimitive.content == "GENERICA" })
        // ZONA_SIN_ILUMINACION es solo de reporte; VENTA_SUSTANCIAS está inactivo.
        assertTrue(tipos.none { it["codigo"]!!.jsonPrimitive.content in setOf("ZONA_SIN_ILUMINACION", "VENTA_SUSTANCIAS") })
        val numero = cuerpo["numero"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.NotModified, client.get("/api/v1/catalogo?version=$numero").status)
    }

    @Test
    fun `el acuse sin senal de la unidad asignada guarda la hora del telefono y no se duplica`() {
        val (id, activadaEn) = alertaEnCaseta()
        val acusadaEn = activadaEn.plusSeconds(40)
        val r = sincronizacion().procesar(caseta, listOf(acuse(id, activadaEn, acusadaEn))).single()
        assertEquals("ACEPTADO", r.resultado, r.toString())
        assertEquals("ACUSADA", texto("SELECT estado::text FROM alerta WHERE id = ?", id))
        assertEquals(40, numero("SELECT extract(epoch FROM acusada_en - activada_en)::int FROM alerta WHERE id = ?", id))
        assertEquals(1, numero("SELECT count(*) FROM notificacion WHERE alerta_id = ? AND tipo = 'ACUSE'", id))

        assertEquals("DUPLICADO", sincronizacion().procesar(caseta, listOf(acuse(id, activadaEn, acusadaEn))).single().resultado)
    }

    @Test
    fun `una unidad cercana que acusa por la malla toma la alerta y libera a la asignada`() {
        val (id, activadaEn) = alertaEnCaseta()
        val r = sincronizacion().procesar(c5, listOf(acuse(id, activadaEn, Instant.now()))).single()
        assertEquals("ACEPTADO", r.resultado, r.toString())
        assertEquals("ACUSADA", texto("SELECT estado::text FROM alerta WHERE id = ?", id))
        assertEquals("Patrulla Lindavista", texto(
            "SELECT u.nombre FROM asignacion a JOIN unidad_atencion u ON u.id = a.unidad_id WHERE a.alerta_id = ? AND a.liberada_en IS NULL", id))
        assertEquals("DISPONIBLE", texto("SELECT estado::text FROM unidad_atencion WHERE nombre = 'Caseta ESCOM'"))

        // Si después acusa la Caseta, queda como apoyo y la alerta no cambia.
        assertEquals("ACEPTADO", sincronizacion().procesar(caseta, listOf(acuse(id, activadaEn, Instant.now()))).single().resultado)
        assertEquals(1, numero("SELECT count(*) FROM evento_alerta WHERE alerta_id = ? AND detalle->>'accion' = 'ACUSE_APOYO'", id))
    }

    @Test
    fun `un acuse de una alerta que todavia no llega se reintenta y una persona no operadora no acusa`() {
        val falsa = UUID.randomUUID()
        val r = sincronizacion().procesar(caseta, listOf(acuse(falsa, Instant.now().minusSeconds(30), Instant.now()))).single()
        assertEquals("RECHAZADO", r.resultado)
        assertEquals("true", r.error!!.detalle!!["temporal"]!!.jsonPrimitive.content)

        val (id, activadaEn) = alertaEnCaseta()
        val deAna = sincronizacion().procesar(ana, listOf(acuse(id, activadaEn, Instant.now()))).single()
        assertEquals("SIN_PERMISO", deAna.error!!.codigo)
    }

    @Test
    fun `la cancelacion guardada sin senal cancela la alerta`() {
        val (id, _) = alertaEnCaseta()
        val e = ElementoSync(TipoSync.CANCELACION, UUID.randomUUID(), Instant.now().minusSeconds(5), buildJsonObject {
            put("alertaId", id.toString()); put("metodo", "PIN"); put("exitoso", true); put("motivo", "FALSA_ALARMA")
        })
        assertEquals("ACEPTADO", sincronizacion().procesar(ana, listOf(e)).single().resultado)
        assertEquals("CANCELADA", texto("SELECT estado::text FROM alerta WHERE id = ?", id))
        // La unidad se libera después del commit.
        repeat(30) { if (texto("SELECT estado::text FROM unidad_atencion WHERE nombre = 'Caseta ESCOM'") == "DISPONIBLE") return; Thread.sleep(100) }
        error("La Caseta no quedó DISPONIBLE")
    }

    @Test
    fun `un trayecto, su check-in y la alerta de su temporizador llegan juntos en el lote`() {
        val trayecto = UUID.randomUUID()
        val alertaId = UUID.randomUUID()
        val inicio = Instant.now().minus(20, ChronoUnit.MINUTES)
        val datosTrayecto = buildJsonObject {
            put("id", trayecto.toString()); put("origenLatitud", 19.5045); put("origenLongitud", -99.1469)
            put("destinoLatitud", 19.5000); put("destinoLongitud", -99.1400); put("iniciadoEn", inicio.toString())
            put("intervaloCheckinMin", 5); put("estado", "FINALIZADO"); put("finalizadoEn", inicio.plusSeconds(900).toString())
            put("ultimaLatitud", 19.501); put("ultimaLongitud", -99.141); put("ultimaUbicacionEn", inicio.plusSeconds(800).toString())
        }
        val datosAlerta = buildJsonObject {
            put("id", alertaId.toString()); put("activadaEn", inicio.plusSeconds(600).toString()); put("detonador", "TEMPORIZADOR")
            put("trayectoId", trayecto.toString())
            putJsonObject("ubicacion") { put("latitud", 19.502); put("longitud", -99.142) }
        }
        val lote = listOf(
            ElementoSync(TipoSync.ALERTA, alertaId, inicio.plusSeconds(600), datosAlerta),
            ElementoSync(TipoSync.CHECKIN, UUID.randomUUID(), inicio.plusSeconds(300), buildJsonObject {
                put("trayectoId", trayecto.toString()); put("esperadoEn", inicio.plusSeconds(300).toString())
                put("confirmadoEn", inicio.plusSeconds(320).toString())
            }),
            ElementoSync(TipoSync.TRAYECTO, trayecto, inicio, datosTrayecto),
        )
        val r = sincronizacion().procesar(ana, lote)
        assertEquals(listOf("ACEPTADO", "ACEPTADO", "ACEPTADO"), r.map { it.resultado }, r.toString())
        assertEquals(trayecto.toString(), texto("SELECT trayecto_id::text FROM alerta WHERE id = ?", alertaId))
        assertEquals("FINALIZADO", texto("SELECT estado::text FROM trayecto WHERE id = ?", trayecto))
        assertEquals(1, numero("SELECT count(*) FROM checkin_trayecto WHERE trayecto_id = ? AND confirmado_en IS NOT NULL", trayecto))

        // Reenviar todo no duplica nada.
        assertEquals(listOf("DUPLICADO", "DUPLICADO", "DUPLICADO"), sincronizacion().procesar(ana, lote).map { it.resultado })
    }

    @Test
    fun `compartir el trayecto avisa a la red de apoyo con app y solo ellos lo ven`() {
        val id = UUID.randomUUID()
        val (t, nuevo) = trayectos.iniciar(AlertasPrueba.ANA, TrayectoEntrada(
            id = id.toString(), origenLatitud = 19.5045, origenLongitud = -99.1469, destinoLatitud = 19.5, destinoLongitud = -99.14,
            iniciadoEn = Instant.now().toString(), compartir = true, rutaPlanificada = JsonObject(mapOf("type" to kotlinx.serialization.json.JsonPrimitive("LineString"))),
        ), null)
        assertTrue(nuevo)
        assertEquals("EN_CURSO", t.estado)
        assertEquals(1, numero("SELECT count(*) FROM notificacion WHERE trayecto_id = ? AND usuario_id = ? AND tipo = 'TRAYECTO_COMPARTIDO'", id, beto))

        assertEquals(id.toString(), trayectos.consultar(beto, id.toString()).id)
        val extraño = AlertasPrueba.CUENTAS.getValue("c5")
        assertEquals("NO_ENCONTRADO", assertFailsWith<ApiException> { trayectos.consultar(extraño, id.toString()) }.codigo)

        trayectos.checkin(AlertasPrueba.ANA, id.toString(), CheckinEntrada(Instant.now().toString(), Instant.now().toString()))
        val fin = trayectos.cambiar(AlertasPrueba.ANA, id.toString(), TrayectoCambio(estado = "FINALIZADO", latitud = 19.5, longitud = -99.14))
        assertEquals("FINALIZADO", fin.estado)
        assertEquals(1, fin.checkins.size)
        assertEquals("TRANSICION_INVALIDA", assertFailsWith<ApiException> {
            trayectos.cambiar(AlertasPrueba.ANA, id.toString(), TrayectoCambio(estado = "CANCELADO"))
        }.codigo)
    }
}
