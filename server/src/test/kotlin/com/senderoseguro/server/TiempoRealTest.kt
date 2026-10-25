package com.senderoseguro.server

import com.senderoseguro.server.desarrollo.AlertasPrueba
import com.senderoseguro.server.dispersion.AtencionServicio
import com.senderoseguro.server.dispersion.CambioEstado
import com.senderoseguro.server.dispersion.ConsultaAlertas
import com.senderoseguro.server.dispersion.EscalamientoServicio
import com.senderoseguro.server.dispersion.ResultadoDispersion
import com.senderoseguro.server.plataforma.EmisorTokens
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.UsuarioToken
import com.senderoseguro.server.tiemporeal.CentroTiempoReal
import com.senderoseguro.server.tiemporeal.Marcador
import com.senderoseguro.server.tiemporeal.configurarWebSockets
import com.senderoseguro.server.tiemporeal.rutasTiempoReal
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** WebSocket de la consola y del enlace de la red de apoyo. */
class TiempoRealTest : PruebaConBaseDatos() {

    private val tokens = EmisorTokens("secreto-ws", "sendero-seguro", "sendero-seguro-api")
    private val consulta by lazy { ConsultaAlertas(dataSource) }
    private val escalamiento by lazy { EscalamientoServicio(dataSource) }
    private val atencion by lazy { AtencionServicio(dataSource, consulta, escalamiento, enlaces) }

    private fun usuario(cuenta: String, rol: Rol) = UsuarioToken(AlertasPrueba.CUENTAS.getValue(cuenta), rol, null, null)
    private fun jwt(cuenta: String, rol: Rol) = tokens.emitir(usuario(cuenta, rol), 1.hours).first

    /** Levanta solo el tiempo real, con vueltas cortas, y entrega un cliente con WebSocket. */
    private fun conServidor(ping: Duration = 25.seconds, bloque: suspend (HttpClient) -> Unit) = testApplication {
        val centro = CentroTiempoReal(dataSource, consulta, enlaces)
        application {
            configurarWebSockets()
            centro.iniciar(this, 100.milliseconds)
            routing { route("/api/v1") { rutasTiempoReal(centro, tokens, ping) } }
        }
        startApplication()
        bloque(createClient { install(WebSockets) })
    }

    private suspend fun HttpClient.abrir(ruta: String): DefaultClientWebSocketSession =
        webSocketSession(ruta).also { delay(400) }   // deja pasar la primera vuelta del centro

    /** El siguiente mensaje de [tipo] sobre la alerta [alertaId] (si se da) que cumpla [condicion], ignorando los demás. */
    private suspend fun DefaultClientWebSocketSession.esperar(
        tipo: String,
        alertaId: UUID? = null,
        condicion: (JsonObject) -> Boolean = { true },
    ): JsonObject =
        withTimeout(5_000) {
            var m: JsonObject
            do {
                m = siguiente() ?: error("Se cerró la conexión esperando $tipo")
            } while (m.tipo != tipo || (alertaId != null && !m.esDe(alertaId)) || !condicion(m.datos))
            m
        }

    private suspend fun DefaultClientWebSocketSession.siguiente(): JsonObject? {
        while (true) {
            val f = incoming.receiveCatching().getOrNull() ?: return null
            if (f is Frame.Text) return Json.parseToJsonElement(f.readText()).jsonObject
        }
    }

    /** Junta lo que llegue durante [ms] milisegundos. */
    private suspend fun DefaultClientWebSocketSession.recibirDurante(ms: Long): List<JsonObject> = buildList {
        withTimeoutOrNull(ms) { while (true) add(siguiente() ?: break) }
    }

    private val JsonObject.tipo get() = getValue("tipo").jsonPrimitive.content
    private val JsonObject.datos get() = getValue("datos").jsonObject
    private fun JsonObject.esDe(alertaId: UUID) =
        (datos["alertaId"] ?: datos["id"])?.jsonPrimitive?.content == alertaId.toString()

    private fun latitud(ubicacion: JsonObject) = ubicacion.getValue("latitud").jsonPrimitive.content.toDouble()

    private fun alertaEnCaseta(): UUID = alerta().also {
        assertEquals("Caseta ESCOM", assertIs<ResultadoDispersion.Asignada>(dispersion.despacharAhora(it)).unidadNombre)
    }

    private fun nuevaUbicacion(alertaId: UUID) = sql(
        """INSERT INTO ubicacion_alerta (alerta_id, latitud, longitud, precision_m, fuente, capturada_en)
           VALUES (?, 19.5051, -99.1470, 8, 'GNSS', now())""",
        alertaId,
    )

