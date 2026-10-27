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
    private val onDesviacionRutaExcedida: (distanciaMetros: Double) -> Unit
) {

    companion object {
        private const val TAG = "MODULO2_CAMINATA_P7"
        private const val RADIO_TIERRA_METROS = 6371000.0
    }

    private var intervaloCheckInSegundos: Long = 60L
    private var toleranciaDesviacionMetros: Double = 100.0 // 100 metros de tolerancia por defecto
    private var jobTemporizador: Job? = null

    private val _estadoCaminata = MutableStateFlow(EstadoCaminataSegura())
    val estadoCaminata: StateFlow<EstadoCaminataSegura> = _estadoCaminata.asStateFlow()

    /**
     * P7: Inicia el Modo Acompañamiento con ruta objetivo, ETA y temporizador de check-in.
     */
    fun iniciarCaminataSegura(
        rutaObjetivo: List<PuntoRuta>,
        tiempoEstimadoLlegadaMs: Long,
        intervaloCheckInSeg: Long = 60L,
        toleranciaMetros: Double = 100.0
    ) {
        this.intervaloCheckInSegundos = intervaloCheckInSeg
        this.toleranciaDesviacionMetros = toleranciaMetros

        _estadoCaminata.value = EstadoCaminataSegura(
            activa = true,
            rutaObjetivo = rutaObjetivo,
            tiempoEstimadoLlegadaMs = tiempoEstimadoLlegadaMs,
            segundosRestantesCheckIn = intervaloCheckInSeg,
            desviacionActualMetros = 0.0,
            fueraDeRuta = false
        )

        Log.i(TAG, "Caminata Segura INICIADA (Check-in cada ${intervaloCheckInSeg}s | Tolerancia: ${toleranciaMetros}m)")
        iniciarCuentaRegresivaCheckIn()
    }

    /**
     * P7: El usuario confirma periódicamente su presencia y reinicia la cuenta regresiva.
     */
    fun confirmarPresenciaCheckIn() {
        if (!_estadoCaminata.value.activa) return
        Log.i(TAG, "Check-in confirmado por el usuario. Reiniciando cuenta regresiva a ${intervaloCheckInSegundos}s.")
        iniciarCuentaRegresivaCheckIn()
    }

    /**
     * P7: Evalúa si la coordenada actual del usuario se desvió más allá de la tolerancia permitida.
     */
    fun evaluarUbicacionEnRuta(latitudActual: Double, longitudActual: Double) {
        val estadoActual = _estadoCaminata.value
        if (!estadoActual.activa || estadoActual.rutaObjetivo.isEmpty()) return

        val puntoActual = PuntoRuta(latitudActual, longitudActual)
        // Calcula la distancia mínima hacia los nodos de la ruta objetivo
        val distanciaMinima = estadoActual.rutaObjetivo.minOf { puntoRuta ->
            calcularDistanciaHaversineMetros(puntoActual, puntoRuta)
        }

        val excedioTolerancia = distanciaMinima > toleranciaDesviacionMetros

        _estadoCaminata.value = estadoActual.copy(
            desviacionActualMetros = distanciaMinima,
            fueraDeRuta = excedioTolerancia
        )

        if (excedioTolerancia) {
            Log.w(TAG, "¡Desviación geográfica detectada! Distancia a la ruta: ${distanciaMinima.toInt()}m (Tolerancia: ${toleranciaDesviacionMetros.toInt()}m)")
            onDesviacionRutaExcedida(distanciaMinima)
        } else {
            Log.d(TAG, "Usuario dentro de ruta segura. Desviación: ${distanciaMinima.toInt()}m")
        }
    }

    fun finalizarCaminataSegura() {
        jobTemporizador?.cancel()
        _estadoCaminata.value = EstadoCaminataSegura(activa = false)
        Log.i(TAG, "Caminata Segura FINALIZADA correctamente.")
    }

    private fun iniciarCuentaRegresivaCheckIn() {
        jobTemporizador?.cancel()
        jobTemporizador = scope.launch {
            var segundos = intervaloCheckInSegundos
            while (isActive && segundos > 0 && _estadoCaminata.value.activa) {
                _estadoCaminata.value = _estadoCaminata.value.copy(segundosRestantesCheckIn = segundos)
                delay(1000L)
                segundos--
            }

            if (isActive && segundos == 0L && _estadoCaminata.value.activa) {
                _estadoCaminata.value = _estadoCaminata.value.copy(segundosRestantesCheckIn = 0L)
                Log.e(TAG, "¡Tiempo de Check-in VENCIDO en Caminata Segura! Disparando alerta automática (P4)...")
                onTiempoCheckInVencido()
            }
        }
    }

    /**
     * Fórmula de Haversine para calcular distancia en metros entre dos coordenadas GPS.
     */
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