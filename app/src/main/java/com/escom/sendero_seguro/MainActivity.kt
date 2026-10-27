package com.escom.sendero_seguro

import android.Manifest
import android.graphics.Color as AndroidColor
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configuración de OpenStreetMap (guarda el mapa en caché local para uso offline)
        Configuration.getInstance().load(applicationContext, PreferenceManager.getDefaultSharedPreferences(applicationContext))
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
    val context = LocalContext.current
    val alerta by modulo.alertaActiva.collectAsState()
    val telemetria by modulo.flujoTelemetria.collectAsState()
    val caminata by modulo.estadoCaminata.collectAsState()

    // Bitácora visual en pantalla para ver qué hace cada proceso en tiempo real
    val bitacora = remember { mutableStateListOf<String>() }
    fun registrarEvento(proceso: String, mensaje: String) {
        val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        bitacora.add(0, "[$hora] [$proceso] $mensaje")
    }

    // Escuchar paquetes de Malla P5.1 y mostrarlos en la bitácora
    LaunchedEffect(Unit) {
        modulo.flujoPaquetesMalla.collect { pkt ->
            registrarEvento("P5.1 MALLA", "Paquete ${pkt.packetId.take(8)} transmitido | TTL=${pkt.ttl}")
        }
    }

    // Escuchar cambios de Alerta P4
    LaunchedEffect(alerta) {
        alerta?.let {
            if (it.activa) {
                registrarEvento("P4 ALERTA", "¡SOS ACTIVO! Detonador: ${it.detonador} | Bat: ${it.nivelBateria}%")
            }
        }
    }

    // Solicitar permisos y encender sensores de inmediato
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permisos ->
        if (permisos[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            modulo.gestorTelemetria.iniciarTelemetriaTiempoReal(2000L)
            registrarEvento("P6 SENSORES", "Permiso GPS concedido. Leyendo ubicación y acelerómetro...")
        } else {
            registrarEvento("ERROR", "Permiso de ubicación denegado por el usuario.")
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    val lat = telemetria?.latitud ?: 19.5045
    val lon = telemetria?.longitud ?: -99.1469

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Diagnóstico en Vivo - Módulo 2", fontSize = 18.sp, fontWeight = FontWeight.Bold)

        // 1. MAPA REAL DE CALLES (OpenStreetMap con caché Offline)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
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

                    // Si Caminata Segura (P7) está activa, dibujar la línea de la ruta en el mapa
                    if (caminata.rutaObjetivo.isNotEmpty()) {
                        val lineaRuta = Polyline().apply {
                            setPoints(caminata.rutaObjetivo.map { GeoPoint(it.latitud, it.longitud) })
                            outlinePaint.color = if (caminata.fueraDeRuta) AndroidColor.RED else AndroidColor.rgb(0, 180, 0)
                            outlinePaint.strokeWidth = 10f
                        }
                        mapView.overlays.add(lineaRuta)
                    }

                    // Marcador de tu posición actual
                    val marcadorUsuario = Marker(mapView).apply {
                        position = puntoActual
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = if (alerta?.activa == true) "🚨 EMERGENCIA ACTIVA" else "📍 Tu ubicación actual"
                    }
                    mapView.overlays.add(marcadorUsuario)
                    mapView.invalidate()
                }
            )
        }

        // 2. TARJETA DE TELEMETRÍA EN TIEMPO REAL (P6)
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (alerta?.activa == true) Color(0xFFFFEBEE) else Color(0xFFE8F5E9)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = if (alerta?.activa == true) "🚨 P4: ALERTA ACTIVA (${alerta?.detonador})" else "✅ P4: Sistema en espera (Sacude el cel para activar por caída)",
                    fontWeight = FontWeight.Bold,
                    color = if (alerta?.activa == true) Color.Red else Color(0xFF1B5E20),
                    fontSize = 13.sp
                )
                Text(
                    text = "P6 GPS: ${"%.5f".format(lat)}, ${"%.5f".format(lon)} | Vel: ${telemetria?.velocidadMps ?: 0f} m/s",
                    fontSize = 12.sp
                )
                Text(
                    text = "P6 Sensores: Fuerza G = ${"%.2f".format(telemetria?.aceleracionG ?: 1f)}G (Umbral: 2.8G) | Batería: ${telemetria?.nivelBateria ?: 100}%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (caminata.activa) {
                    Text(
                        text = "P7 Caminata: Check-in en ${caminata.segundosRestantesCheckIn}s | Desvío: ${caminata.desviacionActualMetros.toInt()}m " +
                                if (caminata.fueraDeRuta) "⚠️ FUERA DE RUTA" else "✓ En ruta",
                        fontSize = 12.sp,
                        color = if (caminata.fueraDeRuta) Color.Red else Color.Blue,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 3. BOTONES DE PRUEBA PARA CADA PROCESO
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { modulo.activarAlertaEmergencia(TipoDetonador.BOTON_SOS) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
            ) { Text("P4: SOS", fontSize = 11.sp) }

            Button(
                onClick = {
                    val detectado = modulo.procesarTextoDeVoz("auxilio sendero seguro")
                    if (detectado) registrarEvento("P4 VOZ", "Palabra clave 'auxilio' detectada")
                },
                modifier = Modifier.weight(1f)
            ) { Text("P4: Voz", fontSize = 11.sp) }

            OutlinedButton(
                onClick = {
                    modulo.desactivarAlertaEmergencia(false)
                    registrarEvento("P8 PIN", "Alerta desactivada por el usuario")
                },
                modifier = Modifier.weight(1f)
            ) { Text("Apagar", fontSize = 11.sp) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    // Crea una ruta de 2 cuadras a partir de DONDE ESTÁS PARADO AHORITA
                    val rutaDesdeMiUbicacion = listOf(
                        PuntoRuta(lat, lon),
                        PuntoRuta(lat + 0.0008, lon + 0.0008),
                        PuntoRuta(lat + 0.0016, lon + 0.0016)
                    )
                    modulo.gestorCaminataSegura.iniciarCaminataSegura(
                        rutaObjetivo = rutaDesdeMiUbicacion,
                        tiempoEstimadoLlegadaMs = 300_000L,
                        intervaloCheckInSeg = 12L,
                        toleranciaMetros = 50.0
                    )
                    registrarEvento("P7 RUTA", "Ruta trazada desde tu ubicación actual (Check-in: 12s)")
                },
                modifier = Modifier.weight(1f)
            ) { Text("P7: Trazar Ruta Aquí", fontSize = 11.sp) }

            Button(
                onClick = {
                    modulo.gestorCaminataSegura.confirmarPresenciaCheckIn()
                    registrarEvento("P7 CHECK-IN", "Presencia confirmada. Reloj reiniciado a 12s")
                },
                modifier = Modifier.weight(1f)
            ) { Text("P7: Check-In", fontSize = 11.sp) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    // Simula que te alejaste 200 metros de tu casa/ubicación actual
                    val latLejana = lat + 0.0020
                    modulo.gestorTelemetria.simularUbicacionParaPruebas(latLejana, lon)
                    modulo.gestorCaminataSegura.evaluarUbicacionEnRuta(latLejana, lon)
                    registrarEvento("P7 DESVÍO", "Simulando caminar fuera de la línea verde...")
                },
                modifier = Modifier.weight(1f)
            ) { Text("Simular Desvío Ruta", fontSize = 11.sp) }

            OutlinedButton(
                onClick = {
                    val res = modulo.gestorMalla.interceptarYRetransmitir(
                        PaqueteMalla(
                            packetId = "MESH-${(10..99).random()}",
                            emisorId = "VECINO",
                            latitud = lat,
                            longitud = lon,
                            nivelBateria = 80,
                            ttl = 3
                        )
                    )
                    if (res != null) {
                        registrarEvento("P5.1 MALLA", "Paquete vecino recibido (TTL 3 -> ${res.ttl})")
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("P5.1: Rebote Malla", fontSize = 11.sp) }
        }

        // 4. CONSOLA DE BITÁCORA EN PANTALLA
        Text("Bitácora de Procesos (Evidencia de funcionamiento):", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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