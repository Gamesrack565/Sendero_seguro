package com.escom.sendero_seguro.identidad.datos.seguro

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.escom.sendero_seguro.identidad.dominio.TokenGuardado
import com.escom.sendero_seguro.identidad.puertos.AlmacenSeguro
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Token de sesión y hash del PIN cifrados con AES-256-GCM. La llave vive en el Android Keystore
 * y no se puede extraer del teléfono; en SharedPreferences solo queda el texto cifrado (IV + datos).
 */
class AlmacenSeguroKeystore(context: Context) : AlmacenSeguro {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)

    override fun guardarToken(token: String, expiraEnMs: Long) {
        prefs.edit()
            .putString(K_TOKEN, cifrar(token))
            .putLong(K_TOKEN_EXPIRA, expiraEnMs)
            .apply()
    }

    override fun leerToken(): TokenGuardado? {
        val token = prefs.getString(K_TOKEN, null)?.let(::descifrar) ?: return null
        return TokenGuardado(token, prefs.getLong(K_TOKEN_EXPIRA, 0L))
    }

    override fun borrarToken() {
        prefs.edit().remove(K_TOKEN).remove(K_TOKEN_EXPIRA).apply()
    }

    override fun guardarHashPin(hash: String) {
        // commit(): el PIN debe quedar escrito antes de que la persona pueda emitir una alerta.
        prefs.edit().putString(K_PIN, cifrar(hash)).commit()
    }

    override fun leerHashPin(): String? = prefs.getString(K_PIN, null)?.let(::descifrar)

    override fun guardarHashPinCoaccion(hash: String) {
        prefs.edit().putString(K_PIN_COACCION, cifrar(hash)).commit()
    }

    override fun leerHashPinCoaccion(): String? = prefs.getString(K_PIN_COACCION, null)?.let(::descifrar)

    override fun borrarHashPinCoaccion() {
        prefs.edit().remove(K_PIN_COACCION).commit()
    }

    override fun borrarTodo() {
        prefs.edit().clear().commit()
    }

    // ------------------------------------------------------------------ cifrado

    private fun llave(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun cifrar(texto: String): String {
        val cipher = Cipher.getInstance(TRANSFORMACION)
        cipher.init(Cipher.ENCRYPT_MODE, llave())
        val iv = cipher.iv
        val datos = cipher.doFinal(texto.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + datos, Base64.NO_WRAP)
    }

    /** Si la llave cambió (p. ej. restauración de respaldo), se descarta el valor en vez de fallar. */
    private fun descifrar(guardado: String): String? = runCatching {
        val bytes = Base64.decode(guardado, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val datos = bytes.copyOfRange(IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMACION)
        cipher.init(Cipher.DECRYPT_MODE, llave(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(datos), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val ARCHIVO = "sendero_identidad_seguro"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "sendero_identidad_aes"
        const val TRANSFORMACION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val K_TOKEN = "token"
        const val K_TOKEN_EXPIRA = "token_expira_en"
        const val K_PIN = "pin_hash"
        const val K_PIN_COACCION = "pin_coaccion_hash"
    }
}
