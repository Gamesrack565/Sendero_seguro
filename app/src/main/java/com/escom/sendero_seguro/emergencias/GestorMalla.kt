package com.escom.sendero_seguro.emergencias

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class GestorMalla {

    companion object {
        private const val TAG = "MODULO2_MALLA_P5_1"
    }

    // Historial de IDs de paquetes ya vistos para evitar tormentas de broadcast
    private val paquetesProcesados = LinkedHashSet<String>()

    // Flujo para observar paquetes emitidos o retransmitidos (útil para UI o pruebas)
    private val _flujoPaquetesMalla = MutableSharedFlow<PaqueteMalla>(replay = 1)
    val flujoPaquetesMalla: SharedFlow<PaqueteMalla> = _flujoPaquetesMalla.asSharedFlow()

    /**
     * P5.1: Crea y emite un paquete de alerta propio hacia dispositivos cercanos
     */
    fun emitirAlertaEnMalla(alerta: AlertaEmergencia, emisorId: String, ttlInicial: Int = 5): PaqueteMalla {
        val paquete = PaqueteMalla(
            packetId = alerta.idAlerta,
            emisorId = emisorId,
            latitud = alerta.latitudInicial,
            longitud = alerta.longitudInicial,
            nivelBateria = alerta.nivelBateria,
            ttl = ttlInicial
        )
        paquetesProcesados.add(paquete.packetId)
        transmitirPorHardwareP2P(paquete)
        _flujoPaquetesMalla.tryEmit(paquete)
        Log.d(TAG, "Paquete de malla EMITIDO -> ID: ${paquete.packetId} | TTL: ${paquete.ttl}")
        return paquete
    }

    /**
     * P5.1: Intercepta un paquete recibido por Bluetooth/WiFi-Direct de otro dispositivo,
     * valida su ID único, descuenta 1 al TTL y lo retransmite si aún tiene saltos disponibles.
     */
    fun interceptarYRetransmitir(paqueteEntrante: PaqueteMalla): PaqueteMalla? {
        // 1. Verificar si ya procesamos este identificador único
        if (paquetesProcesados.contains(paqueteEntrante.packetId)) {
            Log.d(TAG, "Paquete ignorado (ID duplicado): ${paqueteEntrante.packetId}")
            return null
        }

        // 2. Registrar el ID como visto
        paquetesProcesados.add(paqueteEntrante.packetId)

        // 3. Verificar y descontar el contador de saltos (TTL)
        val nuevoTtl = paqueteEntrante.ttl - 1
        if (nuevoTtl <= 0) {
            Log.w(TAG, "Paquete descartado por TTL agotado (TTL=0): ${paqueteEntrante.packetId}")
            return null
        }

        // 4. Reenviar paquete con TTL actualizado
        val paqueteRetransmitido = paqueteEntrante.copy(ttl = nuevoTtl)
        transmitirPorHardwareP2P(paqueteRetransmitido)
        _flujoPaquetesMalla.tryEmit(paqueteRetransmitido)

        Log.i(TAG, "Paquete RETRANSMITIDO en malla -> ID: ${paqueteRetransmitido.packetId} | Nuevo TTL: $nuevoTtl")
        return paqueteRetransmitido
    }

    /**
     * Punto de enlace con Bluetooth Low Energy (BLE) / WiFi-Direct.
     */
    private fun transmitirPorHardwareP2P(paquete: PaqueteMalla) {
        // Serialización compacta lista para enviar por BluetoothGatt / WifiP2pManager
        val payloadBytes = "${paquete.packetId}|${paquete.emisorId}|${paquete.latitud}|${paquete.longitud}|${paquete.nivelBateria}|${paquete.ttl}".toByteArray()
        Log.d(TAG, "Payload P2P listo (${payloadBytes.size} bytes)")
    }

    fun limpiarHistorialMalla() {
        paquetesProcesados.clear()
    }
}