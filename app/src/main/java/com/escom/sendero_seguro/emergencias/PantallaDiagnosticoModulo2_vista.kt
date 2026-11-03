package com.escom.sendero_seguro

import android.Manifest
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.escom.sendero_seguro.emergencias.*
import com.escom.sendero_seguro.ui.theme.Sendero_seguroTheme
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PantallaDiagnosticoModulo2_vista : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(
            applicationContext,
            PreferenceManager.getDefaultSharedPreferences(applicationContext)
        )
        Configuration.getInstance().userAgentValue = packageName

        enableEdgeToEdge()
        val modulo2 = ModuloEmergencias(this)

        setContent {
            Sendero_seguroTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PantallaDiagnosticoModulo2(
                        modulo = modulo2,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun PantallaDiagnosticoModulo2(modulo: ModuloEmergencias, modifier: Modifier = Modifier) {
    val alerta by modulo.alertaActiva.collectAsState()
    val telemetria by modulo.flujoTelemetria.collectAsState()
    val caminata by modulo.estadoCaminata.collectAsState()

    var ipLinux by remember { mutableStateOf("10.100.88.182") }
    var ultimaAlertaMallaRecibida by remember { mutableStateOf<PaqueteMalla?>(null) }

    val bitacora = remember { mutableStateListOf<String>() }
    fun registrarEvento(proceso: String, mensaje: String) {
        val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        bitacora.add(0, "[$hora] [$proceso] $mensaje")
    }

    LaunchedEffect(Unit) {
        modulo.gestorMalla.urlServidorLinux = "http://$ipLinux:8080/alerta"
    }

    LaunchedEffect(Unit) {
        modulo.gestorVozPanico.flujoEventosVoz.collect { msg ->
            registrarEvento("P4 VOZ", msg)
        }
    }

    LaunchedEffect(Unit) {
        modulo.flujoPaquetesMalla.collect { pkt ->
            if (pkt.emisorId == "NODO_CERCANO") {
                ultimaAlertaMallaRecibida = pkt
            }
        }
    }

    LaunchedEffect(Unit) {
        modulo.gestorMalla.flujoEstadoMalla.collect { msg ->
            registrarEvento("MALLA P5.1", msg)
        }
    }

    LaunchedEffect(alerta) {
        alerta?.let {
            if (it.activa) {
                registrarEvento("P4 ALERTA", "¡SOS ACTIVO! (${it.detonador}) | Bat: ${it.nivelBateria}%")
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permisos ->
        val gpsOk = permisos[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val btOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permisos[Manifest.permission.BLUETOOTH_SCAN] == true &&
                    permisos[Manifest.permission.BLUETOOTH_ADVERTISE] == true
        } else true

        if (gpsOk) {
            // Iniciar servicio en primer plano (pantalla apagada) + GPS
            modulo.iniciarProteccionSegundoPlano()
            modulo.gestorTelemetria.iniciarTelemetriaTiempoReal(2000L)
            registrarEvento("SERVICIO", "Protección con pantalla apagada ACTIVA")
        } else {
            registrarEvento("PERMISOS", "⚠️ Activa la Ubicación para usar GPS y escaneo BLE")
        }

        if (btOk) {
            modulo.gestorMalla.asegurarEscaneoMallaActivo()
        }else {
            registrarEvento("PERMISOS", "❌ Permite 'Dispositivos cercanos' en Ajustes de la App")
        }
    }

    LaunchedEffect(Unit) {
        val listaPermisos = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listaPermisos.add(Manifest.permission.BLUETOOTH_SCAN)
            listaPermisos.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            listaPermisos.add(Manifest.permission.BLUETOOTH_CONNECT)
            listaPermisos.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listaPermisos.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(listaPermisos.toTypedArray())
    }

    val lat = telemetria?.latitud ?: 19.5045
    val lon = telemetria?.longitud ?: -99.1469

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Diagnóstico en Vivo - Módulo 2", fontSize = 17.sp, fontWeight = FontWeight.Bold)

        // 1. MAPA (Ubicación propia, ruta P7 y alertas recibidas por Malla P5.1)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(175.dp)
                .clip(RoundedCornerShape(12.dp))
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        controller.setZoom(18.0)
                        controller.setCenter(GeoPoint(lat, lon))
                    }
                },
                update = { mapView ->
                    val puntoActual = GeoPoint(lat, lon)
                    mapView.controller.animateTo(puntoActual)
                    mapView.overlays.clear()

                    if (caminata.rutaObjetivo.isNotEmpty()) {
                        val lineaRuta = Polyline().apply {
                            setPoints(caminata.rutaObjetivo.map { GeoPoint(it.latitud, it.longitud) })
                            outlinePaint.color = if (caminata.fueraDeRuta) AndroidColor.RED else AndroidColor.rgb(0, 180, 0)
                            outlinePaint.strokeWidth = 10f
                        }
                        mapView.overlays.add(lineaRuta)
                    }

                    val marcadorPropio = Marker(mapView).apply {
                        position = puntoActual
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = if (alerta?.activa == true) "🚨 TU ALERTA ACTIVA" else "📍 Tu ubicación"
                    }
                    mapView.overlays.add(marcadorPropio)

                    ultimaAlertaMallaRecibida?.let { alertaVecino ->
                        val marcadorVecino = Marker(mapView).apply {
                            position = GeoPoint(alertaVecino.latitud, alertaVecino.longitud)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_TOP)
                            title = "📲 ALERTA MALLA: ${alertaVecino.packetId} (TTL: ${alertaVecino.ttl})"
                        }
                        mapView.overlays.add(marcadorVecino)
                    }

                    mapView.invalidate()
                }
            )
        }

        // 2. TARJETA DE SENSORES Y TELEMETRÍA LOCAL (P4, P6 y P7)
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (alerta?.activa == true) Color(0xFFFFEBEE) else Color(0xFFE8F5E9)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = if (alerta?.activa == true) "🚨 P4: ALERTA PROPIA ACTIVA (${alerta?.detonador})" else "✅ P4: En espera (Funciona con pantalla apagada)",
                    fontWeight = FontWeight.Bold,
                    color = if (alerta?.activa == true) Color.Red else Color(0xFF1B5E20),
                    fontSize = 12.sp
                )
                Text(
                    text = "P6 GPS: ${"%.5f".format(lat)}, ${"%.5f".format(lon)} | Fuerza G: ${"%.2f".format(telemetria?.aceleracionG ?: 1f)}G | Bat: ${telemetria?.nivelBateria ?: 100}%",
                    fontSize = 11.sp
                )
                if (caminata.activa) {
                    Text(
                        text = "P7 Caminata: Check-in en ${caminata.segundosRestantesCheckIn}s | Desvío: ${caminata.desviacionActualMetros.toInt()}m " +
                                if (caminata.fueraDeRuta) "⚠️ FUERA DE RUTA" else "✓ En ruta",
                        fontSize = 11.sp,
                        color = if (caminata.fueraDeRuta) Color.Red else Color.Blue,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 3. TARJETA VISUAL DE RECEPCIÓN BLUETOOTH MALLA (P5.1)
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (ultimaAlertaMallaRecibida != null) Color(0xFFF3E5F5) else Color(0xFFF5F5F5)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                val pkt = ultimaAlertaMallaRecibida
                if (pkt != null) {
                    Text(
                        text = "📲 P5.1 MALLA RECIBIDA POR BLUETOOTH: ${pkt.packetId}",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF6A1B9A),
                        fontSize = 12.sp
                    )
                    Text(
                        text = "Coordenadas compañero: ${"%.5f".format(pkt.latitud)}, ${"%.5f".format(pkt.longitud)} | Bat: ${pkt.nivelBateria}% | Nuevo TTL: ${pkt.ttl}",
                        fontSize = 11.sp,
                        color = Color(0xFF4A148C)
                    )
                } else {
                    Text(
                        text = "📡 P5.1 Malla Bluetooth: Escuchando alertas (soporta pantalla apagada)...",
                        fontSize = 11.sp,
                        color = Color.DarkGray
                    )
                }
            }
        }

        // 4. CONFIGURACIÓN DEL SERVIDOR DE PRUEBA EN LINUX (WiFi)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = ipLinux,
                onValueChange = {
                    ipLinux = it
                    modulo.gestorMalla.urlServidorLinux = "http://$it:8080/alerta"
                },
                label = { Text("IP Consola Linux (WiFi)", fontSize = 10.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedButton(
                onClick = {
                    modulo.gestorMalla.urlServidorLinux = "http://$ipLinux:8080/alerta"
                    modulo.gestorMalla.enviarAServidorLinux(
                        PaqueteMalla(
                            packetId = "TEST-${(100..999).random()}",
                            emisorId = "PRUEBA_WIFI",
                            latitud = lat,
                            longitud = lon,
                            nivelBateria = telemetria?.nivelBateria ?: 80,
                            ttl = 4
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Test Consola Linux", fontSize = 11.sp)
            }
        }

        // 5. BOTONES DE EMERGENCIA Y CAMINATA SEGURA
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    // Limpia la alerta anterior sin apagar el escáner y lanza una nueva ráfaga al instante
                    modulo.desactivarAlertaEmergencia(false)
                    modulo.gestorMalla.reiniciarEstadoParaNuevaPrueba()
                    modulo.activarAlertaEmergencia(TipoDetonador.BOTON_SOS)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
            ) { Text("P4: Emitir SOS", fontSize = 11.sp) }

            Button(
                onClick = {
                    registrarEvento("P4 MICRÓFONO", "🎙️ Escuchando... Di 'auxilio' o 'sendero seguro'")
                    modulo.escucharComandoVozMicrofono()
                },
                modifier = Modifier.weight(1f)
            ) { Text("P4: Voz 🎙️", fontSize = 11.sp) }

            OutlinedButton(
                onClick = {
                    modulo.desactivarAlertaEmergencia(false)
                    modulo.gestorMalla.reiniciarEstadoParaNuevaPrueba()
                    ultimaAlertaMallaRecibida = null
                    registrarEvento("REINICIO", "Listo para recibir nueva alerta sin cerrar la app")
                },
                modifier = Modifier.weight(1f)
            ) { Text("Reiniciar", fontSize = 11.sp) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val ruta = listOf(
                        PuntoRuta(lat, lon),
                        PuntoRuta(lat + 0.0008, lon + 0.0008),
                        PuntoRuta(lat + 0.0016, lon + 0.0016)
                    )
                    modulo.gestorCaminataSegura.iniciarCaminataSegura(
                        rutaObjetivo = ruta,
                        tiempoEstimadoLlegadaMs = 300_000L,
                        intervaloCheckInSeg = 12L,
                        toleranciaMetros = 50.0
                    )
                    registrarEvento("P7 RUTA", "Caminata Segura iniciada (Check-in: 12s)")
                },
                modifier = Modifier.weight(1f)
            ) { Text("P7: Iniciar Ruta", fontSize = 11.sp) }

            Button(
                onClick = {
                    modulo.gestorCaminataSegura.confirmarPresenciaCheckIn()
                    registrarEvento("P7 CHECK-IN", "Check-in confirmado (12s)")
                },
                modifier = Modifier.weight(1f)
            ) { Text("P7: Check-In", fontSize = 11.sp) }

            OutlinedButton(
                onClick = {
                    val latLejana = lat + 0.0020
                    modulo.gestorTelemetria.simularUbicacionParaPruebas(latLejana, lon)
                    modulo.gestorCaminataSegura.evaluarUbicacionEnRuta(latLejana, lon)
                    registrarEvento("P7 DESVÍO", "Desviación simulada a +200m")
                },
                modifier = Modifier.weight(1f)
            ) { Text("P7: Desviar", fontSize = 11.sp) }
        }

        // 6. BITÁCORA EN PANTALLA
        Text("Bitácora de Procesos (Evidencia en vivo):", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            items(bitacora) { linea ->
                Text(
                    text = linea,
                    color = Color(0xFF00E676),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}