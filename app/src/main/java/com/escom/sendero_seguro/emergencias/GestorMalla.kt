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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
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
import java.security.SecureRandom
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.*
import kotlin.random.Random

class GestorMalla(private val context: Context? = null) {

    companion object {
        private const val TAG = "MODULO2_MALLA_P5_1"
        private const val MESH_MANUFACTURER_ID = 0xFFFF
        private const val MAGIC_BYTE_1: Byte = 0x53 // 'S'
        private const val MAGIC_BYTE_2: Byte = 0x53 // 'S'
        private const val RADIO_TIERRA_M = 6371000.0
    }

    private val meshScope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val paquetesProcesados = Collections.synchronizedSet(LinkedHashSet<String>())
    private val listaHistorialMalla = Collections.synchronizedList(mutableListOf<PaqueteMalla>())

    var urlServidorLinux: String = "http://10.100.88.182:8080/alerta"
    var retransmitirAlertasOtrosActivo: Boolean = true
    var ahorroBateriaEscaneoActivo: Boolean = true

    private val llaveSimetricaMalla = "SenderoSeguroIPN2026ClaveMalla32".toByteArray(Charsets.UTF_8).copyOf(32)

    private val _flujoPaquetesMalla = MutableSharedFlow<PaqueteMalla>(replay = 1, extraBufferCapacity = 30)
    val flujoPaquetesMalla: SharedFlow<PaqueteMalla> = _flujoPaquetesMalla.asSharedFlow()

    private val _flujoEstadoMalla = MutableSharedFlow<String>(extraBufferCapacity = 40)
    val flujoEstadoMalla: SharedFlow<String> = _flujoEstadoMalla.asSharedFlow()

    private val _estadoRedYMalla = MutableStateFlow(EstadoRedYMalla())
    val estadoRedYMalla: StateFlow<EstadoRedYMalla> = _estadoRedYMalla.asStateFlow()

    private val _alertaModoOperadorAdHoc = MutableStateFlow<AlertaAdHocOperadorVista?>(null)
    val alertaModoOperadorAdHoc: StateFlow<AlertaAdHocOperadorVista?> = _alertaModoOperadorAdHoc.asStateFlow()

    private var escaneando = false
    private var modoScanActual = ScanSettings.SCAN_MODE_LOW_LATENCY
    private var ultimoPaqueteEmitido: PaqueteMalla? = null

