package com.senderoseguro.server.cuentas

import com.senderoseguro.server.plataforma.AUTH_JWT
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.ipCliente
import com.senderoseguro.server.plataforma.usuarioActual
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * P1 del Módulo 1 (`docs/modulo1_contrato_api.md`):
 * - Sin token: `GET avisos-privacidad/vigente`, `GET instituciones`, `GET instituciones/{id}/escuelas`,
 *   `POST auth/registro` y `POST auth/login`.
 * - Con token: `POST auth/logout`, `GET` y `PATCH usuarios/me`, `POST usuarios/me/baja`.
 */
fun Route.rutasCuentas(cuentas: CuentasServicio) {
    get("/avisos-privacidad/vigente") { call.respond(withContext(Dispatchers.IO) { cuentas.avisoVigente() }) }
    get("/instituciones") { call.respond(withContext(Dispatchers.IO) { cuentas.instituciones() }) }
    get("/instituciones/{id}/escuelas") {
        val id = call.parameters["id"]?.toIntOrNull()
            ?: throw ApiException(HttpStatusCode.NotFound, "NO_ENCONTRADO", "La institución no existe")
        call.respond(withContext(Dispatchers.IO) { cuentas.escuelas(id) })
    }

    post("/auth/registro") {
        val entrada = call.receive<RegistroEntrada>()
        val ip = call.ipCliente()
        call.respond(HttpStatusCode.Created, withContext(Dispatchers.IO) { cuentas.registrar(entrada, ip) })
    }
    post("/auth/login") {
        val entrada = call.receive<LoginEntrada>()
        val ip = call.ipCliente()
        call.respond(withContext(Dispatchers.IO) { cuentas.iniciarSesion(entrada, ip) })
    }

    authenticate(AUTH_JWT) {
        post("/auth/logout") {
            val usuario = call.usuarioActual()
            val ip = call.ipCliente()
            withContext(Dispatchers.IO) { cuentas.cerrarSesion(usuario, ip) }
            call.respond(HttpStatusCode.NoContent)
        }
        get("/usuarios/me") {
            val usuario = call.usuarioActual()
            call.respond(withContext(Dispatchers.IO) { cuentas.perfil(usuario.usuarioId) })
        }
        patch("/usuarios/me") {
            val usuario = call.usuarioActual()
            val cambio = call.receive<PerfilCambio>()
            val ip = call.ipCliente()
            call.respond(withContext(Dispatchers.IO) { cuentas.actualizarPerfil(usuario.usuarioId, cambio, ip) })
        }
        post("/usuarios/me/baja") {
            val usuario = call.usuarioActual()
            val entrada = call.receive<BajaEntrada>()
            val ip = call.ipCliente()
            withContext(Dispatchers.IO) { cuentas.solicitarBaja(usuario.usuarioId, entrada.contrasena, ip) }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
