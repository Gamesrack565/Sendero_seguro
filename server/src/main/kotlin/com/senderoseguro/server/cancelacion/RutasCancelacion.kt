package com.senderoseguro.server.cancelacion

import com.senderoseguro.server.plataforma.AUTH_JWT
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

/** P8 del Módulo 1: `POST alertas/{id}/cancelaciones` con token de la persona que envió la alerta. Responde 204. */
fun Route.rutasCancelacion(cancelacion: CancelacionServicio) {
    authenticate(AUTH_JWT) {
        post("/alertas/{id}/cancelaciones") {
            val u = call.usuarioActual()
            val entrada = call.receive<CancelacionEntrada>()
            val ip = call.ipCliente()
            val alerta = call.parameters["id"].orEmpty()
            withContext(Dispatchers.IO) { cancelacion.registrar(u.usuarioId, alerta, entrada, ip) }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
