package com.escom.sendero_seguro.emergencias

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

class GestorTelemetria(
    private val context: Context,
    private val onCaidaDetectada: () -> Unit
) : SensorEventListener, LocationListener {

    companion object {
        private const val TAG = "MODULO2_TELEMETRIA_P6"
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // Umbral de fuerza G para caída/impacto (configurable por P3, por defecto 2.8 G)
    var umbralImpactoG: Float = 2.8f

    private var ultimaUbicacion: Location? = null
    private var ultimaAceleracionG: Float = 1.0f
    private var monitoreandoTelemetria = false

    private val _flujoTelemetria = MutableStateFlow<DatosTelemetria?>(null)
    val flujoTelemetria: StateFlow<DatosTelemetria?> = _flujoTelemetria.asStateFlow()

    /**
     * P4: Activa el acelerómetro en todo momento para detectar caídas o impactos bruscos.
     */
    fun iniciarVigilanciaImpactos() {
        val acelerometro = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        acelerometro?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d(TAG, "Vigilancia de impactos/caídas activada (Umbral: ${umbralImpactoG}G)")
        }
    }

    fun detenerVigilanciaImpactos() {
        if (!monitoreandoTelemetria) {
            sensorManager.unregisterListener(this)
        }
    }

    /**
     * P6: Inicia la lectura periódica de GPS, acelerómetro y batería durante una alerta activa.
     */
    @SuppressLint("MissingPermission")
    fun iniciarTelemetriaTiempoReal(intervaloMs: Long = 2000L) {
        monitoreandoTelemetria = true
        iniciarVigilanciaImpactos()

        try {
            // 1. Escuchar GPS satelital (funciona sin internet)
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, intervaloMs, 1f, this, Looper.getMainLooper()
                )
            }
            // 2. Escuchar proveedor de Red/Fused (ubica rápido bajo techo)
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, intervaloMs, 1f, this, Looper.getMainLooper()
                )
            }

            // Tomar la última coordenada real guardada por tu teléfono
            val locGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val locRed = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ultimaUbicacion = locGps ?: locRed

            emitirInstantaneaTelemetria()
            Log.i(TAG, "Monitoreo de telemetría en tiempo real INICIADO (P6)")
        } catch (e: SecurityException) {
            Log.e(TAG, "Faltan permisos de ubicación: ${e.message}")
            emitirInstantaneaTelemetria()
        }
    }

    fun detenerTelemetriaTiempoReal() {
        monitoreandoTelemetria = false
        locationManager.removeUpdates(this)
        // NO apagamos el acelerómetro aquí para que P4 siga vigilando caídas/impactos
        Log.i(TAG, "Monitoreo GPS P6 detenido (Vigilancia de caídas P4 sigue activa)")
    }



    /**
     * P4 y P6: Obtiene el porcentaje actual de batería del dispositivo (0 a 100%).
     */
    fun obtenerNivelBateria(): Int {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)
        val nivel = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val escala = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (nivel >= 0 && escala > 0) ((nivel * 100) / escala) else 100
    }

    /**
     * Permite inyectar coordenadas manualmente para tus pruebas sin mover el teléfono.
     */
    fun simularUbicacionParaPruebas(latitud: Double, longitud: Double, velocidad: Float = 1.2f, rumbo: Float = 90f) {
        val locSimulada = Location("SIMULADOR_MODULO_2").apply {
            this.latitude = latitud
            this.longitude = longitud
            this.accuracy = 5.0f
            this.speed = velocidad
            this.bearing = rumbo
            this.time = System.currentTimeMillis()
        }
        onLocationChanged(locSimulada)
    }

    override fun onLocationChanged(location: Location) {
        ultimaUbicacion = location
        if (monitoreandoTelemetria) {
            emitirInstantaneaTelemetria()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val magnitudG = sqrt((x * x + y * y + z * z).toDouble()).toFloat() / SensorManager.GRAVITY_EARTH
            ultimaAceleracionG = magnitudG

            // Actualizar el valor de Fuerza G en vivo en la pantalla
            _flujoTelemetria.value = _flujoTelemetria.value?.copy(aceleracionG = magnitudG)

            if (magnitudG >= umbralImpactoG) {
                Log.w(TAG, "¡Pico de fuerza G detectado! (${magnitudG}G >= ${umbralImpactoG}G) -> Disparando P4")
                onCaidaDetectada()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    fun emitirInstantaneaTelemetria(): DatosTelemetria {
        val loc = ultimaUbicacion
        // Coordenadas por defecto (ESCOM IPN) si el emulador aún no tiene fix GPS
        val datos = DatosTelemetria(
            latitud = loc?.latitude ?: 19.5045,
            longitud = loc?.longitude ?: -99.1469,
            precision = loc?.accuracy ?: 10.0f,
            velocidadMps = loc?.speed ?: 0.0f,
            rumboGrados = loc?.bearing ?: 0.0f,
            aceleracionG = ultimaAceleracionG,
            nivelBateria = obtenerNivelBateria()
        )
        _flujoTelemetria.value = datos
        Log.d(TAG, "Telemetría P6 -> Lat:${datos.latitud}, Lon:${datos.longitud}, Vel:${datos.velocidadMps}m/s, Bat:${datos.nivelBateria}%")
        return datos
    }
}