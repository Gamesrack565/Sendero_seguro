package com.senderoseguro.server.plataforma

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** Roles de `usuario_rol` en la base de datos. */
enum class Rol { INTEGRANTE, COORDINADOR, OPERADOR, ADMINISTRADOR }

/** Lo que trae el JWT: `sub`, `rol`, `disp` y `sid`. */
data class UsuarioToken(
    val usuarioId: UUID,
    val rol: Rol,
    val dispositivoId: UUID?,
    val sesionId: UUID?,
)

const val AUTH_JWT = "jwt"

/**
 * Firma los JWT con la misma llave, emisor y audiencia con que el servidor los valida (lo usa el login),
 * y valida tokens que no llegan en el encabezado, como el `?token=` del WebSocket.
 */
class EmisorTokens(private val secreto: String, private val emisor: String, private val audiencia: String) {

    private val verificador = JWT.require(Algorithm.HMAC256(secreto)).withIssuer(emisor).withAudience(audiencia).build()

    /** El usuario del token, o null si la firma no es válida, ya venció o el rol no existe. */
    fun verificar(token: String): UsuarioToken? = runCatching {
        val p = verificador.verify(token)
        UsuarioToken(
            usuarioId = UUID.fromString(p.subject),
            rol = Rol.valueOf(p.getClaim("rol").asString()),
            dispositivoId = p.getClaim("disp").asString()?.let(UUID::fromString),
            sesionId = p.getClaim("sid").asString()?.let(UUID::fromString),
        )
    }.getOrNull()

    fun emitir(usuario: UsuarioToken, duracion: Duration): Pair<String, Instant> {
        val expira = Instant.now().plus(duracion.toJavaDuration())
        val token = JWT.create()
            .withIssuer(emisor)
            .withAudience(audiencia)
            .withSubject(usuario.usuarioId.toString())
            .withClaim("rol", usuario.rol.name)
            .apply {
                usuario.dispositivoId?.let { withClaim("disp", it.toString()) }
                usuario.sesionId?.let { withClaim("sid", it.toString()) }
            }
            .withExpiresAt(Date.from(expira))
            .sign(Algorithm.HMAC256(secreto))
        return token to expira
    }

    companion object {
        fun desde(app: Application): EmisorTokens {
            val config = app.environment.config
            return EmisorTokens(
                config.property("jwt.secreto").getString(),
                config.property("jwt.emisor").getString(),
                config.property("jwt.audiencia").getString(),
            )
        }
    }
}

fun Application.configurarSeguridad() {
    val config = environment.config
    val secreto = config.property("jwt.secreto").getString()
    val emisor = config.property("jwt.emisor").getString()
    val audiencia = config.property("jwt.audiencia").getString()

    install(Authentication) {
        jwt(AUTH_JWT) {
            realm = "sendero-seguro"
            verifier(
                JWT.require(Algorithm.HMAC256(secreto))
                    .withIssuer(emisor)
                    .withAudience(audiencia)
                    .build()
            )
            validate { credencial ->
                val rolValido = credencial.payload.getClaim("rol").asString() in Rol.entries.map { it.name }
                if (rolValido && credencial.payload.subject != null) JWTPrincipal(credencial.payload) else null
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorApi("NO_AUTENTICADO", "Falta el token o venció"))
            }
        }
    }
}

/** Usuario del token. Usar dentro de `authenticate(AUTH_JWT) { ... }`. */
fun ApplicationCall.usuarioActual(): UsuarioToken {
    val payload = principal<JWTPrincipal>()?.payload
        ?: throw ApiException(HttpStatusCode.Unauthorized, "NO_AUTENTICADO", "Falta el token o venció")
    return UsuarioToken(
        usuarioId = UUID.fromString(payload.subject),
        rol = Rol.valueOf(payload.getClaim("rol").asString()),
        dispositivoId = payload.getClaim("disp").asString()?.let(UUID::fromString),
        sesionId = payload.getClaim("sid").asString()?.let(UUID::fromString),
    )
}

/** Corta la petición con 403 si el rol del token no está en la lista. */
fun ApplicationCall.exigirRol(vararg roles: Rol): UsuarioToken {
    val usuario = usuarioActual()
    if (usuario.rol !in roles) {
        throw ApiException(HttpStatusCode.Forbidden, "SIN_PERMISO", "Tu rol no puede hacer esto")
    }
    return usuario
}
