package com.senderoseguro.server.dispersion

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration

/**
 * Temporizador de la dispersión (A-03). En cada vuelta:
 * 1. despacha las alertas que se quedaron sin despachar;
 * 2. escala las alertas cuyo plazo de acuse ya venció.
 * Se detiene solo cuando se apaga el servidor.
 */
fun iniciarTemporizador(scope: CoroutineScope, cada: Duration, dispersion: DispersionServicio, escalamiento: EscalamientoServicio) {
    val log = LoggerFactory.getLogger("com.senderoseguro.server.dispersion.Temporizador")
    scope.launch(Dispatchers.IO) {
        while (isActive) {
            try {
                dispersion.despacharPendientes()
                val n = escalamiento.escalarVencidas()
                if (n > 0) log.info("Escalamiento: {} alertas procesadas", n)
            } catch (e: Exception) {
                log.error("Falló la vuelta del temporizador", e)
            }
            delay(cada)
        }
    }
}
