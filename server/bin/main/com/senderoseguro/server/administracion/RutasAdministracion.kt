package com.senderoseguro.server.administracion

import com.senderoseguro.server.cuentas.errorValidacion
import com.senderoseguro.server.plataforma.AUTH_JWT
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.exigirRol
import com.senderoseguro.server.plataforma.ipCliente
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Administración (consola web W-08 y W-09, CU-07 y CU-10) y turno del operador:
 * - Administrador: `GET usuarios`, `PATCH usuarios/{id}/perfil`, `PATCH usuarios/{id}/estado`,
 *   `GET|POST corporaciones`, `PATCH corporaciones/{id}`, `GET|POST unidades`, `GET|PATCH unidades/{id}`,
 *   `PUT unidades/{id}/operadores`.
 * - Operador: `GET unidades/mia` y `PATCH unidades/mia` (abrir o cerrar turno y mandar la ubicación de la unidad).
 */
fun Route.rutasAdministracion(admin: AdministracionServicio) {
    authenticate(AUTH_JWT) {
        get("/usuarios") {
            call.exigirRol(Rol.ADMINISTRADOR)
            val buscar = call.request.queryParameters["buscar"]
            val rol = call.request.queryParameters["rol"]
            val limite = call.request.queryParameters["limite"]?.toIntOrNull() ?: 50
            call.respond(io { admin.buscarUsuarios(buscar, rol, limite) })
        }
        patch("/usuarios/{id}/perfil") {
            val u = call.exigirRol(Rol.ADMINISTRADOR)
            val id = uuidDe(call.parameters["id"], "id")
            val cambio = call.cuerpo<CambioPerfil>()
            val ip = call.ipCliente()
            call.respond(io { admin.cambiarPerfil(u, id, cambio, ip) })
        }
        patch("/usuarios/{id}/estado") {
            val u = call.exigirRol(Rol.ADMINISTRADOR)
            val id = uuidDe(call.parameters["id"], "id")
            val cambio = call.cuerpo<CambioEstadoCuenta>()
            val ip = call.ipCliente()
            call.respond(io { admin.cambiarEstado(u, id, cambio, ip) })
        }

        get("/corporaciones") {
            call.exigirRol(Rol.ADMINISTRADOR, Rol.OPERADOR)
            call.respond(io { admin.corporaciones() })
        }
        post("/corporaciones") {
            val u = call.exigirRol(Rol.ADMINISTRADOR)
            val entrada = call.cuerpo<CorporacionEntrada>()
            val ip = call.ipCliente()
            call.respond(HttpStatusCode.Created, io { admin.crearCorporacion(u, entrada, ip) })
        }
        patch("/corporaciones/{id}") {
            val u = call.exigirRol(Rol.ADMINISTRADOR)
            val id = call.parameters["id"]?.toIntOrNull() ?: throw errorValidacion(mapOf("id" to "No es un id válido"))
            val cambio = call.cuerpo<CorporacionCambio>()
            val ip = call.ipCliente()
            call.respond(io { admin.editarCorporacion(u, id, cambio, ip) })
        }

        // "mia" va antes que "{id}" para que no se lea como un id.
        get("/unidades/mia") {
            val u = call.exigirRol(Rol.OPERADOR)
            call.respond(io { admin.miUnidad(u) })
        }
        patch("/unidades/mia") {
            val u = call.exigirRol(Rol.OPERADOR)
            val cambio = call.cuerpo<TurnoCambio>()
            val ip = call.ipCliente()
            call.respond(io { admin.cambiarTurno(u, cambio, ip) })
        }
        get("/unidades") {
            call.exigirRol(Rol.ADMINISTRADOR)
            val corporacion = call.request.queryParameters["corporacionId"]?.let {
                it.toIntOrNull() ?: throw errorValidacion(mapOf("corporacionId" to "No es un id válido"))
            }
            call.respond(io { admin.unidades(corporacion) })
        }
        post("/unidades") {
            val u = call.exigirRol(Rol.ADMINISTRADOR)
            val entrada = call.cuerpo<UnidadEntrada>()
            val ip = call.ipCliente()
            call.respond(HttpStatusCode.Created, io { admin.crearUnidad(u, entrada, ip) })
        }
        get("/unidades/{id}") {
            call.exigirRol(Rol.ADMINISTRADOR)
            val id = uuidDe(call.parameters["id"], "id")
            call.respond(io { admin.unidad(id) })
        }
        patch("/unidades/{id}") {
            val u = call.exigirRol(Rol.ADMINISTRADOR)
            val id = uuidDe(call.parameters["id"], "id")
            val cambio = call.cuerpo<UnidadCambio>()
            val ip = call.ipCliente()
            call.respond(io { admin.editarUnidad(u, id, cambio, ip) })
        }
        put("/unidades/{id}/operadores") {
            val u = call.exigirRol(Rol.ADMINISTRADOR)
            val id = uuidDe(call.parameters["id"], "id")
            val entrada = call.cuerpo<OperadoresEntrada>()
            val ip = call.ipCliente()
            call.respond(io { admin.vincularOperadores(u, id, entrada, ip) })
        }
    }
}

/** El cuerpo JSON; si falta un campo obligatorio o no es JSON responde 400 VALIDACION. */
internal suspend inline fun <reified T : Any> ApplicationCall.cuerpo(): T = runCatching { receive<T>() }
    .getOrElse { throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "Cuerpo inválido: revisa los campos obligatorios") }

internal suspend fun <T> io(bloque: () -> T): T = withContext(Dispatchers.IO) { bloque() }