    @Test
    fun `la consola rechaza sin token, con token falso o con rol de la app`() = conServidor { cliente ->
        for (ruta in listOf(
            "/api/v1/ws/consola",
            "/api/v1/ws/consola?token=no-es-un-jwt",
            "/api/v1/ws/consola?token=${EmisorTokens("otra-llave", "sendero-seguro", "sendero-seguro-api").emitir(usuario("admin", Rol.ADMINISTRADOR), 1.hours).first}",
            "/api/v1/ws/consola?token=${jwt("ana", Rol.INTEGRANTE)}",
        )) {
            val ws = cliente.webSocketSession(ruta)
            val cierre = withTimeout(3_000) { ws.closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, cierre?.code, ruta)
        }
    }

    @Test
    fun `el operador recibe la alerta de su unidad con estado, ubicacion y escalamiento`() = conServidor { cliente ->
        val caseta = cliente.abrir("/api/v1/ws/consola?token=${jwt("caseta", Rol.OPERADOR)}")
        val c5 = cliente.abrir("/api/v1/ws/consola?token=${jwt("c5", Rol.OPERADOR)}")
        val admin = cliente.abrir("/api/v1/ws/consola?token=${jwt("admin", Rol.ADMINISTRADOR)}")

        val id = alertaEnCaseta()
        val nueva = caseta.esperar("alerta.nueva", id)
        assertEquals("ASIGNADA", nueva.datos.getValue("estado").jsonPrimitive.content)
        val estado = caseta.esperar("alerta.estado", id).datos
        assertEquals("ENVIADA", estado.getValue("estadoAnterior").jsonPrimitive.content)
        assertEquals("ASIGNADA", estado.getValue("estadoNuevo").jsonPrimitive.content)
        admin.esperar("alerta.nueva", id)
        admin.esperar("alerta.estado", id)

        // La lectura con que se registró la alerta pudo llegar antes de la asignación (va en alerta.nueva); se espera la nueva.
        nuevaUbicacion(id)
        caseta.esperar("alerta.ubicacion", id) { latitud(it.getValue("ubicacion").jsonObject) == 19.5051 }

        // Al vencer el plazo, la Caseta (que tuvo la alerta) ve el escalamiento.
        AlertasPrueba.vencerPlazo(dataSource, id)
        escalamiento.escalarPorFaltaDeAcuse(id)
        val esc = caseta.esperar("alerta.escalada", id).datos.getValue("escalamiento").jsonObject
        assertEquals("1", esc.getValue("nivel").jsonPrimitive.content)
        assertEquals("SIN_ACUSE", esc.getValue("motivo").jsonPrimitive.content)

        // C5 nunca tuvo la alerta: no recibe nada de ella... salvo que el escalamiento se la haya asignado.
        val tuvoC5 = numero(
            """SELECT count(*) FROM asignacion s JOIN operador o ON o.unidad_id = s.unidad_id
               WHERE s.alerta_id = ? AND o.usuario_id = ?""",
            id, AlertasPrueba.CUENTAS.getValue("c5"),
        ) > 0
        if (!tuvoC5) assertTrue(c5.recibirDurante(800).none { it.esDe(id) })
    }

    @Test
    fun `el operador no recibe alertas de otras unidades`() = conServidor { cliente ->
        val pbi = cliente.abrir("/api/v1/ws/consola?token=${jwt("pbi", Rol.OPERADOR)}")
        val id = alertaEnCaseta()
        nuevaUbicacion(id)
        assertTrue(pbi.recibirDurante(1_000).none { it.esDe(id) })
    }

    @Test
    fun `el enlace manda estado y ubicacion y termina con fin al cerrar la alerta`() = conServidor { cliente ->
        val id = alertaEnCaseta()
        val token = enlaces.tokenDeAlerta(id)!!
        val ws = cliente.webSocketSession("/api/v1/ws/enlace/$token")
        assertEquals("Unidad asignada", ws.esperar("estado").datos.getValue("estado").jsonPrimitive.content)
        ws.esperar("ubicacion")
        delay(400)

        nuevaUbicacion(id)
        ws.esperar("ubicacion") { latitud(it) == 19.5051 }

        val caseta = usuario("caseta", Rol.OPERADOR)
        atencion.acusar(caseta, id)
        assertEquals("Recibida por la unidad", ws.esperar("estado").datos.getValue("estado").jsonPrimitive.content)
        atencion.cambiarEstado(caseta, id, CambioEstado("EN_ATENCION"))
        atencion.cambiarEstado(caseta, id, CambioEstado("ATENDIDA"))
        atencion.cambiarEstado(caseta, id, CambioEstado("CERRADA", "Se acompañó a la persona a la caseta"))
        assertEquals("CERRADA", ws.esperar("fin").datos.getValue("motivo").jsonPrimitive.content)
        assertEquals(CloseReason.Codes.NORMAL.code, withTimeout(3_000) { ws.closeReason.await() }?.code)
    }

