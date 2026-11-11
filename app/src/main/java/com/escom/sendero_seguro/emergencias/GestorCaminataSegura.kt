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
import java.util.PriorityQueue
import kotlin.math.*

class GestorCaminataSegura(
    private val scope: CoroutineScope,
    private val onTiempoCheckInVencido: () -> Unit,
    private val onDesviacionRutaExcedida: (distanciaMetros: Double) -> Unit,
    private val onDestinoAlcanzadoExitosamente: () -> Unit = {}
) {

    companion object {
        private const val TAG = "MODULO2_RUTA_Y_CAMINATA_P7"
        private const val RADIO_TIERRA_METROS = 6371000.0
        private const val RADIO_LLEGADA_DESTINO_METROS = 20.0
        private const val VELOCIDAD_PEATON_MPS = 1.32 // ~4.75 km/h
    }

    var intervaloCheckInPorDefectoSeg: Long = 60L
    // E3 P7 / W-10: Factor alfa para penalizar tramos de riesgo en Ruta Segura
    var factorAlfaRiesgo: Double = 3.5

    private var intervaloCheckInActualSeg: Long = 60L
    private var toleranciaDesviacionMetros: Double = 100.0
    private var jobTemporizador: Job? = null

    private val _estadoCaminata = MutableStateFlow(EstadoCaminataSegura())
    val estadoCaminata: StateFlow<EstadoCaminataSegura> = _estadoCaminata.asStateFlow()

    private val _comparativaRutasOffline = MutableStateFlow<ComparativaRutasP7?>(null)
    val comparativaRutasOffline: StateFlow<ComparativaRutasP7?> = _comparativaRutasOffline.asStateFlow()

    // =========================================================================
    // PARTE A: E3 P7 (A-18) CÁLCULO DE RUTA SEGURA OFFLINE CON GRAFO LOCAL
    // =========================================================================

    /**
     * E3 P7 (A-18): Traza opciones de camino entre el origen y destino usando el grafo local
     * sin conexión y compara la «Ruta segura RECOMENDADA» frente a la «Más corta».
     */
    fun calcularRutasEnDispositivoOffline(
        origen: PuntoRuta,
        destino: PuntoRuta,
        aristasGrafoCache: List<AristaVialOffline> = obtenerGrafoZacatencoPorDefecto()
    ): ComparativaRutasP7 {
        // 1. Calcular Ruta Más Corta (alfa = 0.0 -> solo importa la distancia física)
        val rutaMasCorta = ejecutarDijkstraPonderado(
            origen = origen,
            destino = destino,
            aristas = aristasGrafoCache,
            alfa = 0.0,
            nombreRuta = "Más corta",
            esRecomendada = false
        )

        // 2. Calcular Ruta Segura Recomendada (ponderando riesgo IA-2 v42 e iluminación)
        val rutaSegura = ponderarRiesgoEIluminacionRuta(
            origen = origen,
            destino = destino,
            aristasGrafoCache = aristasGrafoCache,
            alfa = factorAlfaRiesgo
        )

        val comparativa = ComparativaRutasP7(
            rutaSeguraRecomendada = rutaSegura,
            rutaMasCorta = rutaMasCorta,
            versionCapaRiesgo = 42
        )
        _comparativaRutasOffline.value = comparativa
        return comparativa
    }

    /**
     * E3 P7 (A-18): Evalúa los trazos contra la capa local de riesgo predictivo (IA-2 v42)
     * y reportes de iluminación usando la fórmula oficial: peso = distancia * (1 + alfa * riesgo).
     */
    fun ponderarRiesgoEIluminacionRuta(
        origen: PuntoRuta,
        destino: PuntoRuta,
        aristasGrafoCache: List<AristaVialOffline>,
        alfa: Double = factorAlfaRiesgo
    ): OpcionRutaCalculada {
        return ejecutarDijkstraPonderado(
            origen = origen,
            destino = destino,
            aristas = aristasGrafoCache,
            alfa = alfa,
            nombreRuta = "Ruta segura RECOMENDADA",
            esRecomendada = true
        )
    }

    private fun ejecutarDijkstraPonderado(
        origen: PuntoRuta,
        destino: PuntoRuta,
        aristas: List<AristaVialOffline>,
        alfa: Double,
        nombreRuta: String,
        esRecomendada: Boolean
    ): OpcionRutaCalculada {
        if (aristas.isEmpty()) {
            val distDirecta = calcularDistanciaHaversineMetros(origen, destino)
            return OpcionRutaCalculada(
                nombreRuta = nombreRuta,
                esRecomendada = esRecomendada,
                nodos = listOf(origen, destino),
                distanciaTotalKm = ((distDirecta / 1000.0) * 100.0).roundToInt() / 100.0,
                tiempoEstimadoMin = ((distDirecta / VELOCIDAD_PEATON_MPS) / 60.0).roundToInt().coerceAtLeast(1),
                puntajeRiesgoPromedio = 0.15,
                advertenciasZonasRiesgo = emptyList()
            )
        }

        val todosLosNodos = (aristas.map { it.nodoOrigen } + aristas.map { it.nodoDestino })
            .distinctBy { "${it.latitud}_${it.longitud}" }

        val nodoInicial = todosLosNodos.minByOrNull { calcularDistanciaHaversineMetros(origen, it) } ?: origen
        val nodoFinal = todosLosNodos.minByOrNull { calcularDistanciaHaversineMetros(destino, it) } ?: destino

        val costos = HashMap<PuntoRuta, Double>().withDefault { Double.MAX_VALUE }
        val previos = HashMap<PuntoRuta, Pair<PuntoRuta, AristaVialOffline>>()
        val cola = PriorityQueue<Pair<PuntoRuta, Double>>(compareBy { it.second })

        costos[nodoInicial] = 0.0
        cola.add(nodoInicial to 0.0)

        while (cola.isNotEmpty()) {
            val (actual, costoActual) = cola.poll()
            if (actual == nodoFinal) break
            if (costoActual > costos.getValue(actual)) continue

            val vecinas = aristas.filter { it.nodoOrigen == actual || it.nodoDestino == actual }
            for (arista in vecinas) {
                val vecino = if (arista.nodoOrigen == actual) arista.nodoDestino else arista.nodoOrigen
                val penalizacionSinLuz = if (!arista.bienIluminada && alfa > 0.0) 0.25 else 0.0
                val riesgoEfectivo = (arista.riesgoNormalizado + penalizacionSinLuz).coerceIn(0.0, 1.0)

                // Fórmula oficial del E3 (Diccionario de datos: riesgo_tramo / A-18)
                val pesoTramo = arista.longitudMetros * (1.0 + alfa * riesgoEfectivo)
                val nuevoCosto = costoActual + pesoTramo

                if (nuevoCosto < costos.getValue(vecino)) {
                    costos[vecino] = nuevoCosto
                    previos[vecino] = actual to arista
                    cola.add(vecino to nuevoCosto)
                }
            }
        }

        val caminoNodos = mutableListOf<PuntoRuta>()
        val aristasRecorridas = mutableListOf<AristaVialOffline>()
        var cursor: PuntoRuta? = nodoFinal

        while (cursor != null) {
            caminoNodos.add(0, cursor)
            val pasoPrevio = previos[cursor]
            if (pasoPrevio != null) {
                aristasRecorridas.add(0, pasoPrevio.second)
                cursor = pasoPrevio.first
            } else {
                cursor = null
            }
        }

        val distanciaMetros = if (aristasRecorridas.isNotEmpty()) {
            aristasRecorridas.sumOf { it.longitudMetros }
        } else {
            calcularDistanciaHaversineMetros(origen, destino)
        }

        val riesgoMedio = if (aristasRecorridas.isNotEmpty()) {
            aristasRecorridas.map { it.riesgoNormalizado }.average()
        } else 0.1

        val advertencias = aristasRecorridas
            .filter { it.riesgoNormalizado >= 0.65 && it.zonaRiesgoNombre != null }
            .mapNotNull { "Cruza «${it.zonaRiesgoNombre}» (riesgo ALTO, 21-24 h)" }
            .distinct()

        return OpcionRutaCalculada(
            nombreRuta = nombreRuta,
            esRecomendada = esRecomendada,
            nodos = caminoNodos,
            distanciaTotalKm = ((distanciaMetros / 1000.0) * 100.0).roundToInt() / 100.0,
            tiempoEstimadoMin = ((distanciaMetros / VELOCIDAD_PEATON_MPS) / 60.0).roundToInt().coerceAtLeast(1),
            puntajeRiesgoPromedio = (riesgoMedio * 100.0).roundToInt() / 100.0,
            advertenciasZonasRiesgo = if (esRecomendada && advertencias.isEmpty()) {
                listOf("Evita la zona de riesgo alto · calles iluminadas")
            } else advertencias,
            calculadaOffline = true
        )
    }

    private fun obtenerGrafoZacatencoPorDefecto(): List<AristaVialOffline> {
        val nEscom = PuntoRuta(19.5045, -99.1469, 1L)
        val nWilfrido = PuntoRuta(19.5028, -99.1450, 2L)
        val nAvIpnIluminada = PuntoRuta(19.5038, -99.1432, 3L)
        val nMetroPoli = PuntoRuta(19.5010, -99.1490, 4L)

        return listOf(
            // Ruta directa corta pero con riesgo alto en la noche (0.82)
            AristaVialOffline(101L, nEscom, nMetroPoli, "Paso directo Salida Metro Politécnico", 1100.0, 0.82, false, "Salida metro Politécnico"),
            // Ruta segura por calles iluminadas (1.5 km total, bajo riesgo)
            AristaVialOffline(102L, nEscom, nAvIpnIluminada, "Av. Juan de Dios Bátiz (Luminarias activas)", 650.0, 0.15, true, null),
            AristaVialOffline(103L, nAvIpnIluminada, nWilfrido, "Av. Wilfrido Massieu", 450.0, 0.18, true, null),
            AristaVialOffline(104L, nWilfrido, nMetroPoli, "Acceso Vigilado Politécnico", 400.0, 0.20, true, null)
        )
    }

    // =========================================================================
    // PARTE B: MODO ACOMPAÑAMIENTO ("CAMINATA SEGURA")
    // =========================================================================

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

        iniciarTemporizadores()
    }

    fun confirmarPresenciaCheckIn() {
        if (!_estadoCaminata.value.activa) return
        _estadoCaminata.value = _estadoCaminata.value.copy(
            segundosRestantesCheckIn = intervaloCheckInActualSeg
        )
    }

    fun evaluarUbicacionEnRuta(latitudActual: Double, longitudActual: Double) {
        val estadoActual = _estadoCaminata.value
        if (!estadoActual.activa || estadoActual.rutaObjetivo.isEmpty()) return

        val puntoActual = PuntoRuta(latitudActual, longitudActual)
        val nodoDestino = estadoActual.rutaObjetivo.last()
        val distanciaAlDestino = calcularDistanciaHaversineMetros(puntoActual, nodoDestino)

        if (distanciaAlDestino <= RADIO_LLEGADA_DESTINO_METROS && estadoActual.rutaObjetivo.size > 1) {
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

        // Mejora geométrica: Distancia mínima punto-a-segmento (no solo a los vértices)
        val distanciaMinima = calcularDistanciaMinimaAPolilineaMetros(puntoActual, estadoActual.rutaObjetivo)
        val excedioTolerancia = distanciaMinima > toleranciaDesviacionMetros

        _estadoCaminata.value = estadoActual.copy(
            desviacionActualMetros = distanciaMinima,
            distanciaAlDestinoMetros = distanciaAlDestino,
            fueraDeRuta = excedioTolerancia
        )

        if (excedioTolerancia) {
            onDesviacionRutaExcedida(distanciaMinima)
        }
    }

    fun finalizarCaminataSegura() {
        jobTemporizador?.cancel()
        _estadoCaminata.value = EstadoCaminataSegura(activa = false)
    }

    private fun iniciarTemporizadores() {
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
                    Log.e(TAG, "¡Tiempo de Check-in VENCIDO! Disparando alerta automática (P4)...")
                    onTiempoCheckInVencido()
                    break
                }
            }
        }
    }

    private fun calcularDistanciaMinimaAPolilineaMetros(punto: PuntoRuta, polilinea: List<PuntoRuta>): Double {
        if (polilinea.size == 1) return calcularDistanciaHaversineMetros(punto, polilinea.first())
        var minDistancia = Double.MAX_VALUE
        for (i in 0 until polilinea.size - 1) {
            val d = distanciaPuntoASegmentoMetros(punto, polilinea[i], polilinea[i + 1])
            if (d < minDistancia) minDistancia = d
        }
        return minDistancia
    }

    private fun distanciaPuntoASegmentoMetros(p: PuntoRuta, a: PuntoRuta, b: PuntoRuta): Double {
        val latMediaRad = Math.toRadians((a.latitud + b.latitud) / 2.0)
        val mPorGradoLat = 111132.92
        val mPorGradoLon = 111412.84 * cos(latMediaRad)

        val px = (p.longitud - a.longitud) * mPorGradoLon
        val py = (p.latitud - a.latitud) * mPorGradoLat
        val bx = (b.longitud - a.longitud) * mPorGradoLon
        val by = (b.latitud - a.latitud) * mPorGradoLat

        val lenSq = bx * bx + by * by
        val t = if (lenSq == 0.0) 0.0 else ((px * bx + py * by) / lenSq).coerceIn(0.0, 1.0)
        val proyX = t * bx
        val proyY = t * by
        return hypot(px - proyX, py - proyY)
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