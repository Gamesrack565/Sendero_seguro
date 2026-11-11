package com.escom.sendero_seguro.emergencias.remoto

import com.escom.sendero_seguro.emergencias.DatosTelemetria
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * P6: las ubicaciones de la alerta activa hacia el servidor. Cada lectura del GPS se guarda en pendientes y se
 * manda en cuanto hay internet; lo que se juntó sin señal sale después en grupos de [porEnvio]. El servidor no
 * repite una lectura (misma alerta, hora y fuente), así que reenviar es seguro.
 *
 * Las pendientes viven en memoria, como la alerta activa del Módulo 2, y se guardan como máximo [maximo] (las más
 * viejas se descartan primero).
 */
class EnvioUbicaciones(
    private val canal: CanalUbicaciones,
    private val maximo: Int = 500,
    private val porEnvio: Int = 100,
) {
    private val candado = Mutex()
    private val pendientes = ArrayDeque<LecturaUbicacionDto>()
    private var terminada = false

    suspend fun agregar(lectura: DatosTelemetria) = candado.withLock {
        if (terminada) return@withLock
        pendientes.addLast(ServidorAlertasRetrofit.aDto(lectura))
        while (pendientes.size > maximo) pendientes.removeFirst()
    }

    /** Manda lo pendiente de [alertaId]. Regresa cuántas lecturas entregó. */
    suspend fun enviarPendientes(alertaId: String): Int = candado.withLock {
        var entregadas = 0
        while (pendientes.isNotEmpty() && !terminada) {
            val grupo = pendientes.take(porEnvio)
            when (canal.enviar(alertaId, grupo)) {
                ResultadoUbicaciones.ENTREGADAS -> { repeat(grupo.size) { pendientes.removeFirst() }; entregadas += grupo.size }
                ResultadoUbicaciones.DESCARTAR -> repeat(grupo.size) { pendientes.removeFirst() }
                ResultadoUbicaciones.ALERTA_TERMINADA -> { pendientes.clear(); terminada = true }
                ResultadoUbicaciones.REINTENTAR -> return@withLock entregadas
            }
        }
        entregadas
    }

    suspend fun pendientes(): Int = candado.withLock { pendientes.size }

    /** Para una alerta nueva o cuando la alerta termina en el teléfono. */
    suspend fun reiniciar() = candado.withLock { pendientes.clear(); terminada = false }
}
