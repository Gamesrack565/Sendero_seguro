package com.senderoseguro.server.plataforma

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

const val VERSION_API = "0.1.0"

@Serializable
data class Salud(
    val estado: String,
    val version: String,
    val componentes: Map<String, String>,
)

/** `GET /salud`: responde 200 si la base de datos contesta y 503 si no. */
fun Route.rutasSalud(revisarBaseDatos: () -> Boolean) {
    get("/salud") {
        val baseDatosOk = revisarBaseDatos()
        val estado = if (baseDatosOk) "OK" else "CAIDO"
        call.respond(
            if (baseDatosOk) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            Salud(estado, VERSION_API, mapOf("baseDatos" to estado)),
        )
    }
}
