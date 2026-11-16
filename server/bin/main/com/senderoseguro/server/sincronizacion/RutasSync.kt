package com.senderoseguro.server.sincronizacion

import com.senderoseguro.server.plataforma.AUTH_JWT
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.ErrorApi
import com.senderoseguro.server.plataforma.usuarioActual
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

@Serializable
data class ResultadoLoteSync(val resultados: List<ResultadoSync>)

/**
 * `POST /sync/lote`: la cola offline del teléfono. Un lote mal formado o de otra persona se rechaza completo; un
 * elemento con forma incorrecta sale RECHAZADO y los demás siguen.
 */
fun Route.rutasSync(sincronizacion: Sincronizacion) {
    authenticate(AUTH_JWT) {
        post("/sync/lote") {
            val usuario = call.usuarioActual()
            if ((call.request.contentLength() ?: 0) > Sincronizacion.MAX_BYTES) throw demasiadoGrande()
            val bytes = call.receiveChannel().readBuffer(Sincronizacion.MAX_BYTES + 1L).readByteArray()
            if (bytes.size > Sincronizacion.MAX_BYTES) throw demasiadoGrande()

            val lote = runCatching { Json.parseToJsonElement(bytes.decodeToString()) as JsonObject }.getOrNull()
                ?: throw invalido("El cuerpo debe ser un objeto JSON con dispositivoId y elementos")
            val dispositivoId = runCatching { UUID.fromString((lote["dispositivoId"] as JsonPrimitive).content) }.getOrNull()
                ?: throw invalido("dispositivoId debe ser un UUID")
            val crudos = (lote["elementos"] as? JsonArray)?.takeIf { it.isNotEmpty() }
                ?: throw invalido("elementos debe traer al menos un elemento")
            if (crudos.size > Sincronizacion.MAX_ELEMENTOS) throw demasiadoGrande()
            if (usuario.dispositivoId != null && usuario.dispositivoId != dispositivoId) {
                throw ApiException(HttpStatusCode.Forbidden, "SIN_PERMISO", "dispositivoId no es el de tu sesión")
            }
            sincronizacion.revisarDispositivo(usuario.usuarioId, dispositivoId)

            // Cada elemento: el leído, o el rechazo por su forma. Sin un id legible no hay a quién contestarle.
            val leidos = crudos.mapIndexed { i, crudo ->
                val o = crudo as? JsonObject ?: throw invalido("El elemento $i no es un objeto")
                val id = (o["id"] as? JsonPrimitive)?.content?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: throw invalido("El elemento $i no tiene un id UUID")
                Sincronizacion.leerElemento(o) ?: ResultadoSync(
                    id.toString(), "RECHAZADO",
                    ErrorApi(
                        "VALIDACION", "El elemento necesita tipo (${TipoSync.entries.joinToString()}), creadoEn ISO 8601 y datos",
                        buildJsonObject { put("temporal", false) },
                    ),
                )
            }
            val procesados = sincronizacion.procesar(
                ContextoSync(usuario.usuarioId, dispositivoId), leidos.filterIsInstance<ElementoSync>(),
            ).iterator()
            call.respond(ResultadoLoteSync(leidos.map { it as? ResultadoSync ?: procesados.next() }))
        }
    }
}

private fun invalido(mensaje: String) = ApiException(HttpStatusCode.BadRequest, "VALIDACION", mensaje)

private fun demasiadoGrande() = ApiException(
    HttpStatusCode.PayloadTooLarge, "VALIDACION",
    "El lote pasa de ${Sincronizacion.MAX_ELEMENTOS} elementos o de 1 MB; mándalo en partes",
)
