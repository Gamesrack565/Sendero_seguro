package com.senderoseguro.server.avisos

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date

/**
 * La cuenta de servicio de Firebase (el `.json` que se descarga en Configuración del proyecto → Cuentas de servicio).
 * Es un secreto: vive fuera del repo y se indica con la variable FCM_CREDENCIALES.
 */
class CredencialesFcm(
    val proyecto: String,
    val correo: String,
    val llave: RSAPrivateKey,
    val urlToken: String = "https://oauth2.googleapis.com/token",
) {
    companion object {
        fun desdeArchivo(ruta: String): CredencialesFcm {
            val archivo = Path.of(ruta)
            require(Files.exists(archivo)) { "No existe el archivo de FCM_CREDENCIALES: $ruta" }
            val j = Json.parseToJsonElement(Files.readString(archivo)).jsonObject
            fun campo(nombre: String) = j[nombre]?.jsonPrimitive?.contentOrNull
                ?: error("El archivo de FCM_CREDENCIALES no trae '$nombre'; ¿es la clave de la cuenta de servicio?")
            return CredencialesFcm(
                proyecto = campo("project_id"),
                correo = campo("client_email"),
                llave = leerLlave(campo("private_key")),
                urlToken = j["token_uri"]?.jsonPrimitive?.contentOrNull ?: "https://oauth2.googleapis.com/token",
            )
        }

        /** Llave privada en PEM (PKCS#8), como viene en el `.json` de Google. */
        fun leerLlave(pem: String): RSAPrivateKey {
            val base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replace(Regex("\\s"), "")
            val spec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64))
            return KeyFactory.getInstance("RSA").generatePrivate(spec) as RSAPrivateKey
        }
    }
}

/**
 * Push por Firebase Cloud Messaging (API HTTP v1). Se autentica con la cuenta de servicio: firma un JWT,
 * lo cambia por un token de acceso de Google (dura 1 hora y se reutiliza) y con él envía cada mensaje.
 *
 * FCM no avisa cuando el mensaje llega al teléfono, así que un push aceptado queda ENVIADA ([confirmaEntrega] = false).
 * Si FCM dice que el token ya no sirve (la app se desinstaló o el token es de otro proyecto), el resultado lo marca
 * con `destinoInvalido` para que el envío borre ese token y el aviso salga por SMS.
 */
class ProveedorFcm(
    private val credenciales: CredencialesFcm,
    private val urlFcm: String = "https://fcm.googleapis.com",
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : Proveedor {
    override val nombre = "fcm"
    override val confirmaEntrega = false

    private var tokenAcceso: String? = null
    private var tokenVence: Instant = Instant.EPOCH

    override fun enviar(m: Mensaje): ResultadoEnvio {
        val acceso = try {
            tokenDeAcceso()
        } catch (e: Exception) {
            return ResultadoEnvio.Fallo("No se pudo obtener el token de Google: ${e.message}")
        }
        val cuerpo = buildJsonObject {
            putJsonObject("message") {
                put("token", m.destino)
                putJsonObject("notification") {
                    put("title", m.titulo)
                    put("body", m.texto)
                }
                putJsonObject("data") { m.datos.forEach { (k, v) -> put(k, v) } }
                // Prioridad alta para que Android lo muestre aunque el teléfono esté en reposo.
                putJsonObject("android") { put("priority", "HIGH") }
            }
        }
        val respuesta = http.send(
            HttpRequest.newBuilder(URI.create("$urlFcm/v1/projects/${credenciales.proyecto}/messages:send"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer $acceso")
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo.toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val json = runCatching { Json.parseToJsonElement(respuesta.body()).jsonObject }.getOrNull()
        if (respuesta.statusCode() == 200) {
            return ResultadoEnvio.Aceptado(json?.get("name")?.jsonPrimitive?.contentOrNull)
        }
        val error = json?.get("error")?.jsonObject
        val estado = error?.get("status")?.jsonPrimitive?.contentOrNull
        val codigoFcm = error?.let(::codigoFcm)
        val texto = "FCM ${respuesta.statusCode()} ${codigoFcm ?: estado ?: ""}: ${error?.get("message")?.jsonPrimitive?.contentOrNull ?: respuesta.body().take(200)}"
        if (respuesta.statusCode() == 401) tokenAcceso = null   // se pide uno nuevo en el siguiente intento
        return when {
            codigoFcm in TOKEN_INVALIDO || estado == "INVALID_ARGUMENT" ->
                ResultadoEnvio.Fallo(texto, destinoInvalido = true)
            else -> ResultadoEnvio.Fallo(texto)   // 401, 403, 429, 5xx: se reintenta
        }
    }

    @Synchronized
    private fun tokenDeAcceso(): String {
        tokenAcceso?.takeIf { Instant.now().isBefore(tokenVence.minusSeconds(60)) }?.let { return it }
        val ahora = Instant.now()
        val afirmacion = JWT.create()
            .withIssuer(credenciales.correo)
            .withAudience(credenciales.urlToken)
            .withClaim("scope", "https://www.googleapis.com/auth/firebase.messaging")
            .withIssuedAt(Date.from(ahora))
            .withExpiresAt(Date.from(ahora.plusSeconds(3600)))
            .sign(Algorithm.RSA256(null, credenciales.llave))
        val formulario = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", Charsets.UTF_8) +
            "&assertion=" + afirmacion
        val respuesta = http.send(
            HttpRequest.newBuilder(URI.create(credenciales.urlToken))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formulario))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(respuesta.statusCode() == 200) { "Google respondió ${respuesta.statusCode()}: ${respuesta.body().take(200)}" }
        val j = Json.parseToJsonElement(respuesta.body()).jsonObject
        val token = j.getValue("access_token").jsonPrimitive.content
        tokenAcceso = token
        tokenVence = ahora.plusSeconds(j["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600)
        return token
    }

    /** El `errorCode` de FCM que viene en `error.details`. */
    private fun codigoFcm(error: JsonObject): String? =
        error["details"]?.jsonArray?.firstNotNullOfOrNull { d ->
            d.jsonObject["errorCode"]?.jsonPrimitive?.contentOrNull
        }

    private companion object {
        /** El token ya no sirve: la app se desinstaló, el token rotó o es de otro proyecto de Firebase. */
        val TOKEN_INVALIDO = setOf("UNREGISTERED", "SENDER_ID_MISMATCH", "INVALID_ARGUMENT")
    }
}
