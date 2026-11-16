package com.escom.sendero_seguro.ui.pantallas.mapa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.escom.sendero_seguro.mapas.EstadoMapaP12
import com.escom.sendero_seguro.mapas.ModuloMapas
import com.escom.sendero_seguro.mapas.SincronizarMapasWorker
import com.escom.sendero_seguro.mapas.remoto.CapaRiesgoDto
import com.escom.sendero_seguro.mapas.remoto.ZonaMapaDto
import com.escom.sendero_seguro.reportes.ModuloReportes
import com.escom.sendero_seguro.reportes.ReporteComunitarioPublicoDto
import com.escom.sendero_seguro.reportes.SincronizarFeedReportesWorker
import com.escom.sendero_seguro.ui.demo.DatosDemo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.android.geometry.LatLng
import retrofit2.HttpException
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Date
import java.util.Locale

@Composable
fun PantallaMapaP12(
    moduloMapas: ModuloMapas,
    moduloReportes: ModuloReportes,
    onVolver: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val estado by moduloMapas.estado.collectAsState()
    val feed by moduloReportes.feedPublico.collectAsState()
    val scope = rememberCoroutineScope()
    var errorFeed by remember { mutableStateOf<String?>(null) }
    var cargandoFeed by remember { mutableStateOf(false) }
    var mostrarBase by remember { mutableStateOf(true) }
    var mostrarCalor by remember { mutableStateOf(true) }
    var mostrarGrafo by remember { mutableStateOf(true) }
    var mostrarReportes by remember { mutableStateOf(true) }
    var mostrarFiltros by remember { mutableStateOf(false) }
    var categoriaSeleccionada by remember { mutableStateOf<String?>(null) }
    var periodoSeleccionado by remember { mutableStateOf(7) }
    var franjaSeleccionada by remember { mutableStateOf(FRANJAS_HORARIAS.first().id) }
    var reporteSeleccionadoId by remember { mutableStateOf<String?>(null) }
    val reportes = feed?.reportes.orEmpty()
    val reportesFiltrados = remember(reportes, categoriaSeleccionada, periodoSeleccionado, franjaSeleccionada) {
        filtrarReportes(reportes, categoriaSeleccionada, periodoSeleccionado, franjaSeleccionada)
    }

    suspend fun refrescarFeed() {
        cargandoFeed = true
        errorFeed = null
        try {
            val resultado = moduloReportes.cargarFeedPublico()
            if (resultado.desdeCache) SincronizarFeedReportesWorker.programarInmediato(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            errorFeed = "El servidor respondió HTTP ${e.code()} al cargar los reportes."
        } catch (e: IOException) {
            errorFeed = "No se pudieron cargar los reportes comunitarios: ${e.localizedMessage ?: "sin conexión"}."
        } finally {
            cargandoFeed = false
        }
    }

    LaunchedEffect(feed) {
        if (feed != null) errorFeed = null
    }

    LaunchedEffect(moduloMapas, moduloReportes) {
        moduloMapas.cargarCacheLocal()
        SincronizarMapasWorker.programarInmediato(context)
        SincronizarFeedReportesWorker.programarInmediato(context)
        SincronizarFeedReportesWorker.programarPeriodico(context)
        refrescarFeed()
        while (true) {
            delay(CHEQUEO_VISIBLE_MS)
            SincronizarMapasWorker.programarInmediato(context)
        }
    }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            MapaVectorialP12(
                centro = LatLng(DatosDemo.ESCOM.latitud, DatosDemo.ESCOM.longitud),
                modifier = Modifier.fillMaxSize(),
                zoom = 15.5,
                mbtilesPath = estado.archivoMbtilesLocal,
                capaRiesgo = estado.capaRiesgo,
                grafo = estado.grafoVial,
                reportes = reportesFiltrados,
                mostrarMapaBase = mostrarBase,
                mostrarCapaRiesgo = mostrarCalor,
                mostrarGrafo = mostrarGrafo,
                mostrarReportes = mostrarReportes,
                onReporteSeleccionado = { reporteSeleccionadoId = it },
            )
            Column(
                Modifier.align(Alignment.BottomStart).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ControlCapa("Base", mostrarBase) { mostrarBase = it }
                ControlCapa("Calor", mostrarCalor) { mostrarCalor = it }
                ControlCapa("Grafo", mostrarGrafo) { mostrarGrafo = it }
                ControlCapa("Reportes", mostrarReportes) { mostrarReportes = it }
            }
            reportesFiltrados.firstOrNull { it.id == reporteSeleccionadoId }?.let { reporte ->
                TarjetaReporteComunitario(
                    reporte = reporte,
                    capa = estado.capaRiesgo,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).widthIn(max = 300.dp),
                    onCerrar = { reporteSeleccionadoId = null },
                )
            }
        }
        InformacionVersiones(estado)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    cargandoFeed -> "Cargando reportes…"
                    errorFeed != null -> errorFeed.orEmpty()
                    feed?.desdeCache == true -> "${reportesFiltrados.size} reportes · copia sin conexión"
                    feed != null -> "${reportesFiltrados.size} reportes publicados"
                    else -> "Todavía no hay reportes descargados"
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { mostrarFiltros = true }) { Text("Filtros") }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onVolver) { Text("Volver") }
            TextButton(
                enabled = !cargandoFeed,
                onClick = { scope.launch { refrescarFeed() } },
            ) { Text("Actualizar reportes") }
            TextButton(
                enabled = !estado.sincronizando,
                onClick = { SincronizarMapasWorker.programarInmediato(context) },
            ) {
                if (estado.sincronizando) {
                    CircularProgressIndicator(Modifier.size(16.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                }
                Text(if (estado.sincronizando) "Actualizando" else "Comprobar ahora")
            }
        }

        if (mostrarFiltros) {
            AlertDialog(
                onDismissRequest = { mostrarFiltros = false },
                title = { Text("Filtrar reportes comunitarios") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Periodo de publicación")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(7, 30).forEach { dias ->
                                FilterChip(
                                    selected = periodoSeleccionado == dias,
                                    onClick = { periodoSeleccionado = dias },
                                    label = { Text("$dias días") },
                                )
                            }
                        }
                        Text("Categoría")
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            listOf(null, "FISICO", "SOCIAL", "DIGITAL").forEach { categoria ->
                                FilterChip(
                                    selected = categoriaSeleccionada == categoria,
                                    onClick = { categoriaSeleccionada = categoria },
                                    label = {
                                        Text(categoria?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "Todas")
                                    },
                                )
                            }
                        }
                        Text("Franja horaria local")
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FRANJAS_HORARIAS.forEach { franja ->
                                FilterChip(
                                    selected = franjaSeleccionada == franja.id,
                                    onClick = { franjaSeleccionada = franja.id },
                                    label = { Text(franja.etiqueta) },
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { mostrarFiltros = false }) { Text("Listo") }
                },
            )
        }
    }

}

