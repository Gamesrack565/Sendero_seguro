package com.escom.sendero_seguro.emergencias

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.*

class GestorCaminataSegura(
    private val scope: CoroutineScope,
    private val onTiempoCheckInVencido: () -> Unit,
    private val onDesviacionRutaExcedida: (distanciaMetros: Double) -> Unit,
    private val onDestinoAlcanzadoExitosamente: () -> Unit = {}
) {

    companion object {
        private const val TAG = "MODULO2_CAMINATA_P7"
        private const val RADIO_TIERRA_METROS = 6371000.0
        private const val RADIO_LLEGADA_DESTINO_METROS = 20.0
    }

    // Configurable desde P3 (Configuración de Parámetros de Seguridad)
    var intervaloCheckInPorDefectoSeg: Long = 60L

    private var intervaloCheckInActualSeg: Long = 60L
    private var toleranciaDesviacionMetros: Double = 100.0
    private var jobTemporizador: Job? = null

    private val _estadoCaminata = MutableStateFlow(EstadoCaminataSegura())
    val estadoCaminata: StateFlow<EstadoCaminataSegura> = _estadoCaminata.asStateFlow()

    /**
     * P7: Inicia el Modo Acompañamiento con ruta objetivo, ETA y temporizador de check-in.
     */
    fun iniciarCaminataSegura(
        rutaObjetivo: List<PuntoRuta>,
        tiempoEstimadoLlegadaMs: Long,
        intervaloCheckInSeg: Long = intervaloCheckInPorDefectoSeg,
        toleranciaMetros: Double = 100.0
    ) {
        this.intervaloCheckInActualSeg = intervaloCheckInSeg
        this.toleranciaDesviacionMetros = toleranciaMetros
        val etaSegundos = (tiempoEstimadoLlegadaMs / 1000L).coerceAtLeast(intervaloCheckInSeg)

        _estadoCaminata.value = EstadoCaminataSegura(
            activa = true,
            rutaObjetivo = rutaObjetivo,
            tiempoEstimadoLlegadaMs = tiempoEstimadoLlegadaMs,
            segundosRestantesEta = etaSegundos,
            segundosRestantesCheckIn = intervaloCheckInSeg,
            desviacionActualMetros = 0.0,
            distanciaAlDestinoMetros = 0.0,
            fueraDeRuta = false,
            destinoAlcanzado = false
        )

        Log.i(TAG, "Caminata Segura INICIADA (Check-in: ${intervaloCheckInSeg}s | ETA: ${etaSegundos}s | Tolerancia: ${toleranciaMetros}m)")
        iniciarTemporizadores(reiniciarSoloCheckIn = false)
    }

    /**
     * P7: El usuario confirma periódicamente su presencia y reinicia la cuenta regresiva de check-in.
     */
    fun confirmarPresenciaCheckIn() {
        if (!_estadoCaminata.value.activa) return
        Log.i(TAG, "Check-in confirmado por el usuario. Reiniciando cuenta regresiva a ${intervaloCheckInActualSeg}s.")
        _estadoCaminata.value = _estadoCaminata.value.copy(
            segundosRestantesCheckIn = intervaloCheckInActualSeg
        )
    }

    /**
     * P7: Evalúa la desviación geográfica contra los segmentos de la ruta y verifica si ya llegó al destino final.
     */
    fun evaluarUbicacionEnRuta(latitudActual: Double, longitudActual: Double) {
        val estadoActual = _estadoCaminata.value
        if (!estadoActual.activa || estadoActual.rutaObjetivo.isEmpty()) return

        val puntoActual = PuntoRuta(latitudActual, longitudActual)

        // 1. Verificar distancia al destino final (último nodo de la ruta)
        val nodoDestino = estadoActual.rutaObjetivo.last()
        val distanciaAlDestino = calcularDistanciaHaversineMetros(puntoActual, nodoDestino)

        if (distanciaAlDestino <= RADIO_LLEGADA_DESTINO_METROS && estadoActual.rutaObjetivo.size > 1) {
            Log.i(TAG, "¡Usuario llegó al destino final (${distanciaAlDestino.toInt()}m)! Finalizando Caminata Segura.")
            jobTemporizador?.cancel()
            _estadoCaminata.value = estadoActual.copy(
                activa = false,
                distanciaAlDestinoMetros = distanciaAlDestino,
                desviacionActualMetros = 0.0,
                fueraDeRuta = false,
                destinoAlcanzado = true
            )
            onDestinoAlcanzadoExitosamente()
            return
        }

        // 2. Calcular la distancia mínima hacia la ruta objetivo
        val distanciaMinima = estadoActual.rutaObjetivo.minOf { puntoRuta ->
            calcularDistanciaHaversineMetros(puntoActual, puntoRuta)
        }

        val excedioTolerancia = distanciaMinima > toleranciaDesviacionMetros

        _estadoCaminata.value = estadoActual.copy(
            desviacionActualMetros = distanciaMinima,
            distanciaAlDestinoMetros = distanciaAlDestino,
            fueraDeRuta = excedioTolerancia
        )

        if (excedioTolerancia) {
            Log.w(TAG, "¡Desviación geográfica detectada! Distancia: ${distanciaMinima.toInt()}m (Tolerancia: ${toleranciaDesviacionMetros.toInt()}m)")
            onDesviacionRutaExcedida(distanciaMinima)
        }
    }

    fun finalizarCaminataSegura() {
        jobTemporizador?.cancel()
        _estadoCaminata.value = EstadoCaminataSegura(activa = false)
        Log.i(TAG, "Caminata Segura FINALIZADA.")
    }

    private fun iniciarTemporizadores(reiniciarSoloCheckIn: Boolean) {
        jobTemporizador?.cancel()
        jobTemporizador = scope.launch {
            while (isActive && _estadoCaminata.value.activa) {
                delay(1000L)
                val actual = _estadoCaminata.value
                if (!actual.activa) break

                val nuevoCheckIn = (actual.segundosRestantesCheckIn - 1).coerceAtLeast(0L)
                val nuevoEta = (actual.segundosRestantesEta - 1).coerceAtLeast(0L)

                _estadoCaminata.value = actual.copy(
                    segundosRestantesCheckIn = nuevoCheckIn,
                    segundosRestantesEta = nuevoEta
                )

                if (nuevoCheckIn == 0L) {
                    Log.e(TAG, "¡Tiempo de Check-in VENCIDO en Caminata Segura! Disparando alerta automática (P4)...")
                    onTiempoCheckInVencido()
                    break
                }
            }
        }
    }

    fun calcularDistanciaHaversineMetros(p1: PuntoRuta, p2: PuntoRuta): Double {
        val dLat = Math.toRadians(p2.latitud - p1.latitud)
        val dLon = Math.toRadians(p2.longitud - p1.longitud)
        val lat1 = Math.toRadians(p1.latitud)
        val lat2 = Math.toRadians(p2.latitud)

        val a = sin(dLat / 2).pow(2.0) + sin(dLon / 2).pow(2.0) * cos(lat1) * cos(lat2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return RADIO_TIERRA_METROS * c
    }
}