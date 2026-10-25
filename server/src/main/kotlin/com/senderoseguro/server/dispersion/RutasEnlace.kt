package com.senderoseguro.server.dispersion

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** `GET /enlace/{token}`: página pública de ubicación para la red de apoyo. No pide sesión. */
fun Route.rutasEnlace(enlaces: EnlacesServicio) {
    get("/enlace/{token}") {
        call.respond(enlaces.consultar(call.parameters["token"].orEmpty()))
    }
}