@Composable
private fun ControlCapa(texto: String, activado: Boolean, onCambio: (Boolean) -> Unit) {
    FilterChip(
        selected = activado,
        onClick = { onCambio(!activado) },
        label = { Text(texto) },
    )
}

@Composable
private fun InformacionVersiones(estado: EstadoMapaP12) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            estado.capaRiesgo?.let { "Mapa de calor v${it.version} · descargado ${fecha(estado.descargadaCapaEn)}" }
                ?: "Mapa de calor: todavía no descargado",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            estado.versionGrafo?.let {
                "Grafo vial v$it · ${estado.cantidadNodosGrafo ?: 0} nodos · " +
                    "${estado.grafoVial?.aristas?.size ?: 0} tramos · descargado ${fecha(estado.descargadoGrafoEn)}"
            }
                ?: "Grafo vial: todavía no descargado en este dispositivo",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            if (estado.archivoMbtilesLocal != null) "Mapa base vectorial CDMX listo" else "Mapa base vectorial CDMX pendiente",
            style = MaterialTheme.typography.bodySmall,
        )
        if (estado.grafoVial != null) {
            Text(
                "Red vial superpuesta · azul: riesgo bajo · naranja: medio · rojo: alto",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            estado.cantidadNodosGrafo?.let { "Grafo para P7: $it nodos" }
                ?: "Grafo para P7: todavía no descargado",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Almacenado: ${formatoMb(estado.tamanoTotalBytes)} " +
                "(capa ${formatoMb(estado.tamanoCapaBytes)}, grafo ${formatoMb(estado.tamanoGrafoBytes)}, " +
                "paquetes ${formatoMb(estado.tamanoPaquetesBytes)})",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "${estado.mensaje} · ${estado.paquetesDescargados} paquete(s) offline",
            style = MaterialTheme.typography.bodySmall,
        )
        estado.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TarjetaReporteComunitario(
    reporte: ReporteComunitarioPublicoDto,
    capa: CapaRiesgoDto?,
    modifier: Modifier = Modifier,
    onCerrar: () -> Unit,
) {
    val zona = capa?.zonas?.firstOrNull { it.periodoDias == 30 && puntoEnZona(reporte.latitud, reporte.longitud, it) }
    Card(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(reporte.tipo, style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onCerrar) { Text("Cerrar") }
            }
            Text("Categoría: ${reporte.categoria ?: "Sin categoría"}")
            Text("Autor: ${reporte.autor}")
            Text("Ubicación aproximada: ${reporte.referencia ?: "celda ${reporte.celda}"}")
            Text("Publicado: ${fechaPublicacion(reporte.publicadoEn)}")
            Text(reporte.descripcion, style = MaterialTheme.typography.bodySmall)
            if (zona != null) {
                Text(
                    "Zona ${zona.nivel.lowercase()} · ${zona.numReportes} reportes · " +
                        "peso ${"%.2f".format(Locale.getDefault(), zona.densidadPonderada)} " +
                        "(capa ${zona.periodoDias} días, v${capa.version})",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text("Sin zona de calor asociada en la capa descargada.")
            }
        }
    }
}

