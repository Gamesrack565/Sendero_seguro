package com.escom.sendero_seguro.emergencias

import java.util.UUID

// P4: Tipos de detonadores de emergencia
enum class TipoDetonador {
    BOTON_SOS,
    COMANDO_VOZ,
    CAIDA_IMPACTO,
    VENCIMIENTO_CAMINATA_SEGURA
}

// P4: Estado e información inicial de la Alerta de Emergencia
data class AlertaEmergencia(
    val idAlerta: String = UUID.randomUUID().toString(),
    val detonador: TipoDetonador,
    val latitudInicial: Double,
    val longitudInicial: Double,
    val precisionMetros: Float,
    val nivelBateria: Int,
    val timestampInicio: Long = System.currentTimeMillis(),
    val activa: Boolean = true
)

// P5.1: Paquete para Retransmisión en Malla (Bluetooth / WiFi-Direct)
data class PaqueteMalla(
    val packetId: String = UUID.randomUUID().toString(),
    val emisorId: String,
    val latitud: Double,
    val longitud: Double,
    val nivelBateria: Int,
    val ttl: Int = 5,
    val timestamp: Long = System.currentTimeMillis()
)

// P6: Flujo continuo de Monitoreo y Telemetría en Tiempo Real
data class DatosTelemetria(
    val latitud: Double,
    val longitud: Double,
    val precision: Float,
    val velocidadMps: Float,
    val rumboGrados: Float,
    val aceleracionG: Float,
    val nivelBateria: Int,
    val timestamp: Long = System.currentTimeMillis()
)

// P7: Coordenada simple para trazar la ruta de Caminata Segura
data class PuntoRuta(
    val latitud: Double,
    val longitud: Double
)

// P7: Estado del Modo Acompañamiento ("Caminata Segura")
data class EstadoCaminataSegura(
    val activa: Boolean = false,
    val rutaObjetivo: List<PuntoRuta> = emptyList(),
    val tiempoEstimadoLlegadaMs: Long = 0L,
    val segundosRestantesCheckIn: Long = 0L,
    val desviacionActualMetros: Double = 0.0,
    val fueraDeRuta: Boolean = false
)