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
    val context = LocalContext.current
    val colaSqlite = remember { GestorColaLocalSQLiteER06(context) }

    val alerta by modulo.alertaActiva.collectAsState()
    val segundosPreEnvio by modulo.segundosRestantesPreEnvio.collectAsState()
    val telemetria by modulo.flujoTelemetria.collectAsState()
    val caminata by modulo.estadoCaminata.collectAsState()
    val comparativaRutas by modulo.gestorCaminataSegura.comparativaRutasOffline.collectAsState()
    val clasificacionIA1 by modulo.gestorVozPanico.ultimaClasificacionIA1.collectAsState()
    val vistaOperadorAdHoc by modulo.gestorMalla.alertaModoOperadorAdHoc.collectAsState()
    val pendientesSQLite by colaSqlite.pendientesCola.collectAsState()

    var ipLinux by remember { mutableStateOf("10.100.88.182") }
    var ultimaAlertaMallaRecibida by remember { mutableStateOf<PaqueteMalla?>(null) }

    val bitacora = remember { mutableStateListOf<String>() }
    fun registrarEvento(proceso: String, mensaje: String) {
        val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        bitacora.add(0, "[$hora] [$proceso] $mensaje")
    }

    LaunchedEffect(Unit) {
        modulo.gestorMalla.urlServidorLinux = "http://$ipLinux:8080/alerta"
        colaSqlite.refrescarFlujoPendientes()
    }

    LaunchedEffect(Unit) {
        modulo.gestorVozPanico.flujoEventosVoz.collect { msg ->
            registrarEvento("P4 VOZ/IA-1", msg)
        }
    }

    LaunchedEffect(Unit) {
        modulo.flujoPaquetesMalla.collect { pkt ->
            colaSqlite.registrarPaqueteMallaVisto(pkt.packetId, pkt.alertaId, true)
            if (pkt.emisorId == "NODO_CERCANO") {
                ultimaAlertaMallaRecibida = pkt
                val latPropia = telemetria?.latitud ?: 19.5045
                val lonPropia = telemetria?.longitud ?: -99.1469
                modulo.gestorMalla.mostrarAlertaAdHocModoOperador(pkt, latPropia, lonPropia)
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
                colaSqlite.encolarPendienteConVigencia(
                    alertaId = it.idAlerta,
                    payloadBytes = it.folio.toByteArray(),
                    tipoElemento = "ALERTA",
                    ultimoCanal = it.canalRecepcion.name
                )
                registrarEvento(
                    "P4 ALERTA",
                    "¡SOS ACTIVO! Folio: ${it.folio} | Estado: ${it.estadoActual} | Tipo: ${it.nombreTipoIncidente}"
                )
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
            modulo.iniciarProteccionSegundoPlano()
            modulo.gestorTelemetria.iniciarTelemetriaTiempoReal(2000L)
            registrarEvento("SERVICIO", "Protección con pantalla apagada ACTIVA")
        } else {
            registrarEvento("PERMISOS", "⚠️ Activa la Ubicación para usar GPS y escaneo BLE")
        }

        if (btOk) {
            modulo.gestorMalla.asegurarEscaneoMallaActivo()
        } else {
            registrarEvento("PERMISOS", "❌ Permite 'Dispositivos cercanos' en Ajustes de la App")
        }
    }

    LaunchedEffect(Unit) {
        val listaPermisos = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listaPermisos.add(Manifest.permission.BLUETOOTH_SCAN)
            listaPermisos.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            listaPermisos.add(Manifest.permission.BLUETOOTH_CONNECT)
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
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text("Diagnóstico Módulo 2 (E3 + Malla + IA-1)", fontSize = 16.sp, fontWeight = FontWeight.Bold)

        // 1. MAPA (Ubicación propia, Ruta Segura Offline A-18, Caminata P7 y Nodos Malla P5.1)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(155.dp)
                .clip(RoundedCornerShape(12.dp))
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        controller.setZoom(17.5)
                        controller.setCenter(GeoPoint(lat, lon))
                    }
                },
                update = { mapView ->
                    val puntoActual = GeoPoint(lat, lon)
                    mapView.controller.animateTo(puntoActual)
                    mapView.overlays.clear()

                    // Dibujar Ruta Más Corta (Naranja) vs Ruta Segura Recomendada (Verde) de A-18
                    comparativaRutas?.let { comp ->
                        val lineaCorta = Polyline().apply {
                            setPoints(comp.rutaMasCorta.nodos.map { GeoPoint(it.latitud, it.longitud) })
                            outlinePaint.color = AndroidColor.rgb(255, 140, 0)
                            outlinePaint.strokeWidth = 7f
                        }
                        val lineaSegura = Polyline().apply {
                            setPoints(comp.rutaSeguraRecomendada.nodos.map { GeoPoint(it.latitud, it.longitud) })
                            outlinePaint.color = AndroidColor.rgb(0, 150, 136)
                            outlinePaint.strokeWidth = 11f
                        }
                        mapView.overlays.add(lineaCorta)
                        mapView.overlays.add(lineaSegura)
                    }

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
                        title = if (alerta?.activa == true) "🚨 ${alerta?.folio}" else "📍 Tu ubicación"
                    }
                    mapView.overlays.add(marcadorPropio)

                    ultimaAlertaMallaRecibida?.let { alertaVecino ->
                        val marcadorVecino = Marker(mapView).apply {
                            position = GeoPoint(alertaVecino.latitud, alertaVecino.longitud)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_TOP)
                            title = "📲 MALLA: ${alertaVecino.packetId} (Saltos: ${alertaVecino.saltosRecorridos})"
                        }
                        mapView.overlays.add(marcadorVecino)
                    }

                    mapView.invalidate()
                }
            )
        }

        // 2. TARJETA DE ALERTA P4 (A-05/A-06), IA-1 Y TELEMETRÍA P6
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (alerta?.activa == true) Color(0xFFFFEBEE) else Color(0xFFE8F5E9)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                val textoEstado = when {
                    segundosPreEnvio != null && (segundosPreEnvio ?: 0) > 0 ->
                        "⏳ A-05 Cuenta regresiva: Enviando como Genérica en ${segundosPreEnvio}s..."
                    alerta?.activa == true ->
                        "🚨 P4 ACTIVA: ${alerta?.folio} | Estado E-01: ${alerta?.estadoActual} | Tipo: ${alerta?.nombreTipoIncidente}"
                    else ->
                        "✅ P4: En espera | Cola SQLite (ER-06): ${pendientesSQLite.size} pendientes"
                }
                Text(
                    text = textoEstado,
                    fontWeight = FontWeight.Bold,
                    color = if (alerta?.activa == true) Color.Red else Color(0xFF1B5E20),
                    fontSize = 11.sp
                )
                Text(
                    text = "P6 (${telemetria?.fuente ?: "GNSS"}): ${"%.5f".format(lat)}, ${"%.5f".format(lon)} (±${telemetria?.precision?.toInt() ?: 8}m) | G: ${"%.2f".format(telemetria?.aceleracionG ?: 1f)}G | Bat: ${telemetria?.nivelBateria ?: 100}%",
                    fontSize = 10.sp
                )
                clasificacionIA1?.let { ia ->
                    Text(
                        text = "🤖 IA-1 (A-06): ${ia.nombreTipoSugerido} (${(ia.confianza * 100).toInt()}%) | QUÉ: ${ia.que} | DÓNDE: ${ia.donde} | QUIÉN: ${ia.quien}",
                        fontSize = 10.sp,
                        color = Color(0xFF0D47A1),
                        fontWeight = FontWeight.SemiBold
                    )
                }
                comparativaRutas?.let { comp ->
                    Text(
                        text = "🛡️ P7 Ruta Segura (A-18): ${comp.rutaSeguraRecomendada.distanciaTotalKm} km (${comp.rutaSeguraRecomendada.tiempoEstimadoMin} min) vs Corta: ${comp.rutaMasCorta.distanciaTotalKm} km (${comp.rutaMasCorta.advertenciasZonasRiesgo.firstOrNull() ?: "Riesgo alto"})",
                        fontSize = 10.sp,
                        color = Color(0xFF004D40),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // 3. TARJETA MALLA P5.1 Y MODO OPERADOR AD-HOC (A-14)
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (vistaOperadorAdHoc != null) Color(0xFFF3E5F5) else Color(0xFFF5F5F5)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val vistaOp = vistaOperadorAdHoc
                    if (vistaOp != null) {
                        Text(
                            text = "🚨 A-14 MODO OPERADOR AD-HOC: ${vistaOp.paquete.packetId} (Saltos: ${vistaOp.paquete.saltosRecorridos})",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF6A1B9A),
                            fontSize = 11.sp
                        )
                        Text(
                            text = "Brújula: ${vistaOp.textoDistanciaYRumbo} | Acuse: ${if (vistaOp.acusadaLocalmente) "ENVIADO POR MALLA" else "PENDIENTE"}",
                            fontSize = 10.sp,
                            color = Color(0xFF4A148C)
                        )
                    } else {
                        Text(
                            text = "📡 P5.1 Malla BLE activa (Escuchando paquetes P2P con pantalla apagada)...",
                            fontSize = 10.sp,
                            color = Color.DarkGray
                        )
                    }
                }
                if (vistaOperadorAdHoc != null) {
                    Button(
                        onClick = {
                            vistaOperadorAdHoc?.paquete?.let { pkt ->
                                val acuse = modulo.gestorMalla.acusarYAcudirOfflinePorMalla(pkt, "P-214")
                                colaSqlite.encolarPendienteConVigencia(
                                    alertaId = acuse.packetId,
                                    payloadBytes = acuse.packetId.toByteArray(),
                                    tipoElemento = "ACUSE",
                                    ultimoCanal = "MALLA"
                                )
                                registrarEvento("A-14 OPERADOR", "Acuse offline emitido por unidad P-214 y guardado en cola_alerta (vigencia 2h)")
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Acusar A-14", fontSize = 10.sp)
                    }
                }
            }
        }

        // 4. CONFIGURACIÓN DEL SERVIDOR EN LINUX (WiFi)
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
                    val pktSimulado = PaqueteMalla(
                        packetId = "SOS-${(1000..9999).random()}",
                        emisorId = "NODO_CERCANO",
                        latitud = lat + 0.0012,
                        longitud = lon + 0.0010,
                        nivelBateria = 78,
                        ttl = 4,
                        saltosRecorridos = 2
                    )
                    ultimaAlertaMallaRecibida = pktSimulado
                    modulo.gestorMalla.mostrarAlertaAdHocModoOperador(pktSimulado, lat, lon)
                    modulo.gestorMalla.enviarAServidorLinux(pktSimulado)
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Simular Malla A-14", fontSize = 10.sp)
            }
        }

        // 5. BOTONES DE EMERGENCIA P4 (Cuenta regresiva, Voz + IA-1, Reinicio)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    modulo.desactivarAlertaEmergencia(false)
                    modulo.gestorMalla.reiniciarEstadoParaNuevaPrueba()
                    modulo.iniciarCuentaRegresivaYEnvioBase(TipoDetonador.BOTON_SOS, segundosCuenta = 3)
                    registrarEvento("P4 (A-05)", "Cuenta regresiva de 3s iniciada antes de envío genérico")
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
            ) { Text("P4: SOS (3s)", fontSize = 10.sp) }

            Button(
                onClick = {
                    // Prueba directa del clasificador IA-1 + apertura de micrófono offline
                    val ejemploRelato = "Un hombre con sudadera gris me viene siguiendo desde la salida del metro Politécnico hace 10 minutos sobre Wilfrido Massieu"
                    val resultado = modulo.gestorVozPanico.clasificarIncidenteConIA1Local(ejemploRelato)
                    registrarEvento(
                        "P4 IA-1 (A-06)",
                        "Sugerencia: ${resultado.nombreTipoSugerido} (${(resultado.confianza * 100).toInt()}% en ${resultado.latenciaMs}ms)"
                    )
                    modulo.gestorVozPanico.transcribirVozATextoOffline()
                },
                modifier = Modifier.weight(1f)
            ) { Text("P4: Voz + IA-1", fontSize = 10.sp) }

            OutlinedButton(
                onClick = {
                    modulo.desactivarAlertaEmergencia(false)
                    modulo.gestorMalla.reiniciarEstadoParaNuevaPrueba()
                    ultimaAlertaMallaRecibida = null
                    registrarEvento("P8 REINICIO", "Alerta cancelada y malla lista")
                },
                modifier = Modifier.weight(1f)
            ) { Text("Reiniciar", fontSize = 10.sp) }
        }

        // 6. BOTONES P7: RUTA SEGURA OFFLINE (A-18) Y CAMINATA SEGURA
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val comp = modulo.gestorCaminataSegura.calcularRutasEnDispositivoOffline(
                        origen = PuntoRuta(19.5045, -99.1469),
                        destino = PuntoRuta(19.5010, -99.1490)
                    )
                    modulo.gestorCaminataSegura.iniciarCaminataSegura(
                        rutaObjetivo = comp.rutaSeguraRecomendada.nodos,
                        tiempoEstimadoLlegadaMs = comp.rutaSeguraRecomendada.tiempoEstimadoMin * 60_000L,
                        intervaloCheckInSeg = 15L,
                        toleranciaMetros = 50.0
                    )
                    registrarEvento(
                        "P7 (A-18)",
                        "Ruta Segura calculada offline: ${comp.rutaSeguraRecomendada.distanciaTotalKm}km (${comp.rutaSeguraRecomendada.tiempoEstimadoMin} min) evitando Salida Metro Politécnico"
                    )
                },
                modifier = Modifier.weight(1f)
            ) { Text("P7: Ruta Segura", fontSize = 10.sp) }

            Button(
                onClick = {
                    modulo.gestorCaminataSegura.confirmarPresenciaCheckIn()
                    registrarEvento("P7 CHECK-IN", "Presencia confirmada por el usuario")
                },
                modifier = Modifier.weight(1f)
            ) { Text("P7: Check-In", fontSize = 10.sp) }

            OutlinedButton(
                onClick = {
                    val latLejana = lat + 0.0020
                    modulo.gestorTelemetria.simularUbicacionParaPruebas(latLejana, lon)
                    modulo.gestorCaminataSegura.evaluarUbicacionEnRuta(latLejana, lon)
                    registrarEvento("P7 DESVÍO", "Desviación simulada a +200m de la polilínea")
                },
                modifier = Modifier.weight(1f)
            ) { Text("P7: Desviar", fontSize = 10.sp) }
        }

        // 7. BITÁCORA EN PANTALLA
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
                    fontSize = 10.5.sp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}