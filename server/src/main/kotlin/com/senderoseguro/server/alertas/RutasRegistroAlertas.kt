package com.senderoseguro.server.alertas

import com.senderoseguro.server.plataforma.AUTH_JWT
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.ipCliente
import com.senderoseguro.server.plataforma.usuarioActual
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * P4: `POST /alertas` con el token de quien la envía (integrante o coordinador). Responde 201 la primera vez y
 * 200 en un reenvío, con el folio. La dispersión corre después sin que la app espere.
 */
fun Route.rutasRegistroAlertas(registro: RegistroAlertas) {
    authenticate(AUTH_JWT) {
        post("/alertas") {
            val u = call.usuarioActual()
            if (u.rol != Rol.INTEGRANTE && u.rol != Rol.COORDINADOR) {
                throw ApiException(HttpStatusCode.Forbidden, "SIN_PERMISO", "Solo la comunidad envía alertas")
            }
            val entrada = call.receive<AlertaNueva>()
            val ip = call.ipCliente()
            val r = withContext(Dispatchers.IO) { registro.registrar(u.usuarioId, u.dispositivoId, entrada, ip) }
            call.respond(if (r.nueva) HttpStatusCode.Created else HttpStatusCode.OK, r.respuesta())
        }
    }
}
