package com.senderoseguro.server.pasarela

/**
 * Números de teléfono. En la base se guardan como los captura la persona (`5512345678` o `+525512345678`),
 * y los proveedores usan el formato internacional E.164 (`+525512345678`). En México un celular puede llegar
 * también con el prefijo antiguo `521`.
 */
object Telefonos {
    /** Formato E.164. Un número de 10 dígitos se toma como de México. */
    fun e164(numero: String): String {
        val d = numero.filter { it.isDigit() }
        return when {
            d.length == 10 -> "+52$d"
            d.length == 13 && d.startsWith("521") -> "+52${d.substring(3)}"
            else -> "+$d"
        }
    }

    /** Las formas en que puede estar guardado en `usuario.telefono_movil` el número que mandó un SMS. */
    fun variantes(numero: String): List<String> {
        val d = numero.filter { it.isDigit() }
        val nacional = when {
            d.length == 12 && d.startsWith("52") -> d.substring(2)
            d.length == 13 && d.startsWith("521") -> d.substring(3)
            d.length == 10 -> d
            else -> return listOf(d, "+$d")
        }
        return listOf(nacional, "52$nacional", "+52$nacional", "521$nacional", "+521$nacional")
    }
}
