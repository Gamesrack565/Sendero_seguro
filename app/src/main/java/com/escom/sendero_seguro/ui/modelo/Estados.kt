package com.escom.sendero_seguro.ui.modelo

// Enumerados que muestra la interfaz. Los nombres son idénticos a los de la base de datos
// (alerta_estado, canal_recepcion, riesgo_nivel) para mapearlos sin traducción.

/** E-01 · alerta.estado */
enum class EstadoAlerta(val textoLlano: String) {
    CREADA("Preparando"),
    EN_CUENTA_REGRESIVA("Cuenta regresiva"),
    PENDIENTE_ENVIO("Esperando señal"),
    EXPIRADA("Expirada"),
    ENVIADA("Enviada"),
    ASIGNADA("Una unidad va en camino"),
    ESCALADA("Buscando otra unidad"),
    ACUSADA("Recibida por la unidad"),
    EN_ATENCION("En atención"),
    ATENDIDA("Atendida"),
    CERRADA("Cerrada"),
    CANCELADA("Cancelada")
}

/**
 * E-05 · canal por el que sale la alerta. EN_COLA es el estado visual de lo que aún no sale.
 * `modo` es el texto del chip de conectividad de A-04 (En línea / Malla / SMS / Sin conexión).
 */
enum class CanalEnvio(val etiqueta: String, val modo: String) {
    INTERNET("INTERNET", "En línea"),
    MALLA("MALLA", "Malla"),
    SMS("SMS", "SMS"),
    EN_COLA("EN COLA", "Sin conexión")
}

/** riesgo_nivel */
enum class NivelRiesgo { BAJO, MEDIO, ALTO }
