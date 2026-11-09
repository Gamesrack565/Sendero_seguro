package com.senderoseguro.server.tiemporeal

import com.auth0.jwt.JWT
import com.senderoseguro.server.dispersion.EnlacesServicio
import com.senderoseguro.server.plataforma.EmisorTokens
import com.senderoseguro.server.plataforma.Rol
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun Application.configurarWebSockets() {
    install(WebSockets) {
        maxFrameSize = 64 * 1024L   // el cliente solo manda `pong`
    }
}

/**
 * `WS /ws/consola?token=<JWT>` (OPERADOR y ADMINISTRADOR) y `WS /ws/enlace/{token}` (público).
 * El navegador no puede mandar encabezados en un WebSocket, por eso el JWT va en la URL.
 * Si el token no sirve, la conexión se cierra con el código 1008 y el motivo en el texto.
 */
fun Route.rutasTiempoReal(centro: CentroTiempoReal, tokens: EmisorTokens, ping: Duration = 25.seconds) {
    webSocket("/ws/consola") {
        val jwt = call.request.queryParameters["token"].orEmpty()
        val usuario = tokens.verificar(jwt)
        if (usuario == null) return@webSocket rechazar("NO_AUTENTICADO")
        if (usuario.rol != Rol.OPERADOR && usuario.rol != Rol.ADMINISTRADOR) return@webSocket rechazar("SIN_PERMISO")
        val esAdministrador = usuario.rol == Rol.ADMINISTRADOR
        val unidad = if (esAdministrador) null else centro.unidadDeOperador(usuario.usuarioId)
        val venceEn = runCatching { JWT.decode(jwt).expiresAtAsInstant }.getOrNull()
        atender(centro, CentroTiempoReal.Consola(usuario.usuarioId, esAdministrador, unidad), ping, venceEn)
    }

    webSocket("/ws/enlace/{token}") {
        val token = call.parameters["token"].orEmpty()
        val (enlaceId, alertaId) = centro.vigente(token) ?: return@webSocket rechazar("ENLACE_NO_DISPONIBLE")
        val seguidor = CentroTiempoReal.Seguidor(enlaceId, alertaId)
        centro.saludar(seguidor, token)
        atender(centro, seguidor, ping, venceEn = null)
    }
}

private suspend fun DefaultWebSocketServerSession.rechazar(motivo: String) =
    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, motivo))

/**
 * Mantiene viva la conexión: manda lo que llegue a la salida del suscriptor, un `ping` cada [ping] y la cierra si
 * el cliente deja de contestar (dos pings sin nada), si vence su JWT o cuando el centro cierra la salida (`fin`).
 */
private suspend fun DefaultWebSocketServerSession.atender(
    centro: CentroTiempoReal,
    s: CentroTiempoReal.Suscriptor,
    ping: Duration,
    venceEn: Instant?,
) = coroutineScope {
    centro.conectar(s)
    var ultimoMensaje = System.nanoTime()
    val envio = launch {
        for (m in s.salida) send(Frame.Text(m))
        close(CloseReason(CloseReason.Codes.NORMAL, "FIN"))
    }
    val latido = launch {
        while (true) {
            delay(ping)
            if (venceEn != null && Instant.now() >= venceEn) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "TOKEN_VENCIDO"))
                break
            }
            if (System.nanoTime() - ultimoMensaje > (ping * 2).inWholeNanoseconds) {
                close(CloseReason(CloseReason.Codes.GOING_AWAY, "SIN_RESPUESTA"))
                break
            }
            s.mandar(CentroTiempoReal.mensaje("ping", buildJsonObject { }))
        }
    }
    try {
        // Lo único que manda el cliente es `{"tipo":"pong"}`; cualquier mensaje cuenta como señal de vida.
        for (frame in incoming) ultimoMensaje = System.nanoTime()
    } finally {
        centro.desconectar(s)
        envio.cancel()
        latido.cancel()
    }
}
