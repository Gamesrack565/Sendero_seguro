package com.senderoseguro.server.catalogo

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
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Catálogo (P3):
 * - Sin token: `GET catalogo` (vigente, para la app). Con `?version=N` igual a la vigente responde 304.
 * - Administrador: `GET|POST catalogo/versiones`, `GET|DELETE catalogo/versiones/{numero}`,
 *   `PUT catalogo/versiones/{numero}/tipos/{codigo}`, `PUT catalogo/versiones/{numero}/reglas/{codigo}`,
 *   `POST catalogo/versiones/{numero}/validar`, `POST catalogo/versiones/{numero}/publicar`.
 */
fun Route.rutasCatalogo(catalogo: CatalogoServicio) {
    get("/catalogo") {
        val tiene = call.request.queryParameters["version"]?.toIntOrNull()
        if (tiene != null && tiene == io { catalogo.numeroVigente() }) {
            call.respond(HttpStatusCode.NotModified)
            return@get
        }
        call.respond(io { catalogo.vigente(call.request.queryParameters["aplicaA"]) })
    }
    authenticate(AUTH_JWT) {
        get("/catalogo/versiones") {
            call.exigirRol(Rol.ADMINISTRADOR)
            call.respond(io { catalogo.versiones() })
        }
        post("/catalogo/versiones") {
            val u = call.exigirRol(Rol.ADMINISTRADOR)
            val ip = call.ipCliente()
            call.respond(HttpStatusCode.Created, io { catalogo.crearBorrador(u, ip) })
        }
        get("/catalogo/versiones/{numero}") {
            call.exigirRol(Rol.ADMINISTRADOR)
            call.respond(io { catalogo.detalle(call.numero()) })
        }
        delete("/catalogo/versiones/{numero}") {
            val u = call.exigirRol(Rol.ADMINISTRADOR)
            val numero = call.numero()
            val ip = call.ipCliente()
            io { catalogo.descartar(u, numero, ip) }
            call.respond(HttpStatusCode.NoContent)
        }
        put("/catalogo/versiones/{numero}/tipos/{codigo}") {
            val u = call.exigirRol(Rol.ADMINISTRADOR)
            val numero = call.numero()
            val entrada = call.cuerpo<TipoEntrada>()
            val ip = call.ipCliente()
            call.respond(io { catalogo.guardarTipo(u, numero, call.parameters["codigo"].orEmpty(), entrada, ip) })
        }
        put("/catalogo/versiones/{numero}/reglas/{codigo}") {
            val u = call.exigirRol(Rol.ADMINISTRADOR)
            val numero = call.numero()
            val entrada = call.cuerpo<ReglaEntrada>()
            val ip = call.ipCliente()
            call.respond(io { catalogo.guardarRegla(u, numero, call.parameters["codigo"].orEmpty(), entrada, ip) })
        }
        post("/catalogo/versiones/{numero}/validar") {
            val u = call.exigirRol(Rol.ADMINISTRADOR)
            val numero = call.numero()
            val ip = call.ipCliente()
            call.respond(io { catalogo.validar(u, numero, ip) })
        }
        post("/catalogo/versiones/{numero}/publicar") {
            val u = call.exigirRol(Rol.ADMINISTRADOR)
            val numero = call.numero()
            val ip = call.ipCliente()
            call.respond(io { catalogo.publicar(u, numero, ip) })
        }
    }
}

private fun ApplicationCall.numero(): Int =
    parameters["numero"]?.removePrefix("v")?.toIntOrNull() ?: throw errorValidacion(mapOf("numero" to "No es un número de versión"))

private suspend inline fun <reified T : Any> ApplicationCall.cuerpo(): T = runCatching { receive<T>() }
    .getOrElse { throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "Cuerpo inválido: revisa los campos obligatorios") }

private suspend fun <T> io(bloque: () -> T): T = withContext(Dispatchers.IO) { bloque() }
