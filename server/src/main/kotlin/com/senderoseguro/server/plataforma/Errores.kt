package com.senderoseguro.server.plataforma

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Formato único de error del contrato (esquema `Error` de openapi.yaml). */
@Serializable
data class ErrorApi(
    val codigo: String,
    val mensaje: String,
    val detalle: JsonObject? = null,
)

/** Lánzala desde cualquier ruta para responder con el formato del contrato. */
class ApiException(
    val status: HttpStatusCode,
    val codigo: String,
    mensaje: String,
    val detalle: JsonObject? = null,
) : RuntimeException(mensaje)

fun Application.configurarErrores() {
    install(StatusPages) {
        exception<ApiException> { call, e ->
            call.respond(e.status, ErrorApi(e.codigo, e.message ?: e.codigo, e.detalle))
        }
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorApi("VALIDACION", e.message ?: "Solicitud inválida"))
        }
        exception<Throwable> { call, e ->
            call.application.log.error("Error no controlado", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorApi("ERROR_INTERNO", "Ocurrió un error en el servidor"))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorApi("NO_ENCONTRADO", "La ruta no existe"))
        }
    }
}
