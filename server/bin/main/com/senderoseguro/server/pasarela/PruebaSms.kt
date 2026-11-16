package com.senderoseguro.server.pasarela

import com.senderoseguro.server.avisos.Mensaje
import com.senderoseguro.server.avisos.Proveedor
import com.senderoseguro.server.avisos.ResultadoEnvio
import com.senderoseguro.server.plataforma.ApiException
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.MessageDigest

@Serializable
data class SmsPrueba(val telefono: String)

@Serializable
data class SmsPruebaRespuesta(
    val proveedor: String,
    val para: String,
    val aceptado: Boolean,
    val proveedorMensajeId: String? = null,
    val error: String? = null,
)

private val json = Json { ignoreUnknownKeys = true }
private val log = LoggerFactory.getLogger("com.senderoseguro.server.pasarela.PruebaSms")

/** Manda un SMS de prueba con el proveedor configurado, sin crear alertas. [cuerpo] es `{"telefono":"..."}`. */
fun enviarSmsPrueba(sms: Proveedor, cuerpo: String): SmsPruebaRespuesta {
    val telefono = runCatching { json.decodeFromString<SmsPrueba>(cuerpo).telefono.trim() }.getOrNull()
        ?.takeIf { t -> t.count { it.isDigit() } in 10..15 }
        ?: throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "telefono debe tener de 10 a 15 dígitos")
    val para = Telefonos.e164(telefono)
    return when (val r = sms.enviar(Mensaje(0, "SMS", telefono, "Sendero Seguro", "Sendero Seguro: SMS de prueba de la pasarela."))) {
        is ResultadoEnvio.Aceptado -> SmsPruebaRespuesta(sms.nombre, para, aceptado = true, proveedorMensajeId = r.proveedorMensajeId)
        is ResultadoEnvio.Fallo -> SmsPruebaRespuesta(sms.nombre, para, aceptado = false, error = r.error)
    }
}

/**
 * `POST /pasarela/prueba-sms`: prueba en la nube que el servidor manda SMS con las credenciales que tiene configuradas.
 * Solo existe mientras PRUEBA_SMS_LLAVE tenga valor, y pide esa llave en `X-Llave-Prueba`. Se borra al terminar.
 */
fun Route.rutasPruebaSms(sms: Proveedor, llave: String) {
    post("/pasarela/prueba-sms") {
        val recibida = call.request.headers["X-Llave-Prueba"].orEmpty()
        if (!MessageDigest.isEqual(recibida.toByteArray(), llave.toByteArray())) {
            throw ApiException(HttpStatusCode.Unauthorized, "LLAVE_INVALIDA", "Falta la llave de prueba o no coincide")
        }
        val r = enviarSmsPrueba(sms, call.receiveText())
        log.info("SMS de prueba por {}: aceptado={} {}", r.proveedor, r.aceptado, r.error.orEmpty())
        call.respond(r)
    }
}
