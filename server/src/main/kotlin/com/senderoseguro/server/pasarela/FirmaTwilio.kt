package com.senderoseguro.server.pasarela

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Firma con la que Twilio llama a los webhooks (encabezado `X-Twilio-Signature`): Base64 de HMAC-SHA1 con el
 * Auth Token como llave, sobre la URL exacta que llamó Twilio seguida de cada parámetro del formulario
 * (nombre y valor, ordenados por nombre).
 */
object FirmaTwilio {
    fun calcular(authToken: String, url: String, parametros: Map<String, List<String>>): String {
        val datos = StringBuilder(url)
        parametros.keys.sorted().forEach { nombre -> parametros.getValue(nombre).forEach { datos.append(nombre).append(it) } }
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(authToken.toByteArray(), "HmacSHA1")) }
        return Base64.getEncoder().encodeToString(mac.doFinal(datos.toString().toByteArray(Charsets.UTF_8)))
    }

    fun valida(authToken: String, url: String, parametros: Map<String, List<String>>, firma: String?): Boolean =
        firma != null && MessageDigest.isEqual(calcular(authToken, url, parametros).toByteArray(), firma.toByteArray())
}