private data class FranjaHoraria(val id: String, val etiqueta: String, val desde: Int, val hasta: Int)

private val FRANJAS_HORARIAS = listOf(
    FranjaHoraria("TODO", "Todo el día", 0, 24),
    FranjaHoraria("MADRUGADA", "Madrugada · 00:00–06:00", 0, 6),
    FranjaHoraria("MANANA", "Mañana · 06:00–12:00", 6, 12),
    FranjaHoraria("TARDE", "Tarde · 12:00–18:00", 12, 18),
    FranjaHoraria("NOCHE", "Noche · 18:00–24:00", 18, 24),
)

internal fun filtrarReportes(
    reportes: List<ReporteComunitarioPublicoDto>,
    categoria: String?,
    dias: Int,
    franjaId: String,
): List<ReporteComunitarioPublicoDto> {
    val limite = Instant.now().minusSeconds(dias * 24L * 60L * 60L)
    val franja = FRANJAS_HORARIAS.first { it.id == franjaId }
    return reportes.filter { reporte ->
        if (categoria != null && reporte.categoria != categoria) return@filter false
        val publicado = try {
            Instant.parse(reporte.publicadoEn)
        } catch (_: DateTimeParseException) {
            return@filter false
        }
        if (publicado.isBefore(limite) || publicado.isAfter(Instant.now())) return@filter false
        val hora = publicado.atZone(ZoneId.systemDefault()).hour
        hora >= franja.desde && hora < franja.hasta
    }
}

internal fun puntoEnZona(latitud: Double, longitud: Double, zona: ZonaMapaDto): Boolean {
    val geometria = zona.geometria
    val coordenadas = geometria["coordinates"] as? JsonArray ?: return false
    return when (geometria["type"]?.jsonPrimitive?.content) {
        "Polygon" -> puntoEnPoligono(latitud, longitud, coordenadas)
        "MultiPolygon" -> coordenadas.any { poligono ->
            (poligono as? JsonArray)?.let { puntoEnPoligono(latitud, longitud, it) } == true
        }
        else -> false
    }
}

private fun puntoEnPoligono(latitud: Double, longitud: Double, anillos: JsonArray): Boolean {
    val exterior = anillos.firstOrNull() as? JsonArray ?: return false
    if (!puntoEnAnillo(latitud, longitud, exterior)) return false
    return anillos.drop(1).none { anillo ->
        (anillo as? JsonArray)?.let { puntoEnAnillo(latitud, longitud, it) } == true
    }
}

private fun puntoEnAnillo(latitud: Double, longitud: Double, anillo: JsonArray): Boolean {
    var dentro = false
    var anterior = anillo.lastIndex
    for (actual in anillo.indices) {
        val puntoActual = anillo[actual] as? JsonArray
        val puntoAnterior = anillo[anterior] as? JsonArray
        val x1 = puntoActual?.getOrNull(0)?.jsonPrimitive?.doubleOrNull
        val y1 = puntoActual?.getOrNull(1)?.jsonPrimitive?.doubleOrNull
        val x2 = puntoAnterior?.getOrNull(0)?.jsonPrimitive?.doubleOrNull
        val y2 = puntoAnterior?.getOrNull(1)?.jsonPrimitive?.doubleOrNull
        if (x1 == null || y1 == null || x2 == null || y2 == null) {
            anterior = actual
            continue
        }
        if (x1.isFinite() && y1.isFinite() && x2.isFinite() && y2.isFinite() &&
            (y1 > latitud) != (y2 > latitud) &&
            longitud < (x2 - x1) * (latitud - y1) / (y2 - y1) + x1
        ) {
            dentro = !dentro
        }
        anterior = actual
    }
    return dentro
}

private fun fechaPublicacion(valor: String): String =
    try {
        java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse(valor))
    } catch (_: DateTimeParseException) {
        "Fecha no disponible"
    }

private fun fecha(timestamp: Long?): String =
    timestamp?.let { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(it)) } ?: "—"

private fun formatoMb(bytes: Long): String =
    if (bytes == 0L) {
        "0 MB"
    } else {
        val megabytes = bytes / 1_000_000.0
        if (megabytes < 0.001) "<0.001 MB" else String.format(Locale.getDefault(), "%.3f MB", megabytes)
    }

private const val CHEQUEO_VISIBLE_MS = 30_000L
