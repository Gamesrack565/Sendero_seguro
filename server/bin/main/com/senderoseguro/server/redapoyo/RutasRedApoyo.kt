package com.senderoseguro.server.redapoyo

import com.senderoseguro.server.plataforma.AUTH_JWT
import com.senderoseguro.server.plataforma.ipCliente
import com.senderoseguro.server.plataforma.usuarioActual
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * P2 del Módulo 1 (`docs/modulo1/modulo1_contrato_api.md`), todo con token:
 * - `GET contactos`, `PUT contactos/{id}`, `DELETE contactos/{id}`, `PUT contactos/orden`.
 * - `GET circulos/mio`, `POST circulos`, `POST circulos/unirse`, `POST circulos/{id}/codigo`,
 *   `GET circulos/{id}/miembros`, `DELETE circulos/{id}/miembros/{usuarioId}`, `POST circulos/{id}/retirarme`.
 * - `POST red-apoyo/avisos`.
 */
fun Route.rutasRedApoyo(red: RedApoyoServicio) {
    authenticate(AUTH_JWT) {
        get("/contactos") {
            val u = call.usuarioActual()
            call.respond(io { red.contactos(u.usuarioId) })
        }
        // "orden" va antes que "{id}" para que no se lea como un id.
        put("/contactos/orden") {
            val u = call.usuarioActual()
            val orden = call.receive<OrdenContactos>()
            val ip = call.ipCliente()
            call.respond(io { red.reordenar(u.usuarioId, orden, ip) })
        }
        put("/contactos/{id}") {
            val u = call.usuarioActual()
            val contacto = call.receive<ContactoEntrada>()
            val ip = call.ipCliente()
            call.respond(io { red.guardarContacto(u.usuarioId, call.id(), contacto, ip) })
        }
        delete("/contactos/{id}") {
            val u = call.usuarioActual()
            val ip = call.ipCliente()
            io { red.eliminarContacto(u.usuarioId, call.id(), ip) }
            call.respond(HttpStatusCode.NoContent)
        }

        get("/circulos/mio") {
            val u = call.usuarioActual()
            call.respond(io { red.miCirculo(u.usuarioId) })
        }
        post("/circulos") {
            val u = call.usuarioActual()
            val entrada = call.receive<CrearCirculo>()
            val ip = call.ipCliente()
            call.respond(HttpStatusCode.Created, io { red.crearCirculo(u.usuarioId, entrada, ip) })
        }
        post("/circulos/unirse") {
            val u = call.usuarioActual()
            val entrada = call.receive<Unirse>()
            val ip = call.ipCliente()
            call.respond(io { red.unirse(u.usuarioId, entrada, ip) })
        }
        post("/circulos/{id}/codigo") {
            val u = call.usuarioActual()
            val ip = call.ipCliente()
            call.respond(io { red.regenerarCodigo(u.usuarioId, call.id(), ip) })
        }
        get("/circulos/{id}/miembros") {
            val u = call.usuarioActual()
            call.respond(io { red.miembros(u.usuarioId, call.id()) })
        }
        delete("/circulos/{id}/miembros/{usuarioId}") {
            val u = call.usuarioActual()
            val ip = call.ipCliente()
            io { red.darDeBaja(u.usuarioId, call.id(), call.parameters["usuarioId"].orEmpty(), ip) }
            call.respond(HttpStatusCode.NoContent)
        }
        post("/circulos/{id}/retirarme") {
            val u = call.usuarioActual()
            val ip = call.ipCliente()
            io { red.retirarme(u.usuarioId, call.id(), ip) }
            call.respond(HttpStatusCode.NoContent)
        }

        post("/red-apoyo/avisos") {
            val u = call.usuarioActual()
            val aviso = call.receive<AvisoVinculacion>()
            io { red.avisar(u.usuarioId, aviso) }
            call.respond(HttpStatusCode.Accepted)
        }
    }
}

private fun RoutingCall.id() = parameters["id"].orEmpty()

private suspend fun <T> io(bloque: () -> T): T = withContext(Dispatchers.IO) { bloque() }
