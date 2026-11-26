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
        private const val PERIODO_MUESTREO_US = 5_000
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val detectorCaidas = DetectorCaidas()

    /** Golpe aislado (g) que activa la vista de caída por sí solo (ver DetectorCaidas). Por defecto 3.0 g. */
    var umbralImpactoG: Float
        get() = detectorCaidas.umbralGolpeG
        set(value) { detectorCaidas.umbralGolpeG = value.coerceIn(2.0f, 6.0f) }

    // Las lecturas a 200 Hz se procesan fuera del hilo principal; el aviso de caída sí llega en el principal.
    private val hiloSensor = android.os.HandlerThread("SenderoAcelerometro").apply { start() }
    private val manejadorSensor = android.os.Handler(hiloSensor.looper)
    private val principal = android.os.Handler(Looper.getMainLooper())
    private var picoActualG = 0f

    private var ultimaPublicacionAceleracionNs = 0L
    private var vigilandoImpactos = false

    private var ultimaUbicacion: Location? = null
    private var ultimaAceleracionG: Float = 1.0f
    private var monitoreandoTelemetria = false

    private val _flujoTelemetria = MutableStateFlow<DatosTelemetria?>(null)
    val flujoTelemetria: StateFlow<DatosTelemetria?> = _flujoTelemetria.asStateFlow()

    /**
     * Acelerómetro a 200 Hz (cada 5 ms, el máximo sin permiso especial): un golpe dura 5-15 ms y a 50 Hz
     * casi siempre caía entre dos lecturas. Si existe la versión «wake-up» del sensor se usa con entrega
     * en lote, para que siga detectando con la pantalla apagada sin un wake lock permanente.
     */
    fun iniciarVigilanciaImpactos() {
        if (vigilandoImpactos) return
        val acelerometro = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return
        vigilandoImpactos = if (acelerometro.isWakeUpSensor) {
            sensorManager.registerListener(this, acelerometro, PERIODO_MUESTREO_US, 2_000_000, manejadorSensor)
        } else {
            sensorManager.registerListener(this, acelerometro, PERIODO_MUESTREO_US, manejadorSensor)
        }
    }

    fun detenerVigilanciaImpactos() {
        if (!monitoreandoTelemetria) {
            sensorManager.unregisterListener(this)
            vigilandoImpactos = false
            detectorCaidas.reiniciar()
        }
    }

    /**
     * E3 P6: Captura y transmite coordenadas GNSS cada 10 segundos (por defecto en E3)
     * mientras la alerta permanezca activa.
     */
    @SuppressLint("MissingPermission")
    fun iniciarTelemetriaTiempoReal(intervaloMs: Long = 10_000L) {
        monitoreandoTelemetria = true
        iniciarVigilanciaImpactos()

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, intervaloMs, 1f, this, Looper.getMainLooper()
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, intervaloMs, 1f, this, Looper.getMainLooper()
                )
            }

            val locGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val locRed = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ultimaUbicacion = locGps ?: locRed

            emitirUbicacionGNSSPeriodica()
        } catch (e: SecurityException) {
            Log.e(TAG, "Faltan permisos de ubicación: ${e.message}")
            emitirUbicacionGNSSPeriodica()
        }
    }

    fun detenerTelemetriaTiempoReal() {
        monitoreandoTelemetria = false
        locationManager.removeUpdates(this)
    }

    fun obtenerNivelBateria(): Int {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)
        val nivel = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val escala = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (nivel >= 0 && escala > 0) ((nivel * 100) / escala) else 100
    }

    fun simularUbicacionParaPruebas(latitud: Double, longitud: Double, velocidad: Float = 1.2f, rumbo: Float = 90f) {
        val locSimulada = Location(LocationManager.GPS_PROVIDER).apply {
            this.latitude = latitud
            this.longitude = longitud
            this.accuracy = 8.0f
            this.speed = velocidad
            this.bearing = rumbo
            this.time = System.currentTimeMillis()
        }
        onLocationChanged(locSimulada)
    }

    override fun onLocationChanged(location: Location) {
        ultimaUbicacion = location
        if (monitoreandoTelemetria) {
            emitirUbicacionGNSSPeriodica()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val magnitudG = sqrt((x * x + y * y + z * z).toDouble()).toFloat() / SensorManager.GRAVITY_EARTH
            ultimaAceleracionG = magnitudG

            // Pico de cada sacudida en el log, para calibrar el umbral en un teléfono real:
            //   adb logcat -s MODULO2_TELEMETRIA_P6
            if (magnitudG >= 1.8f) {
                picoActualG = maxOf(picoActualG, magnitudG)
            } else if (picoActualG > 0f) {
                Log.d(TAG, "Pico de ${"%.2f".format(picoActualG)} g (golpe ≥ ${"%.1f".format(umbralImpactoG)} g)")
                picoActualG = 0f
            }

            // A 200 Hz no se publica cada lectura: recompondría las pantallas que observan la telemetría.
            if (event.timestamp - ultimaPublicacionAceleracionNs >= 500_000_000L) {
                ultimaPublicacionAceleracionNs = event.timestamp
                _flujoTelemetria.value = _flujoTelemetria.value?.copy(aceleracionG = magnitudG)
            }

            if (detectorCaidas.procesar(magnitudG, event.timestamp)) {
                Log.w(TAG, "Golpe o caída detectada (${"%.2f".format(magnitudG)} g)")
                principal.post { onCaidaDetectada() }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    /**
     * E3 P6: Nombre oficial del Diseño de Procesos para emitir lecturas GNSS.
     */
    fun emitirUbicacionGNSSPeriodica(): DatosTelemetria {
        return emitirInstantaneaTelemetria()
    }

    fun emitirInstantaneaTelemetria(): DatosTelemetria {
        val loc = ultimaUbicacion
        val fuenteDetectada = when (loc?.provider) {
            LocationManager.GPS_PROVIDER -> FuenteUbicacion.GNSS
            LocationManager.NETWORK_PROVIDER -> FuenteUbicacion.WIFI
            null -> FuenteUbicacion.ULTIMA_CONOCIDA
            else -> FuenteUbicacion.CELULAR
        }

        val datos = DatosTelemetria(
            latitud = loc?.latitude ?: 19.5045,
            longitud = loc?.longitude ?: -99.1469,
            precision = loc?.accuracy ?: 8.0f,
            velocidadMps = loc?.speed ?: 0.0f,
            rumboGrados = loc?.bearing ?: 0.0f,
            aceleracionG = ultimaAceleracionG,
            nivelBateria = obtenerNivelBateria(),
            fuente = fuenteDetectada
        )
        _flujoTelemetria.value = datos
        return datos
    }
}