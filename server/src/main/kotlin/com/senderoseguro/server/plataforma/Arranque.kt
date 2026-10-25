package com.senderoseguro.server.plataforma

import io.ktor.server.config.ApplicationConfig

/** Valores de `application.conf` que solo sirven en una computadora local. */
private val LLAVES_DE_DESARROLLO = mapOf(
    "jwt.secreto" to ("JWT_SECRETO" to "solo-para-desarrollo-cambiar-en-la-nube"),
    "enlace.secreto" to ("ENLACE_SECRETO" to "solo-para-desarrollo-enlaces"),
)

/** Largo mínimo de una llave propia, para que no se pueda adivinar. */
const val LARGO_MINIMO_LLAVE = 32

/**
 * Fuera del modo de prueba (`DB_DATOS_PRUEBA=false`), revisa que las llaves no sean las del repo: con ellas cualquiera
 * podría firmar un JWT de Administrador o un enlace de ubicación. Regresa los problemas encontrados, vacío si todo está
 * bien. En modo de prueba no exige nada.
 */
fun problemasDeArranque(config: ApplicationConfig, modoDesarrollo: Boolean): List<String> {
    if (modoDesarrollo) return emptyList()
    return LLAVES_DE_DESARROLLO.mapNotNull { (ruta, variableYDefecto) ->
        val (variable, defecto) = variableYDefecto
        val valor = config.propertyOrNull(ruta)?.getString().orEmpty()
        when {
            valor == defecto -> "Falta $variable: tiene el valor de desarrollo del repo"
            valor.length < LARGO_MINIMO_LLAVE -> "$variable debe tener al menos $LARGO_MINIMO_LLAVE caracteres"
            else -> null
        }
    }
}
