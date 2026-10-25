package com.senderoseguro.server

import com.senderoseguro.server.avisos.rutasDispositivos
import com.senderoseguro.server.desarrollo.AlertasPrueba
import com.senderoseguro.server.plataforma.EmisorTokens
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.UsuarioToken
import com.senderoseguro.server.plataforma.configurarErrores
import com.senderoseguro.server.plataforma.configurarSeguridad
import com.senderoseguro.server.plataforma.configurarSerializacion
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours

/** `PUT /dispositivos/{id}/push`: la app registra el token de FCM de su teléfono. */
class DispositivosTest : PruebaConBaseDatos() {

    private val tokens = EmisorTokens("secreto-dispositivos", "sendero-seguro", "sendero-seguro-api")
    private val telefonoAna = "00000000-0000-4000-8000-0000000000d1"
    private val telefonoBeto = "00000000-0000-4000-8000-0000000000d2"
    private val tokenNuevo = "token-fcm-nuevo-de-ana-0123456789"

    private fun jwt(cuenta: String, rol: Rol) =
        tokens.emitir(UsuarioToken(AlertasPrueba.CUENTAS.getValue(cuenta), rol, null, null), 1.hours).first

    private fun ApplicationTestBuilder.montar() {
        environment {
            config = MapApplicationConfig(
                "jwt.secreto" to "secreto-dispositivos", "jwt.emisor" to "sendero-seguro", "jwt.audiencia" to "sendero-seguro-api",
            )
        }
        application {
            configurarSerializacion()
            configurarErrores()
            configurarSeguridad()
            routing { route("/api/v1") { rutasDispositivos(dataSource) } }
        }
    }

    private suspend fun ApplicationTestBuilder.registrar(dispositivo: String, cuerpo: String, jwt: String?) =
        client.put("/api/v1/dispositivos/$dispositivo/push") {
            jwt?.let { header("Authorization", "Bearer $it") }
            contentType(ContentType.Application.Json)
            setBody(cuerpo)
        }.status

    @Test
    fun `el dueno registra su token y se le quita a otro telefono que lo tuviera`() = testApplication {
        montar()
        sql(
            """INSERT INTO dispositivo (id, usuario_id, modelo, android_api, fcm_token) VALUES (?::uuid, ?, 'Prueba', 34, ?)
               ON CONFLICT (id) DO UPDATE SET fcm_token = EXCLUDED.fcm_token""",
            telefonoBeto, AlertasPrueba.CUENTAS.getValue("beto"), tokenNuevo,
        )
        try {
            assertEquals(HttpStatusCode.NoContent, registrar(telefonoAna, """{"fcmToken":"$tokenNuevo"}""", jwt("ana", Rol.INTEGRANTE)))
            assertEquals(tokenNuevo, texto("SELECT fcm_token FROM dispositivo WHERE id = ?::uuid", telefonoAna))
            assertNull(texto("SELECT fcm_token FROM dispositivo WHERE id = ?::uuid", telefonoBeto))
        } finally {
            sql("DELETE FROM dispositivo WHERE id = ?::uuid", telefonoBeto)
            sql("UPDATE dispositivo SET fcm_token = 'token-fcm-de-prueba-ana' WHERE id = ?::uuid", telefonoAna)
        }
    }

    @Test
    fun `rechaza otra cuenta, dispositivo inexistente, token corto y sin sesion`() = testApplication {
        montar()
        val cuerpo = """{"fcmToken":"$tokenNuevo"}"""
        assertEquals(HttpStatusCode.Forbidden, registrar(telefonoAna, cuerpo, jwt("beto", Rol.COORDINADOR)))
        assertEquals(HttpStatusCode.NotFound, registrar("00000000-0000-4000-8000-0000000000ff", cuerpo, jwt("ana", Rol.INTEGRANTE)))
        assertEquals(HttpStatusCode.BadRequest, registrar(telefonoAna, """{"fcmToken":"corto"}""", jwt("ana", Rol.INTEGRANTE)))
        assertEquals(HttpStatusCode.BadRequest, registrar(telefonoAna, """{}""", jwt("ana", Rol.INTEGRANTE)))
        assertEquals(HttpStatusCode.Unauthorized, registrar(telefonoAna, cuerpo, null))
        assertEquals("token-fcm-de-prueba-ana", texto("SELECT fcm_token FROM dispositivo WHERE id = ?::uuid", telefonoAna))
    }
}
