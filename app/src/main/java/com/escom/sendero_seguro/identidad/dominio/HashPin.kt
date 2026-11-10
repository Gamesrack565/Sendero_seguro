package com.escom.sendero_seguro.identidad.dominio

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Hash del PIN de cancelación (PBKDF2-HMAC-SHA256 con sal).
 * El resultado se guarda cifrado con una llave del Android Keystore (AlmacenSeguroKeystore),
 * nunca en Room ni en el servidor. La verificación funciona sin internet.
 *
 * Formato: pbkdf2$<iteraciones>$<salBase64>$<hashBase64>
 */
object HashPin {
    private const val ALGORITMO = "PBKDF2WithHmacSHA256"
    private const val ITERACIONES = 60_000
    private const val BYTES_SAL = 16
    private const val BITS_HASH = 256

    fun generar(pin: String, aleatorio: SecureRandom = SecureRandom()): String {
        val sal = ByteArray(BYTES_SAL).also { aleatorio.nextBytes(it) }
        val hash = derivar(pin, sal, ITERACIONES)
        val b64 = Base64.getEncoder()
        return "pbkdf2$$ITERACIONES$${b64.encodeToString(sal)}$${b64.encodeToString(hash)}"
    }

    fun verificar(pin: String, guardado: String): Boolean {
        val partes = guardado.split('$')
        if (partes.size != 4 || partes[0] != "pbkdf2") return false
        val iteraciones = partes[1].toIntOrNull() ?: return false
        val b64 = Base64.getDecoder()
        val sal = runCatching { b64.decode(partes[2]) }.getOrNull() ?: return false
        val esperado = runCatching { b64.decode(partes[3]) }.getOrNull() ?: return false
        val calculado = derivar(pin, sal, iteraciones)
        return MessageDigest.isEqual(esperado, calculado) // comparación en tiempo constante
    }

    private fun derivar(pin: String, sal: ByteArray, iteraciones: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), sal, iteraciones, BITS_HASH)
        try {
            return SecretKeyFactory.getInstance(ALGORITMO).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
