package com.escom.sendero_seguro.ui.navegacion

/**
 * Pantallas de la app Android (E3 · Diseño de E/S). El id coincide con el de la ficha
 * de la pantalla (A-01 … A-19) y `cu` con sus casos de uso y requerimientos.
 */
enum class Pantalla(val id: String, val titulo: String, val cu: String, val conBarra: Boolean = false) {
    A01("A-01", "Aviso de privacidad", "CU-01 · RF-1"),
    A02("A-02", "Registro", "CU-01 · RF-1"),
    A03("A-03", "Inicio de sesión", "CU-02 · RF-1, RF-2"),
    A04("A-04", "Inicio", "CU-11, CU-27 · RF-5, RF-18", conBarra = true),
    A05("A-05", "Cuenta regresiva y tipo de alerta", "CU-11, CU-12, CU-13 · RF-5, RF-6, RF-7"),
    A06("A-06", "Describir con voz y sugerencia IA", "CU-13 · RF-6"),
    A07("A-07", "Alerta activa y estado", "CU-11, CU-22 · RF-7, RF-9, RF-11"),
    A08("A-08", "Cancelar alerta", "CU-21 · RF-13"),
    A09("A-09", "Contactos de confianza", "CU-04 · RF-3"),
    A10("A-10", "Círculo", "CU-05, CU-06 · RF-2, RF-3"),
    A11("A-11", "Perfil", "CU-03 · RF-1", conBarra = true),
    A12("A-12", "Activación y energía", "CU-27, CU-11 · RF-5, RF-18"),
    A13("A-13", "Aviso a la red de apoyo", "CU-16 · RF-9"),
    A14("A-14", "Modo operador: alerta ad hoc", "CU-24 · RF-15"),
    A15("A-15", "Levantar reporte", "CU-28, CU-29 · RF-19"),
    A16("A-16", "Feed comunitario", "CU-32 · RF-21", conBarra = true),
    A17("A-17", "Mapa de calor con pronóstico", "CU-33, CU-34, CU-36 · RF-22, RF-25", conBarra = true),
    A18("A-18", "Ruta segura", "CU-35 · RF-23, RF-24, RF-25"),
    A19("A-19", "Conectividad y pendientes", "CU-26, CU-14 · RF-14, RF-16, RF-17"),
    GALERIA("DEV", "Galería de componentes", "—");

    val ruta: String get() = name

    companion object {
        fun deRuta(ruta: String?): Pantalla? = entries.firstOrNull { it.ruta == ruta }
    }
}

/** Siguientes pantallas según el mapa de navegación de la app. */
val SiguientesPantallas: Map<Pantalla, List<Pantalla>> = mapOf(
    Pantalla.A03 to listOf(Pantalla.A04, Pantalla.A01),
    Pantalla.A01 to listOf(Pantalla.A02),
    Pantalla.A02 to listOf(Pantalla.A04),
    Pantalla.A05 to listOf(Pantalla.A06, Pantalla.A07),
    Pantalla.A06 to listOf(Pantalla.A07),
    Pantalla.A07 to listOf(Pantalla.A08),
    Pantalla.A08 to listOf(Pantalla.A04),
    Pantalla.A09 to listOf(Pantalla.A10),
    Pantalla.A11 to listOf(Pantalla.A12),
    Pantalla.A16 to listOf(Pantalla.A15),
    Pantalla.A17 to listOf(Pantalla.A18)
)
