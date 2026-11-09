package com.senderoseguro.server.dispersion

import com.senderoseguro.server.plataforma.AUTH_JWT
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.exigirRol
import com.senderoseguro.server.plataforma.usuarioActual
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

private val ESTADOS_ALERTA = setOf(
    "CREADA", "EN_CUENTA_REGRESIVA", "PENDIENTE_ENVIO", "EXPIRADA", "ENVIADA", "ASIGNADA", "ESCALADA",
    "ACUSADA", "EN_ATENCION", "ATENDIDA", "CERRADA", "CANCELADA",
)
private val ACTIVAS = listOf("ENVIADA", "ASIGNADA", "ESCALADA", "ACUSADA", "EN_ATENCION")
private val json = Json { ignoreUnknownKeys = true }

/**
 * Rutas de la consola para las alertas (P5). `POST /alertas` (registro) es del Módulo 2 y no está aquí.
 * Contrato: GET /alertas, GET /alertas/{id}, POST /alertas/{id}/acuse, PATCH /alertas/{id}/estado,
 * POST /alertas/{id}/escalar.
 */
fun Route.rutasAlertas(consulta: ConsultaAlertas, atencion: AtencionServicio) {
    authenticate(AUTH_JWT) {
        route("/alertas") {
            get {
                val usuario = call.exigirRol(Rol.OPERADOR, Rol.ADMINISTRADOR)
                call.respond(consulta.listar(usuario, call.filtros()))
            }
            get("/{id}") {
                call.respond(consulta.detalle(call.usuarioActual(), call.idAlerta()))
            }
            post("/{id}/acuse") {
                call.respond(atencion.acusar(call.usuarioActual(), call.idAlerta()))
            }
            patch("/{id}/estado") {
                val cambio = runCatching { call.receive<CambioEstado>() }
                    .getOrElse { throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "Cuerpo inválido: falta estado") }
                call.respond(atencion.cambiarEstado(call.usuarioActual(), call.idAlerta(), cambio))
            }
            post("/{id}/escalar") {
                val usuario = call.exigirRol(Rol.OPERADOR, Rol.ADMINISTRADOR)
                val id = call.idAlerta()
                val texto = call.receiveText()
                val peticion = if (texto.isBlank()) EscalarPeticion() else runCatching { json.decodeFromString<EscalarPeticion>(texto) }
                    .getOrElse { throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "Cuerpo inválido") }
                call.respond(atencion.escalar(usuario, id, peticion.comentario))
            }
        }
    }
}

private fun ApplicationCall.idAlerta(): UUID = runCatching { UUID.fromString(parameters["id"]) }
    .getOrElse { throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "El id de la alerta no es un UUID") }

private fun ApplicationCall.filtros(): ConsultaAlertas.Filtros {
    val q = request.queryParameters
    val estados = q["estado"]?.split(',')?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }
        ?.flatMap { if (it == "ACTIVAS") ACTIVAS else listOf(it) }
        ?.onEach { if (it !in ESTADOS_ALERTA) throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "Estado desconocido: $it") }
        ?.distinct()
    fun fecha(nombre: String) = q[nombre]?.let {
        runCatching { Instant.parse(it) }
            .getOrElse { throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "$nombre debe ser una fecha ISO 8601, por ejemplo 2026-10-25T00:00:00Z") }
    }
    val limite = q["limite"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..200 } }
        ?: if (q["limite"] == null) 50 else throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "limite debe estar entre 1 y 200")
    return ConsultaAlertas.Filtros(estados, fecha("desde"), fecha("hasta"), limite, q["cursor"])
}
