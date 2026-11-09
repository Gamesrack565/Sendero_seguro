package com.senderoseguro.server

import com.senderoseguro.server.auditoria.rutasBitacora
import com.senderoseguro.server.auditoria.verificarBitacora
import com.senderoseguro.server.desarrollo.AlertasPrueba
import com.senderoseguro.server.plataforma.Bitacora
import com.senderoseguro.server.plataforma.EmisorTokens
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.UsuarioToken
import com.senderoseguro.server.plataforma.configurarErrores
import com.senderoseguro.server.plataforma.configurarSeguridad
import com.senderoseguro.server.plataforma.configurarSerializacion
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.transaccion
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** Bitácora (P13): escritura encadenada, consulta del Administrador y verificación de la cadena. */
class BitacoraTest : PruebaConBaseDatos() {

    private val tokens = EmisorTokens("secreto-bitacora", "sendero-seguro", "sendero-seguro-api")
    private val admin = AlertasPrueba.CUENTAS.getValue("admin")

    private fun jwt(cuenta: String, rol: Rol) =
        tokens.emitir(UsuarioToken(AlertasPrueba.CUENTAS.getValue(cuenta), rol, null, null), 1.hours).first

    private fun ApplicationTestBuilder.montar() {
        environment {
            config = MapApplicationConfig(
                "jwt.secreto" to "secreto-bitacora", "jwt.emisor" to "sendero-seguro", "jwt.audiencia" to "sendero-seguro-api",
            )
        }
        application {
            configurarSerializacion()
            configurarErrores()
            configurarSeguridad()
            routing { route("/api/v1") { rutasBitacora(dataSource) } }
        }
    }

    private suspend fun ApplicationTestBuilder.pedir(ruta: String, jwt: String? = jwt("admin", Rol.ADMINISTRADOR)): HttpResponse =
        client.get("/api/v1$ruta") { jwt?.let { header("Authorization", "Bearer $it") } }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    /** Escribe [n] registros de prueba con una acción única y regresa esa acción. */
    private fun escribir(n: Int): String {
        val accion = "PRUEBA_" + UUID.randomUUID().toString().take(8).uppercase()
        dataSource.transaccion { conn ->
            repeat(n) { i ->
                Bitacora.registrar(
                    conn, accion, admin, entidad = "alerta", entidadId = "id-$i",
                    detalle = buildJsonObject { put("numero", i) }, ip = if (i == 0) "192.168.1.20" else "no-es-ip",
                )
            }
        }
        return accion
    }

    @Test
    fun `cada registro queda encadenado al anterior`() {
        val accion = escribir(2)
        val (primero, segundo) = dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT id, hash, hash_prev FROM bitacora WHERE accion = ? ORDER BY id").use { ps ->
                ps.setString(1, accion)
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(Triple(rs.getLong(1), rs.getBytes(2), rs.getBytes(3))) }
                }
            }
        }
        assertTrue(segundo.third.contentEquals(primero.second), "hash_prev del segundo = hash del primero")
        assertEquals(32, primero.second.size)
    }

    @Test
    fun `el Administrador consulta del mas nuevo al mas viejo, con filtros y cursor`() = testApplication {
        montar()
        val accion = escribir(3)
        val p1 = pedir("/bitacora?accion=${accion.lowercase()}&limite=2").json()
        val elementos = p1.getValue("elementos").jsonArray.map { it.jsonObject }
        assertEquals(listOf("id-2", "id-1"), elementos.map { it.getValue("entidadId").jsonPrimitive.content })
        assertEquals(admin.toString(), elementos[0].getValue("usuarioId").jsonPrimitive.content)
        assertEquals("2", elementos[0].getValue("detalle").jsonObject.getValue("numero").jsonPrimitive.content)
        assertEquals(64, elementos[0].getValue("hash").jsonPrimitive.content.length)
        assertFalse("ip" in elementos[0], "Un texto que no es IP no se guarda")

        val cursor = p1.getValue("siguienteCursor").jsonPrimitive.content
        val p2 = pedir("/bitacora?accion=$accion&limite=2&cursor=$cursor").json()
        val ultimo = p2.getValue("elementos").jsonArray.single().jsonObject
        assertEquals("id-0", ultimo.getValue("entidadId").jsonPrimitive.content)
        assertEquals("192.168.1.20", ultimo.getValue("ip").jsonPrimitive.content)
        assertEquals(JsonNull, p2.getValue("siguienteCursor"))

        assertEquals(1, pedir("/bitacora?accion=$accion&entidadId=id-1").json().getValue("elementos").jsonArray.size)
        assertEquals(HttpStatusCode.BadRequest, pedir("/bitacora?cursor=basura").status)
        assertEquals(HttpStatusCode.BadRequest, pedir("/bitacora?desde=ayer").status)
    }

    @Test
    fun `solo el Administrador, y con sesion`() = testApplication {
        montar()
        assertEquals(HttpStatusCode.Forbidden, pedir("/bitacora", jwt("caseta", Rol.OPERADOR)).status)
        assertEquals(HttpStatusCode.Forbidden, pedir("/bitacora/verificar", jwt("ana", Rol.INTEGRANTE)).status)
        assertEquals(HttpStatusCode.Unauthorized, pedir("/bitacora", null).status)
    }

    @Test
    fun `la verificacion dice si la cadena esta integra y detecta un registro alterado`() = testApplication {
        montar()
        escribir(1)
        val r = pedir("/bitacora/verificar").json()
        assertEquals("true", r.getValue("integra").jsonPrimitive.content)
        assertEquals(JsonNull, r.getValue("primerIdAlterado"))
        assertTrue(r.getValue("registrosRevisados").jsonPrimitive.content.toLong() >= 1)

        // Se altera un registro dentro de una transacción que se deshace: la base real no cambia.
        val accion = escribir(1)
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.ejecutar("ALTER TABLE bitacora DISABLE TRIGGER trg_bitacora_inmutable")
                conn.ejecutar("UPDATE bitacora SET detalle = '{\"numero\": 99}' WHERE accion = ?", accion)
                val v = verificarBitacora(conn)
                assertFalse(v.integra)
                assertEquals(
                    dataSource.connection.use { c ->
                        c.prepareStatement("SELECT id FROM bitacora WHERE accion = ?").use { ps ->
                            ps.setString(1, accion); ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                        }
                    },
                    v.primerIdAlterado.jsonPrimitive.content.toLong(),
                )
            } finally {
                conn.rollback()
                conn.autoCommit = true
            }
        }
        assertEquals("true", pedir("/bitacora/verificar").json().getValue("integra").jsonPrimitive.content)
    }
}
