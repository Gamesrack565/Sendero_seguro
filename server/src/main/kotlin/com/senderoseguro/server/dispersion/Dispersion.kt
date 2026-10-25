package com.senderoseguro.server.dispersion

import java.time.Instant
import java.util.UUID

/**
 * Punto de entrada de la dispersión (P5). Quien registra una alerta nueva (internet, SMS o sincronización)
 * llama a [despachar] después de guardarla.
 */
interface Dispersion {
    /** Canaliza la alerta y le asigna la unidad disponible más cercana. No bloquea a quien la llama. */
    fun despachar(alertaId: UUID)
}

/** Qué pasó al despachar una alerta. */
sealed interface ResultadoDispersion {
    /** Se asignó una unidad. La unidad tiene hasta [acuseLimiteEn] para dar el acuse. */
    data class Asignada(
        val unidadId: UUID,
        val unidadNombre: String,
        val corporacion: String,
        val distanciaM: Double?,
        val acuseLimiteEn: Instant,
    ) : ResultadoDispersion

    /** Hay regla, pero la corporación no tiene unidades disponibles. La alerta queda ENVIADA y se escala. */
    data class SinUnidad(val corporacion: String) : ResultadoDispersion

    /** No hay regla para el tipo ni para la genérica en la versión del catálogo de la alerta. */
    data object SinRegla : ResultadoDispersion

    /** La alerta ya no está ENVIADA (ya se asignó, se canceló o se cerró). No se hace nada. */
    data class YaDespachada(val estado: String) : ResultadoDispersion

    data object NoExiste : ResultadoDispersion
}