    // Callbacks declarados como Any para evitar que el compilador exija API 26 en campos globales
    private var advertisingSetCallbackActivo: Any? = null
    private var legacyAdvertiseCallbackActivo: AdvertiseCallback? = null

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                        escaneando = false
                        advertisingSetCallbackActivo = null
                        legacyAdvertiseCallbackActivo = null
                        _estadoRedYMalla.value = _estadoRedYMalla.value.copy(escaneoBleActivo = false)
                        reportar("⚠️ Bluetooth apagado. Esperando reconexión...")
                    }
                    BluetoothAdapter.STATE_ON -> {
                        mainHandler.postDelayed({
                            escaneando = false
                            asegurarEscaneoMallaActivo()
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
            context?.registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        } catch (_: Exception) {}
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

    fun ajustarEscaneoMallaPorBateria(nivelBateria: Int) {
        if (!ahorroBateriaEscaneoActivo) return
        val nuevoModo = if (nivelBateria > 20) {
            ScanSettings.SCAN_MODE_BALANCED
        } else {
            ScanSettings.SCAN_MODE_LOW_POWER
        }
        val etiquetaModo = if (nivelBateria > 20) {
            "Batería $nivelBateria%: escaneo cada 2 min"
        } else {
            "Bajo 20% ($nivelBateria%): escaneo cada 10 min"
        }
        _estadoRedYMalla.value = _estadoRedYMalla.value.copy(modoEscaneoBateria = etiquetaModo)

        if (nuevoModo != modoScanActual && escaneando) {
            modoScanActual = nuevoModo
            escaneando = false
            asegurarEscaneoMallaActivo()
        }
    }

    fun emitirAlertaEnMalla(alerta: AlertaEmergencia, emisorId: String, ttlInicial: Int = 5): PaqueteMalla {
        val numId = Random.nextInt(1000, 9999)
        val idCorto = "SOS-$numId"

        val jsonCarga = """{"alertaId":"${alerta.idAlerta}","emisorId":"$emisorId","tipo":"${alerta.nombreTipoIncidente}","desc":"${alerta.descripcionRelato}","lat":${alerta.latitudInicial},"lon":${alerta.longitudInicial},"prec":${alerta.precisionMetros}}"""
        val cargaCifrada = cifrarCargaAesGcm(jsonCarga)
        val firma = firmarHmacSha256("$idCorto|$ttlInicial|0|$cargaCifrada")

        val paquete = PaqueteMalla(
            packetId = idCorto,
            alertaId = alerta.idAlerta,
            folioCorto = alerta.folio,
            emisorId = emisorId,
            tipoIncidenteNombre = alerta.nombreTipoIncidente,
            descripcionCorta = alerta.descripcionRelato.ifEmpty { "Alerta SOS activada" },
            latitud = alerta.latitudInicial,
            longitud = alerta.longitudInicial,
            precisionMetros = alerta.precisionMetros,
            nivelBateria = alerta.nivelBateria,
            ttl = ttlInicial,
            saltosRecorridos = 0,
            cargaCifradaBase64 = cargaCifrada,
            firmaHmacHex = firma
        )

        ultimoPaqueteEmitido = paquete
        paquetesProcesados.add(paquete.packetId)
        registrarEnHistorialMalla(paquete)
        _flujoPaquetesMalla.tryEmit(paquete)

        transmitirPorHardwareBLE(paquete)
        return paquete
    }

    fun interceptarYRetransmitir(paqueteEntrante: PaqueteMalla): PaqueteMalla? {
        if (!retransmitirAlertasOtrosActivo) return null
        if (!paquetesProcesados.add(paqueteEntrante.packetId)) return null

        val nuevoTtl = paqueteEntrante.ttl - 1
        val nuevosSaltos = paqueteEntrante.saltosRecorridos + 1

        if (nuevoTtl <= 0) {
            reportar("⏹️ Paquete ${paqueteEntrante.packetId} descartado (TTL=0)")
            return null
        }

        val paqueteRetransmitido = paqueteEntrante.copy(
            ttl = nuevoTtl,
            saltosRecorridos = nuevosSaltos
        )
        ultimoPaqueteEmitido = paqueteRetransmitido
        registrarEnHistorialMalla(paqueteRetransmitido)
        _flujoPaquetesMalla.tryEmit(paqueteRetransmitido)

        reportar("⚡ ¡CAPTADO EN MALLA! ${paqueteRetransmitido.packetId} | Saltos: $nuevosSaltos | TTL: $nuevoTtl")

        transmitirPorHardwareBLE(paqueteRetransmitido)
        enviarAServidorLinux(paqueteRetransmitido)

        return paqueteRetransmitido
    }

    fun mostrarAlertaAdHocModoOperador(
        paquete: PaqueteMalla,
        latitudOperador: Double,
        longitudOperador: Double
    ): AlertaAdHocOperadorVista {
        val distancia = calcularDistanciaMetros(latitudOperador, longitudOperador, paquete.latitud, paquete.longitud)
        val rumboGrados = calcularRumboGrados(latitudOperador, longitudOperador, paquete.latitud, paquete.longitud)
        val cardinal = convertirGradosACardinal(rumboGrados)

        val vista = AlertaAdHocOperadorVista(
            paquete = paquete,
            distanciaMetros = distancia,
            rumboGrados = rumboGrados,
            rumboCardinal = cardinal,
            textoDistanciaYRumbo = "${distancia.roundToInt()} m $cardinal (±${paquete.precisionMetros.roundToInt()} m)",
            acusadaLocalmente = false
        )
        _alertaModoOperadorAdHoc.value = vista
        reportar("🚨 MODO OPERADOR AD-HOC (A-14): ${paquete.folioCorto} a ${vista.textoDistanciaYRumbo}")
        return vista
    }

    fun acusarYAcudirOfflinePorMalla(
        paqueteOriginal: PaqueteMalla,
        unidadOperativaId: String = "P-214"
    ): PaqueteMalla {
        val idAcuse = "ACK-${Random.nextInt(1000, 9999)}"
        val paqueteAcuse = paqueteOriginal.copy(
            packetId = idAcuse,
            ttl = 5,
            saltosRecorridos = 0,
            esAcuseOperador = true,
            unidadAcuseId = unidadOperativaId,
            timestamp = System.currentTimeMillis()
        )

        _alertaModoOperadorAdHoc.value = _alertaModoOperadorAdHoc.value?.copy(acusadaLocalmente = true)
        paquetesProcesados.add(idAcuse)
        registrarEnHistorialMalla(paqueteAcuse)
        _flujoPaquetesMalla.tryEmit(paqueteAcuse)
        transmitirPorHardwareBLE(paqueteAcuse)

        reportar("✅ Acuse offline emitido por malla por unidad $unidadOperativaId (${paqueteAcuse.packetId})")
        return paqueteAcuse
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
                .setScanMode(modoScanActual)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .setReportDelay(0L)
                .build()

            scanner.startScan(listOf(filtroPantallaApagada), ajustes, scanCallback)
            escaneando = true
            _estadoRedYMalla.value = _estadoRedYMalla.value.copy(escaneoBleActivo = true)
            reportar("📡 Receptor BLE ACTIVO")
        } catch (_: SecurityException) {
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                detenerTransmisionOreoPlus(advertiser)
            } else {
                legacyAdvertiseCallbackActivo?.let {
                    try { advertiser.stopAdvertising(it) } catch (_: Exception) {}
                    legacyAdvertiseCallbackActivo = null
                }
            }
        } catch (_: Exception) {}
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun detenerTransmisionOreoPlus(advertiser: BluetoothLeAdvertiser) {
        (advertisingSetCallbackActivo as? AdvertisingSetCallback)?.let {
            try { advertiser.stopAdvertisingSet(it) } catch (_: Exception) {}
            advertisingSetCallbackActivo = null
        }
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
                val saltos = if (bytes.size >= 17) buffer.get().toInt() else (5 - ttl).coerceAtLeast(0)

                val paqueteCapturado = PaqueteMalla(
                    packetId = "SOS-$idNum",
                    emisorId = "NODO_CERCANO",
                    latitud = lat,
                    longitud = lon,
                    nivelBateria = bat,
                    ttl = ttl,
                    saltosRecorridos = saltos
                )
                interceptarYRetransmitir(paqueteCapturado)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            escaneando = false
            _estadoRedYMalla.value = _estadoRedYMalla.value.copy(escaneoBleActivo = false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun transmitirPorHardwareBLE(paquete: PaqueteMalla) {
        val numId = paquete.packetId.replace("SOS-", "").replace("ACK-", "").toIntOrNull()
            ?: (abs(paquete.packetId.hashCode() % 8999) + 1000)

        val buffer = ByteBuffer.allocate(17)
        buffer.put(MAGIC_BYTE_1)
        buffer.put(MAGIC_BYTE_2)
        buffer.putInt(numId)
        buffer.putFloat(paquete.latitud.toFloat())
        buffer.putFloat(paquete.longitud.toFloat())
        buffer.put(paquete.nivelBateria.toByte())
        buffer.put(paquete.ttl.toByte())
        buffer.put(paquete.saltosRecorridos.toByte())
        val payloadBytes = buffer.array()

        try {
            val btManager = context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val advertiser = btManager?.adapter?.bluetoothLeAdvertiser
            if (advertiser == null || btManager.adapter?.isEnabled != true) return

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(MESH_MANUFACTURER_ID, payloadBytes)
                .build()

            // Si es Android 8.0+ (API 26), usar AdvertisingSet avanzado; si es menor, usar Legacy
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                transmitirOreoPlus(advertiser, data, paquete)
            } else {
                emitirModoLegacyRespaldo(advertiser, data, paquete)
            }
        } catch (_: Exception) {}
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("MissingPermission")
    private fun transmitirOreoPlus(advertiser: BluetoothLeAdvertiser, data: AdvertiseData, paquete: PaqueteMalla) {
        (advertisingSetCallbackActivo as? AdvertisingSetCallback)?.let {
            try { advertiser.stopAdvertisingSet(it) } catch (_: Exception) {}
            advertisingSetCallbackActivo = null
        }

        val parameters = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            .setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_MIN)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH) // Soluciona deprecación de TX_POWER_MAX
            .build()

        val setCallback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                if (status != ADVERTISE_SUCCESS) {
                    emitirModoLegacyRespaldo(advertiser, data, paquete)
                }
            }
        }

        advertisingSetCallbackActivo = setCallback
        advertiser.startAdvertisingSet(parameters, data, null, null, null, setCallback)
    }

    @SuppressLint("MissingPermission")
    private fun emitirModoLegacyRespaldo(advertiser: BluetoothLeAdvertiser, data: AdvertiseData, paquete: PaqueteMalla) {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val callback = object : AdvertiseCallback() {}
        legacyAdvertiseCallbackActivo = callback
        advertiser.startAdvertising(settings, data, callback)
    }

    private fun cifrarCargaAesGcm(textoPlano: String): String {
        return try {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(llaveSimetricaMalla, "AES"), GCMParameterSpec(128, iv))
            val cifrado = cipher.doFinal(textoPlano.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + cifrado, Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    private fun firmarHmacSha256(mensaje: String): String {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(llaveSimetricaMalla, "HmacSHA256"))
            val bytes = mac.doFinal(mensaje.toByteArray(Charsets.UTF_8))
            bytes.take(8).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    private fun calcularDistanciaMetros(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + sin(dLon / 2).pow(2.0) * cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2))
        return RADIO_TIERRA_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun calcularRumboGrados(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun convertirGradosACardinal(grados: Double): String {
        val direcciones = listOf(
            "al norte", "al noreste", "al este", "al sureste",
            "al sur", "al suroeste", "al oeste", "al noroeste"
        )
        val indice = (((grados + 22.5) % 360.0) / 45.0).toInt()
        return direcciones[indice.coerceIn(0, 7)]
    }

    fun enviarAServidorLinux(paquete: PaqueteMalla) {
        meshScope.launch {
            val jsonPayload = """
                {
                    "packetId": "${paquete.packetId}",
                    "alertaId": "${paquete.alertaId}",
                    "emisorId": "${paquete.emisorId}",
                    "latitud": ${paquete.latitud},
                    "longitud": ${paquete.longitud},
                    "nivelBateria": ${paquete.nivelBateria},
                    "ttl": ${paquete.ttl},
                    "saltosMalla": ${paquete.saltosRecorridos}
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
                    reportar("💻 ENVIADO A SERVIDOR ($urlServidorLinux) -> HTTP 200")
                }
            } catch (_: Exception) {}
        }
    }

    fun reiniciarEstadoParaNuevaPrueba() {
        detenerSoloTransmisionBLE()
        paquetesProcesados.clear()
        asegurarEscaneoMallaActivo()
    }
}