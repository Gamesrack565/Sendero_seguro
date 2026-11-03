package com.escom.sendero_seguro.emergencias

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.Collections
import kotlin.math.abs
import kotlin.random.Random

class GestorMalla(private val context: Context? = null) {

    companion object {
        private const val TAG = "MODULO2_MALLA_P5_1"
        private const val MESH_MANUFACTURER_ID = 0xFFFF
        private const val MAGIC_BYTE_1: Byte = 0x53
        private const val MAGIC_BYTE_2: Byte = 0x53
    }

    private val meshScope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val paquetesProcesados = Collections.synchronizedSet(LinkedHashSet<String>())
    private val listaHistorialMalla = Collections.synchronizedList(mutableListOf<PaqueteMalla>())

    var urlServidorLinux: String = "http://10.100.88.182:8080/alerta"

    private val _flujoPaquetesMalla = MutableSharedFlow<PaqueteMalla>(replay = 1, extraBufferCapacity = 30)
    val flujoPaquetesMalla: SharedFlow<PaqueteMalla> = _flujoPaquetesMalla.asSharedFlow()

    private val _flujoEstadoMalla = MutableSharedFlow<String>(extraBufferCapacity = 40)
    val flujoEstadoMalla: SharedFlow<String> = _flujoEstadoMalla.asSharedFlow()

    private val _estadoRedYMalla = MutableStateFlow(EstadoRedYMalla())
    val estadoRedYMalla: StateFlow<EstadoRedYMalla> = _estadoRedYMalla.asStateFlow()

    private var escaneando = false
    private var ultimoPaqueteEmitido: PaqueteMalla? = null
    private var advertisingSetCallbackActivo: AdvertisingSetCallback? = null
    private var legacyAdvertiseCallbackActivo: AdvertiseCallback? = null

