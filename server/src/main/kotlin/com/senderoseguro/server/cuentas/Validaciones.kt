package com.senderoseguro.server.cuentas

import com.senderoseguro.server.plataforma.ApiException
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Las mismas reglas que valida la app (Módulo 1, `dominio/Validaciones.kt`), con las mismas claves de campo, para
 * que la app muestre cada error junto a su control.
 */
internal object Validaciones {
    const val NOMBRE = "nombre"
    const val TELEFONO = "telefono"
    const val IDENTIFICADOR = "identificador"
    const val ESCUELA = "escuela"
    const val CARRERA = "carrera"
    const val CONTRASENA = "contrasena"
    const val USUARIO = "usuario"
    const val TIPO_COMUNIDAD = "tipoComunidad"
    const val CONSENTIMIENTO = "consentimiento"
    const val DISPOSITIVO = "dispositivo"
    const val PRIORIDAD = "prioridad"
    const val CODIGO = "codigo"
    const val NOMBRE_GRUPO = "nombreGrupo"
    const val MOTIVO = "motivo"

    private val SOLO_LETRAS_Y_ESPACIOS = Regex("^[\\p{L} ]+$")
    private val IDENTIFICADOR_REGEX = Regex("^[A-Za-z0-9]{6,15}$")
    private val CODIGO_REGEX = Regex("^[A-Z0-9]{3}-[A-Z0-9]{3}$")

    /** Quita espacios, guiones, paréntesis y el prefijo +52; deja solo los dígitos. */
    fun normalizarTelefono(entrada: String): String {
        var t = entrada.filter { it.isDigit() || it == '+' }
        if (t.startsWith("+52")) t = t.removePrefix("+52")
        return t.filter { it.isDigit() }
    }

    /** «k7q42m» o «K7Q-42M» → «K7Q-42M»; null si no tiene el formato AAA-999. */
    fun normalizarCodigo(entrada: String): String? {
        val limpio = entrada.trim().uppercase().filter { it.isLetterOrDigit() || it == '-' }
        val conGuion = if (limpio.length == 6 && '-' !in limpio) limpio.substring(0, 3) + "-" + limpio.substring(3) else limpio
        return conGuion.takeIf { CODIGO_REGEX.matches(it) }
    }

    fun nombre(valor: String): String? {
        val n = valor.trim()
        return when {
            n.isEmpty() -> "Escribe tu nombre completo"
            n.length !in 3..120 -> "Debe tener entre 3 y 120 caracteres"
            !SOLO_LETRAS_Y_ESPACIOS.matches(n) -> "Solo letras y espacios"
            else -> null
        }
    }

    fun telefono(valor: String): String? =
        if (normalizarTelefono(valor).length == 10) null else "Escribe los 10 dígitos del número"

    fun identificador(valor: String): String? {
        val v = valor.trim()
        return when {
            v.isEmpty() -> "Escribe tu matrícula o número de empleado"
            !IDENTIFICADOR_REGEX.matches(v) -> "Alfanumérico de 6 a 15 caracteres"
            else -> null
        }
    }

    fun carrera(valor: String): String? = if (valor.trim().length in 3..120) null else "Debe tener entre 3 y 120 caracteres"

    fun contrasena(valor: String): String? = when {
        valor.length < 8 -> "Mínimo 8 caracteres"
        valor.length > 128 -> "Máximo 128 caracteres"
        valor.none { it.isLetter() } || valor.none { it.isDigit() } -> "Debe tener al menos una letra y un número"
        else -> null
    }

    fun nombreGrupo(valor: String): String? = if (valor.trim().length in 3..100) null else "Debe tener entre 3 y 100 caracteres"
}

/** 400 VALIDACION con el error de cada campo en `detalle.errores`. */
internal fun errorValidacion(errores: Map<String, String>, mensaje: String = "Revisa los datos") = ApiException(
    HttpStatusCode.BadRequest, "VALIDACION", mensaje,
    buildJsonObject { putJsonObject("errores") { errores.forEach { (campo, texto) -> put(campo, texto) } } },
)

/** Lanza 400 VALIDACION si [errores] trae algo. */
internal fun exigirValidos(errores: Map<String, String?>) {
    val conError = errores.filterValues { it != null }.mapValues { it.value!! }
    if (conError.isNotEmpty()) throw errorValidacion(conError)
}

/** 409 DUPLICADO, con el campo repetido en `detalle.errores`. */
internal fun errorDuplicado(campo: String, mensaje: String) = ApiException(
    HttpStatusCode.Conflict, "DUPLICADO", mensaje,
    buildJsonObject { putJsonObject("errores") { put(campo, mensaje) } },
)