    @Test
    fun `al cancelar la alerta la consola ve CANCELADA y el enlace recibe fin`() = conServidor { cliente ->
        val caseta = cliente.abrir("/api/v1/ws/consola?token=${jwt("caseta", Rol.OPERADOR)}")
        val id = alertaEnCaseta()
        val red = cliente.abrir("/api/v1/ws/enlace/${enlaces.tokenDeAlerta(id)!!}")
        caseta.esperar("alerta.nueva", id)

        // Lo que hace el Módulo 1 al cancelar (CU-21), sin registrar el evento él mismo.
        sql("UPDATE alerta SET estado = 'CANCELADA', cancelada_en = now(), motivo_cancelacion = 'FALSA_ALARMA' WHERE id = ?", id)
        dispersion.cancelada(id)
        dispersion.cancelada(id)

        val cambio = caseta.esperar("alerta.estado", id) { it.getValue("estadoNuevo").jsonPrimitive.content == "CANCELADA" }.datos
        assertEquals("ASIGNADA", cambio.getValue("estadoAnterior").jsonPrimitive.content)
        assertEquals("CANCELADA", red.esperar("fin").datos.getValue("motivo").jsonPrimitive.content)
        assertEquals(1, numero("SELECT count(*) FROM evento_alerta WHERE alerta_id = ? AND estado_nuevo = 'CANCELADA'", id))
    }

    @Test
    fun `un aviso descartado llega solo a los administradores`() = conServidor { cliente ->
        val caseta = cliente.abrir("/api/v1/ws/consola?token=${jwt("caseta", Rol.OPERADOR)}")
        val admin = cliente.abrir("/api/v1/ws/consola?token=${jwt("admin", Rol.ADMINISTRADOR)}")
        val id = alertaEnCaseta()
        sql(
            """INSERT INTO evento_alerta (alerta_id, estado_anterior, estado_nuevo, detalle)
               VALUES (?, 'ASIGNADA', 'ASIGNADA', '{"accion":"ENTREGA_DESCARTADA","notificacionId":7,"tipo":"RED_APOYO","canal":"SMS","error":"Número no disponible"}')""",
            id,
        )
        val m = admin.esperar("alerta.entrega_fallida", id).datos
        assertEquals("RED_APOYO", m.getValue("tipo").jsonPrimitive.content)
        assertEquals("7", m.getValue("notificacionId").jsonPrimitive.content)
        assertTrue(caseta.recibirDurante(800).none { it.tipo == "alerta.entrega_fallida" })
    }

    @Test
    fun `el enlace vencido avisa fin VENCIDA y uno que no existe se rechaza`() = conServidor { cliente ->
        val id = alertaEnCaseta()
        val ws = cliente.abrir("/api/v1/ws/enlace/${enlaces.tokenDeAlerta(id)!!}")
        sql("UPDATE enlace_ubicacion SET vence_en = now() WHERE alerta_id = ?", id)
        assertEquals("VENCIDA", ws.esperar("fin").datos.getValue("motivo").jsonPrimitive.content)

        // Ya vencido, o con un token inventado, ni siquiera se abre.
        for (token in listOf(enlaces.token(UUID.randomUUID()), "corto")) {
            val otro = cliente.webSocketSession("/api/v1/ws/enlace/$token")
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, withTimeout(3_000) { otro.closeReason.await() }?.code)
        }
    }

    @Test
    fun `la conexion sigue mientras el cliente contesta pong y se cierra si deja de contestar`() =
        conServidor(ping = 300.milliseconds) { cliente ->
            val ws = cliente.webSocketSession("/api/v1/ws/consola?token=${jwt("admin", Rol.ADMINISTRADOR)}")
            repeat(3) {
                ws.esperar("ping")
                ws.send("""{"tipo":"pong"}""")
            }
            assertNull(ws.closeReason.takeIf { it.isCompleted })
            // Ya no contesta: a los dos pings sin respuesta se cierra.
            val cierre = withTimeout(3_000) {
                while (ws.siguiente() != null) Unit
                ws.closeReason.await()
            }
            assertEquals(CloseReason.Codes.GOING_AWAY.code, cierre?.code)
        }

    @Test
    fun `el marcador no repite filas y tolera ids que llegan tarde`() {
        val m = Marcador()
        m.ponerEn(100)
        assertFalse(m.nuevo(100))           // ya existía al arrancar
        assertTrue(m.nuevo(102))
        assertFalse(m.nuevo(102))
        assertTrue(m.nuevo(101))            // una transacción que guardó después
        assertEquals(100, m.desde())        // sigue mirando desde el piso, dentro de la ventana
        assertTrue(m.nuevo(900))
        assertEquals(400, m.desde())        // la ventana es de 500 ids
    }
}