    /**
     * Receptor del sistema que detecta cuando el usuario apaga y vuelve a prender el Bluetooth
     * para reconectar el escáner y el transmisor automáticamente sin reiniciar la app.
     */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val estado = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (estado) {
                    BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                        // Al apagarse el Bluetooth, Android mata el escáner internamente
                        escaneando = false
                        advertisingSetCallbackActivo = null
                        legacyAdvertiseCallbackActivo = null
                        _estadoRedYMalla.value = _estadoRedYMalla.value.copy(escaneoBleActivo = false)
                        reportar("⚠️ Bluetooth apagado. Esperando a que se vuelva a encender...")
                    }
                    BluetoothAdapter.STATE_ON -> {
                        reportar("🔄 Bluetooth re-encendido: Reconectando malla BLE automáticamente...")
                        // Pequeña pausa de 600ms para que el stack BLE del chip termine de inicializar
                        mainHandler.postDelayed({
                            escaneando = false
                            asegurarEscaneoMallaActivo()
                            // Si había una alerta activa emitiéndose, la reanudamos
                            ultimoPaqueteEmitido?.let { transmitirPorHardwareBLE(it) }
                        }, 600L)
                    }
                }
            }
        }
    }

    init {
        iniciarMonitoreoConectividad()
        registrarMonitorEstadoBluetooth()
    }

    private fun reportar(msg: String) {
        Log.i(TAG, msg)
        _flujoEstadoMalla.tryEmit(msg)
    }

    private fun registrarMonitorEstadoBluetooth() {
        try {
            val filtroBt = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            context?.registerReceiver(bluetoothStateReceiver, filtroBt)
        } catch (e: Exception) {
            Log.e(TAG, "Error registrando Bluetooth receiver: ${e.message}")
        }
    }

    private fun iniciarMonitoreoConectividad() {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        _estadoRedYMalla.value = _estadoRedYMalla.value.copy(conectadoAInternet = hayConexionAInternet())

        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _estadoRedYMalla.value = _estadoRedYMalla.value.copy(conectadoAInternet = true)
                    reportar("🌐 Red disponible (Modo Nodo Puente activo)")
                }

                override fun onLost(network: Network) {
                    _estadoRedYMalla.value = _estadoRedYMalla.value.copy(conectadoAInternet = false)
                    reportar("📴 Sin red -> Priorizando Retransmisión en Malla P5.1")
                }
            })
        } catch (_: Exception) {}
    }

    fun hayConexionAInternet(): Boolean {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val redActiva = cm.activeNetwork ?: return false
        val capacidades = cm.getNetworkCapabilities(redActiva) ?: return false
        return capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun emitirAlertaEnMalla(alerta: AlertaEmergencia, emisorId: String, ttlInicial: Int = 5): PaqueteMalla {
        val numId = Random.nextInt(1000, 9999)
        val idCorto = "SOS-$numId"

        val paquete = PaqueteMalla(
            packetId = idCorto,
            emisorId = emisorId,
            latitud = alerta.latitudInicial,
            longitud = alerta.longitudInicial,
            nivelBateria = alerta.nivelBateria,
            ttl = ttlInicial
        )

        ultimoPaqueteEmitido = paquete
        paquetesProcesados.add(paquete.packetId)
        registrarEnHistorialMalla(paquete)
        _flujoPaquetesMalla.tryEmit(paquete)

        transmitirPorHardwareBLE(paquete)
        return paquete
    }

    fun interceptarYRetransmitir(paqueteEntrante: PaqueteMalla): PaqueteMalla? {
        if (!paquetesProcesados.add(paqueteEntrante.packetId)) {
            return null
        }

        val nuevoTtl = paqueteEntrante.ttl - 1
        if (nuevoTtl <= 0) {
            reportar("⏹️ Paquete ${paqueteEntrante.packetId} descartado (TTL=0)")
            return null
        }

        val paqueteRetransmitido = paqueteEntrante.copy(ttl = nuevoTtl)
        ultimoPaqueteEmitido = paqueteRetransmitido
        registrarEnHistorialMalla(paqueteRetransmitido)
        _flujoPaquetesMalla.tryEmit(paqueteRetransmitido)

        reportar("⚡ ¡CAPTADO AL INSTANTE! ${paqueteRetransmitido.packetId} | TTL: ${paqueteEntrante.ttl} -> $nuevoTtl")

        transmitirPorHardwareBLE(paqueteRetransmitido)
        enviarAServidorLinux(paqueteRetransmitido)

        return paqueteRetransmitido
    }

    private fun registrarEnHistorialMalla(paquete: PaqueteMalla) {
        listaHistorialMalla.add(0, paquete)
        _estadoRedYMalla.value = _estadoRedYMalla.value.copy(
            cantidadPaquetesRetransmitidos = listaHistorialMalla.size,
            historialRecienteMalla = listaHistorialMalla.take(15)
        )
    }

    @SuppressLint("MissingPermission")
    fun asegurarEscaneoMallaActivo() {
        try {
            val btManager = context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = btManager?.adapter
            if (adapter == null || !adapter.isEnabled) {
                escaneando = false
                _estadoRedYMalla.value = _estadoRedYMalla.value.copy(escaneoBleActivo = false)
                reportar("⚠️ Bluetooth apagado. Enciéndelo para conectar la malla.")
                return
            }

            if (escaneando) return

            val scanner = adapter.bluetoothLeScanner ?: return

            val filtroPantallaApagada = ScanFilter.Builder()
                .setManufacturerData(
                    MESH_MANUFACTURER_ID,
                    byteArrayOf(MAGIC_BYTE_1, MAGIC_BYTE_2),
                    byteArrayOf(0xFF.toByte(), 0xFF.toByte())
                )
                .build()

            val ajustes = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .setReportDelay(0L)
                .build()

            scanner.startScan(listOf(filtroPantallaApagada), ajustes, scanCallback)
            escaneando = true
            _estadoRedYMalla.value = _estadoRedYMalla.value.copy(escaneoBleActivo = true)
            reportar("📡 Receptor BLE ACTIVO (Auto-reconexión habilitada)")
        } catch (e: SecurityException) {
            reportar("❌ Falta permiso Bluetooth Scan.")
        } catch (e: Exception) {
            escaneando = false
            reportar("❌ Error al iniciar escáner BLE: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun detenerSoloTransmisionBLE() {
        ultimoPaqueteEmitido = null
        try {
            val btManager = context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val advertiser = btManager?.adapter?.bluetoothLeAdvertiser ?: return

            advertisingSetCallbackActivo?.let {
                try { advertiser.stopAdvertisingSet(it) } catch (_: Exception) {}
                advertisingSetCallbackActivo = null
            }
            legacyAdvertiseCallbackActivo?.let {
                try { advertiser.stopAdvertising(it) } catch (_: Exception) {}
                legacyAdvertiseCallbackActivo = null
            }
        } catch (_: Exception) {}
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val bytes = result?.scanRecord?.getManufacturerSpecificData(MESH_MANUFACTURER_ID) ?: return

            if (bytes.size >= 16 && bytes[0] == MAGIC_BYTE_1 && bytes[1] == MAGIC_BYTE_2) {
                val buffer = ByteBuffer.wrap(bytes)
                buffer.get()
                buffer.get()
                val idNum = buffer.int
                val lat = buffer.float.toDouble()
                val lon = buffer.float.toDouble()
                val bat = buffer.get().toInt()
                val ttl = buffer.get().toInt()

                val paqueteCapturado = PaqueteMalla(
                    packetId = "SOS-$idNum",
                    emisorId = "NODO_CERCANO",
                    latitud = lat,
                    longitud = lon,
                    nivelBateria = bat,
                    ttl = ttl
                )
                interceptarYRetransmitir(paqueteCapturado)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            escaneando = false
            _estadoRedYMalla.value = _estadoRedYMalla.value.copy(escaneoBleActivo = false)
            reportar("⚠️ Escáner BLE reportó código $errorCode. Reintentando...")
        }
    }

    @SuppressLint("MissingPermission")
    private fun transmitirPorHardwareBLE(paquete: PaqueteMalla) {
        val numId = paquete.packetId.replace("SOS-", "").toIntOrNull()
            ?: (abs(paquete.packetId.hashCode() % 8999) + 1000)

        val buffer = ByteBuffer.allocate(16)
        buffer.put(MAGIC_BYTE_1)
        buffer.put(MAGIC_BYTE_2)
        buffer.putInt(numId)
        buffer.putFloat(paquete.latitud.toFloat())
        buffer.putFloat(paquete.longitud.toFloat())
        buffer.put(paquete.nivelBateria.toByte())
        buffer.put(paquete.ttl.toByte())
        val payloadBytes = buffer.array()

        try {
            val btManager = context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val advertiser = btManager?.adapter?.bluetoothLeAdvertiser
            if (advertiser == null || btManager.adapter?.isEnabled != true) {
                reportar("⚠️ Bluetooth apagado.")
                return
            }

            advertisingSetCallbackActivo?.let {
                try { advertiser.stopAdvertisingSet(it) } catch (_: Exception) {}
                advertisingSetCallbackActivo = null
            }
            legacyAdvertiseCallbackActivo?.let {
                try { advertiser.stopAdvertising(it) } catch (_: Exception) {}
                legacyAdvertiseCallbackActivo = null
            }

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(MESH_MANUFACTURER_ID, payloadBytes)
                .build()

            val parameters = AdvertisingSetParameters.Builder()
                .setLegacyMode(true)
                .setConnectable(true)
                .setScannable(true)
                .setInterval(AdvertisingSetParameters.INTERVAL_MIN)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MAX)
                .build()

            val setCallback = object : AdvertisingSetCallback() {
                override fun onAdvertisingSetStarted(
                    advertisingSet: AdvertisingSet?,
                    txPower: Int,
                    status: Int
                ) {
                    if (status == ADVERTISE_SUCCESS) {
                        reportar("🔊 Ráfaga BLE activa (10 pkt/seg): ${paquete.packetId} | TTL=${paquete.ttl}")
                    } else {
                        emitirModoLegacyRespaldo(advertiser, data, paquete)
                    }
                }
            }

            advertisingSetCallbackActivo = setCallback
            advertiser.startAdvertisingSet(parameters, data, null, null, null, setCallback)

        } catch (e: Exception) {
            reportar("❌ Error al transmitir BLE: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun emitirModoLegacyRespaldo(
        advertiser: BluetoothLeAdvertiser,
        data: AdvertiseData,
        paquete: PaqueteMalla
    ) {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                reportar("🔊 Emitiendo BLE: ${paquete.packetId} (TTL=${paquete.ttl})")
            }
        }
        legacyAdvertiseCallbackActivo = callback
        advertiser.startAdvertising(settings, data, callback)
    }

    fun enviarAServidorLinux(paquete: PaqueteMalla) {
        meshScope.launch {
            val jsonPayload = """
                {
                    "packetId": "${paquete.packetId}",
                    "emisorId": "${paquete.emisorId}",
                    "latitud": ${paquete.latitud},
                    "longitud": ${paquete.longitud},
                    "nivelBateria": ${paquete.nivelBateria},
                    "ttl": ${paquete.ttl}
                }
            """.trimIndent()

            try {
                val conn = (URL(urlServidorLinux).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    connectTimeout = 2500
                    readTimeout = 2500
                    doOutput = true
                }
                OutputStreamWriter(conn.outputStream).use { it.write(jsonPayload) }
                val codigo = conn.responseCode
                conn.disconnect()
                if (codigo == 200) {
                    reportar("💻 ENVIADO A LINUX ($urlServidorLinux) -> HTTP 200")
                }
            } catch (_: Exception) {}
        }
    }

    fun reiniciarEstadoParaNuevaPrueba() {
        detenerSoloTransmisionBLE()
        paquetesProcesados.clear()
        asegurarEscaneoMallaActivo()
        reportar("🔄 Malla lista al instante (Escáner activo)")
    }
}