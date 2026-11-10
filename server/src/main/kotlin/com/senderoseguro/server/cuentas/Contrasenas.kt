package com.senderoseguro.server.cuentas

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.OpenBSDBCrypt
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Contraseñas (RNF9): se guardan con Argon2id, con los parámetros mínimos que recomienda OWASP (19 MiB de memoria,
 * 2 pasadas, 1 hilo). El texto queda en el formato estándar `$argon2id$v=19$m=19456,t=2,p=1$<sal>$<hash>`.
 * También se aceptan hash bcrypt (`$2a$…`), que son los de las cuentas de prueba.
 */
object Contrasenas {
    private const val MEMORIA_KB = 19_456
    private const val PASADAS = 2
    private const val HILOS = 1
    private const val LARGO_SAL = 16
    private const val LARGO_HASH = 32

    private val azar = SecureRandom()
    private val b64 = Base64.getEncoder().withoutPadding()
    private val desdeB64 = Base64.getDecoder()

    /** Hash de una contraseña dummy, para tardar lo mismo cuando la cuenta no existe. */
    val HASH_SIMULADO: String by lazy { cifrar("sin-cuenta-${azar.nextLong()}") }

    fun cifrar(contrasena: String): String {
        val sal = ByteArray(LARGO_SAL).also(azar::nextBytes)
        val hash = argon2(contrasena, sal, MEMORIA_KB, PASADAS, HILOS, LARGO_HASH)
        return "\$argon2id\$v=19\$m=$MEMORIA_KB,t=$PASADAS,p=$HILOS\$${b64.encodeToString(sal)}\$${b64.encodeToString(hash)}"
    }

    fun verificar(contrasena: String, guardado: String): Boolean = runCatching {
        when {
            guardado.startsWith("\$argon2id\$") -> verificarArgon2(contrasena, guardado)
            guardado.startsWith("\$2a\$") || guardado.startsWith("\$2b\$") || guardado.startsWith("\$2y\$") ->
                OpenBSDBCrypt.checkPassword(guardado, contrasena.toCharArray())
            else -> false
        }
    }.getOrDefault(false)

    private fun verificarArgon2(contrasena: String, guardado: String): Boolean {
        // $argon2id$v=19$m=19456,t=2,p=1$<sal>$<hash>
        val partes = guardado.split('$')
        require(partes.size == 6 && partes[2] == "v=19")
        val params = partes[3].split(',').associate { it.substringBefore('=') to it.substringAfter('=').toInt() }
        val sal = desdeB64.decode(partes[4])
        val esperado = desdeB64.decode(partes[5])
        val calculado = argon2(contrasena, sal, params.getValue("m"), params.getValue("t"), params.getValue("p"), esperado.size)
        return MessageDigest.isEqual(esperado, calculado)
    }

    private fun argon2(contrasena: String, sal: ByteArray, memoriaKb: Int, pasadas: Int, hilos: Int, largo: Int): ByteArray {
        val parametros = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memoriaKb)
            .withIterations(pasadas)
            .withParallelism(hilos)
            .withSalt(sal)
            .build()
        return ByteArray(largo).also { salida ->
            Argon2BytesGenerator().apply { init(parametros) }.generateBytes(contrasena.toByteArray(Charsets.UTF_8), salida)
        }
    }
}
