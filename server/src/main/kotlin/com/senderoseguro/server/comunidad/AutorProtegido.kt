package com.senderoseguro.server.comunidad

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Anonimato protegido (CU-29, RNF11): el id de quien levanta un reporte anónimo se guarda cifrado con AES-256-GCM en
 * `reporte.autor_cifrado`. La llave sale de REPORTES_LLAVE_AUTOR (SHA-256 del texto) y nunca se guarda en la base.
 * Solo el Administrador lo descifra, con justificación y registro en la bitácora (CU-31).
 *
 * Formato: 12 bytes de IV seguidos del texto cifrado con su etiqueta. `version` va en `reporte.clave_version` para
 * poder cambiar la llave más adelante sin perder los reportes anteriores.
 */
class AutorProtegido(llave: String, val version: Short = 1) {
    private val clave = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(llave.toByteArray()), "AES")
    private val azar = SecureRandom()

    fun cifrar(usuarioId: UUID): ByteArray {
        val iv = ByteArray(12).also(azar::nextBytes)
        val cifrador = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, clave, GCMParameterSpec(128, iv)) }
        val datos = ByteBuffer.allocate(16).putLong(usuarioId.mostSignificantBits).putLong(usuarioId.leastSignificantBits).array()
        return iv + cifrador.doFinal(datos)
    }

    fun descifrar(cifrado: ByteArray): UUID {
        val cifrador = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, clave, GCMParameterSpec(128, cifrado, 0, 12))
        }
        val b = ByteBuffer.wrap(cifrador.doFinal(cifrado, 12, cifrado.size - 12))
        return UUID(b.long, b.long)
    }
}
