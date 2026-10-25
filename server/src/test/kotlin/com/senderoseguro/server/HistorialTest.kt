package com.senderoseguro.server

import com.senderoseguro.server.auditoria.FiltrosHistorial
import com.senderoseguro.server.auditoria.Historial
import com.senderoseguro.server.auditoria.rutasHistorial
import com.senderoseguro.server.desarrollo.AlertasPrueba
import com.senderoseguro.server.plataforma.EmisorTokens
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.UsuarioToken
import com.senderoseguro.server.plataforma.configurarErrores
import com.senderoseguro.server.plataforma.configurarSeguridad
import com.senderoseguro.server.plataforma.configurarSerializacion
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** Historial (P13): el propio de cada quien y el de otra persona, solo para el Administrador y con justificación. */
class HistorialTest : PruebaConBaseDatos() {

    private fun cuenta(nombre: String, rol: Rol) = UsuarioToken(AlertasPrueba.CUENTAS.getValue(nombre), rol, null, null)
    private val ana = cuenta("ana", Rol.INTEGRANTE)
    private val beto = cuenta("beto", Rol.COORDINADOR)
    private val admin = cuenta("admin", Rol.ADMINISTRADOR)
    private val historial get() = Historial(dataSource)

    private val tokens = EmisorTokens("secreto-historial", "sendero-seguro", "sendero-seguro-api")
    private fun jwt(u: UsuarioToken) = tokens.emitir(u, 1.hours).first

    private fun ApplicationTestBuilder.montar() {
        environment {
            config = MapApplicationConfig(
                "jwt.secreto" to "secreto-historial", "jwt.emisor" to "sendero-seguro", "jwt.audiencia" to "sendero-seguro-api",
            )
        }
        application {
            configurarSerializacion()
            configurarErrores()
            configurarSeguridad()
            routing { route("/api/v1") { rutasHistorial(historial) } }
        }
    }

    private suspend fun ApplicationTestBuilder.pedir(ruta: String, u: UsuarioToken, justificacion: String? = null): HttpResponse =
        client.get("/api/v1$ruta") {
            header("Authorization", "Bearer ${jwt(u)}")
            justificacion?.let { header("X-Justificacion", it) }
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    /** Un trayecto terminado de Ana. Se borra al final de la prueba. */
    private fun trayecto(): UUID = UUID.randomUUID().also {
        sql(
            """INSERT INTO trayecto (id, usuario_id, origen_latitud, origen_longitud, destino_latitud, destino_longitud,
                                     estado, iniciado_en, finalizado_en)
               VALUES (?, ?, 19.5045, -99.1469, 19.5000, -99.1400, 'FINALIZADO', now(), now())""",
            it, AlertasPrueba.ANA,
        )
    }

    @Test
    fun `cada quien ve sus alertas y trayectos, del mas nuevo al mas viejo, con filtros y cursor`() {
        val desde = Instant.now()
        val primera = alerta()
        Thread.sleep(20)
        val segunda = alerta()
        Thread.sleep(20)
        val viaje = trayecto()
        try {
            val todo = historial.propio(ana, FiltrosHistorial(desde = desde)).elementos
            assertEquals(listOf(viaje, segunda, primera).map { it.toString() }, todo.map { it.id })
            assertEquals(listOf("TRAYECTO", "ALERTA", "ALERTA"), todo.map { it.tipo })
            assertEquals(JsonNull, todo[0].referencia, "Los trayectos no tienen folio")
            assertTrue(todo[1].referencia.jsonPrimitive.content.startsWith("ALR-"))

            val soloAlertas = historial.propio(ana, FiltrosHistorial(tipo = "ALERTA", desde = desde, limite = 1))
            assertEquals(segunda.toString(), soloAlertas.elementos.single().id)
            assertTrue(soloAlertas.siguienteCursor != JsonNull)

            assertTrue(historial.propio(beto, FiltrosHistorial(desde = desde)).elementos.isEmpty(), "Beto no ve lo de Ana")
        } finally {
            sql("DELETE FROM trayecto WHERE id = ?", viaje)
        }
    }

    @Test
    fun `por la ruta, el cursor trae la pagina siguiente`() = testApplication {
        montar()
        val desde = Instant.now()
        val primera = alerta()
        Thread.sleep(20)
        val segunda = alerta()
        val p1 = pedir("/historial?tipo=alerta&desde=$desde&limite=1", ana).json()
        assertEquals(segunda.toString(), p1.getValue("elementos").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        val cursor = p1.getValue("siguienteCursor").jsonPrimitive.content
        val p2 = pedir("/historial?tipo=alerta&desde=$desde&limite=1&cursor=$cursor", ana).json()
        assertEquals(primera.toString(), p2.getValue("elementos").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(JsonNull, p2.getValue("siguienteCursor"))

        assertEquals(HttpStatusCode.BadRequest, pedir("/historial?tipo=PERRO", ana).status)
        assertEquals(HttpStatusCode.BadRequest, pedir("/historial?cursor=basura", ana).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/historial").status)
    }

    @Test
    fun `con el PIN de coaccion Ana ve su alerta CANCELADA y el Administrador ve la verdad`() {
        val desde = Instant.now()
        val id = alerta()
        sql("UPDATE alerta SET coaccion_detectada = true WHERE id = ?", id)
        assertEquals("CANCELADA", historial.propio(ana, FiltrosHistorial(desde = desde)).elementos.single().estado)
        val real = historial.ajeno(admin, AlertasPrueba.ANA, "Revision de una alerta con coaccion", FiltrosHistorial(desde = desde), null)
        assertTrue(real.elementos.single().estado != "CANCELADA")
    }

    @Test
    fun `el historial de otra persona es solo del Administrador, con justificacion, y queda en la bitacora`() = testApplication {
        montar()
        val ruta = "/usuarios/${AlertasPrueba.ANA}/historial"
        val motivo = "Seguimiento del folio ALR-2026-000123 " + UUID.randomUUID()

        assertEquals(HttpStatusCode.OK, pedir(ruta, admin, motivo).status)
        val registro = dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT usuario_id, entidad_id, detalle->>'justificacion' FROM bitacora WHERE accion = 'CONSULTAR_HISTORIAL_AJENO' AND detalle->>'justificacion' = ?",
            ).use { ps ->
                ps.setString(1, motivo)
                ps.executeQuery().use { rs -> assertTrue(rs.next(), "Quedó en la bitácora"); listOf(rs.getString(1), rs.getString(2)) }
            }
        }
        assertEquals(listOf(admin.usuarioId.toString(), AlertasPrueba.ANA.toString()), registro)

        assertEquals(HttpStatusCode.BadRequest, pedir(ruta, admin).status, "Sin justificación")
        assertEquals(HttpStatusCode.BadRequest, pedir(ruta, admin, "porque si").status, "Menos de 10 caracteres")
        assertEquals(HttpStatusCode.Forbidden, pedir(ruta, cuenta("caseta", Rol.OPERADOR), motivo).status)
        assertEquals(HttpStatusCode.Forbidden, pedir(ruta, beto, motivo).status)
        assertEquals(HttpStatusCode.NotFound, pedir("/usuarios/${UUID.randomUUID()}/historial", admin, motivo).status)
        assertEquals(
            0, numero("SELECT count(*) FROM bitacora WHERE accion = 'CONSULTAR_HISTORIAL_AJENO' AND detalle->>'justificacion' = 'porque si'"),
            "Una consulta rechazada no se registra",
        )
    }
}
