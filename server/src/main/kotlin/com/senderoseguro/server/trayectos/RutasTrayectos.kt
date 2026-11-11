package com.senderoseguro.server.trayectos

import com.senderoseguro.server.plataforma.AUTH_JWT
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.ipCliente
import com.senderoseguro.server.plataforma.usuarioActual
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Caminata segura (P7): `POST trayectos`, `GET|PATCH trayectos/{id}`, `POST trayectos/{id}/checkins`. */
fun Route.rutasTrayectos(trayectos: TrayectosServicio) {
    authenticate(AUTH_JWT) {
        post("/trayectos") {
            val u = call.usuarioActual()
            val e = call.cuerpo<TrayectoEntrada>()
            val ip = call.ipCliente()
            val (t, nuevo) = io { trayectos.iniciar(u.usuarioId, e, ip) }
            call.respond(if (nuevo) HttpStatusCode.Created else HttpStatusCode.OK, t)
        }
        get("/trayectos/{id}") {
            val u = call.usuarioActual()
            call.respond(io { trayectos.consultar(u.usuarioId, call.parameters["id"].orEmpty()) })
        }
        patch("/trayectos/{id}") {
            val u = call.usuarioActual()
            val c = call.cuerpo<TrayectoCambio>()
            call.respond(io { trayectos.cambiar(u.usuarioId, call.parameters["id"].orEmpty(), c) })
        }
        post("/trayectos/{id}/checkins") {
            val u = call.usuarioActual()
            val c = call.cuerpo<CheckinEntrada>()
            call.respond(io { trayectos.checkin(u.usuarioId, call.parameters["id"].orEmpty(), c) })
        }
    }
}

private suspend inline fun <reified T : Any> ApplicationCall.cuerpo(): T = runCatching { receive<T>() }
    .getOrElse { throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "Cuerpo inválido: revisa los campos obligatorios") }

private suspend fun <T> io(bloque: () -> T): T = withContext(Dispatchers.IO) { bloque() }
