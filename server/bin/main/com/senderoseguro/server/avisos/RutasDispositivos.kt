package com.senderoseguro.server.avisos

import com.senderoseguro.server.plataforma.AUTH_JWT
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.transaccion
import com.senderoseguro.server.plataforma.usuarioActual
import com.senderoseguro.server.plataforma.uuid
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.sql.DataSource

@Serializable
private data class TokenPush(val fcmToken: String)

private val json = Json { ignoreUnknownKeys = true }

/**
 * `PUT /dispositivos/{id}/push`: la app registra o renueva el token de FCM de su teléfono, al iniciar sesión y
 * cada vez que FCM lo rota. Solo el dueño del dispositivo.
 */
fun Route.rutasDispositivos(dataSource: DataSource) {
    authenticate(AUTH_JWT) {
        put("/dispositivos/{id}/push") {
            val usuario = call.usuarioActual()
            val id = runCatching { UUID.fromString(call.parameters["id"]) }
                .getOrElse { throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "El id del dispositivo no es un UUID") }
            val token = runCatching { json.decodeFromString<TokenPush>(call.receiveText()).fcmToken.trim() }
                .getOrElse { throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "Cuerpo inválido: falta fcmToken") }
            if (token.length !in 20..4096) throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "fcmToken debe tener entre 20 y 4096 caracteres")

            dataSource.transaccion { conn ->
                val dueno = conn.consultarUno("SELECT usuario_id FROM dispositivo WHERE id = ? FOR UPDATE", id) { it.uuid("usuario_id") }
                    ?: throw ApiException(HttpStatusCode.NotFound, "NO_ENCONTRADO", "El dispositivo no existe")
                if (dueno != usuario.usuarioId) throw ApiException(HttpStatusCode.Forbidden, "SIN_PERMISO", "El dispositivo es de otra cuenta")
                // Un token es de una sola instalación: si otro registro lo tenía (por ejemplo, otra cuenta en el mismo
                // teléfono), se le quita para que no reciba avisos ajenos.
                conn.ejecutar("UPDATE dispositivo SET fcm_token = NULL WHERE fcm_token = ? AND id <> ?", token, id)
                conn.ejecutar("UPDATE dispositivo SET fcm_token = ?, ultimo_visto_en = now() WHERE id = ?", token, id)
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
