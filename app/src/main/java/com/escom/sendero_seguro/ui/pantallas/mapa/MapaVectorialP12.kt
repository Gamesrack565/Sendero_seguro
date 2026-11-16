package com.escom.sendero_seguro.ui.pantallas.mapa

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.escom.sendero_seguro.mapas.remoto.CapaRiesgoDto
import com.escom.sendero_seguro.mapas.remoto.GrafoMapaDto
import com.escom.sendero_seguro.reportes.ReporteComunitarioPublicoDto
import com.escom.sendero_seguro.ui.theme.Superficie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

@Composable
internal fun MapaVectorialP12(
    centro: LatLng,
    zoom: Double,
    mbtilesPath: String?,
    capaRiesgo: CapaRiesgoDto?,
    grafo: GrafoMapaDto?,
    reportes: List<ReporteComunitarioPublicoDto>,
    mostrarMapaBase: Boolean,
    mostrarCapaRiesgo: Boolean,
    mostrarGrafo: Boolean,
    mostrarReportes: Boolean,
    onReporteSeleccionado: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var mapaLibre by remember { mutableStateOf<MapLibreMap?>(null) }
    var errorGlifos by remember { mutableStateOf<String?>(null) }
    val mapView = remember(context) { crearMapView(context) { mapaLibre = it } }
    var camaraInicializada by remember(mapView) { mutableStateOf(false) }
    val callbackSeleccion = rememberUpdatedState(onReporteSeleccionado)

    DisposableEffect(mapView, lifecycle) {
        var started = false
        var resumed = false
        var destroyed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!started) {
                    mapView.onStart()
                    started = true
                }
                Lifecycle.Event.ON_RESUME -> if (!resumed) {
                    mapView.onResume()
                    resumed = true
                }
                Lifecycle.Event.ON_PAUSE -> if (resumed) {
                    mapView.onPause()
                    resumed = false
                }
                Lifecycle.Event.ON_STOP -> if (started) {
                    mapView.onStop()
                    started = false
                }
                Lifecycle.Event.ON_DESTROY -> if (!destroyed) {
                    mapView.onDestroy()
                    destroyed = true
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            if (!destroyed) mapView.onDestroy()
            mapaLibre = null
        }
    }

    LaunchedEffect(
        mapaLibre, mbtilesPath, capaRiesgo, grafo, reportes, mostrarMapaBase,
        mostrarCapaRiesgo, mostrarGrafo, mostrarReportes, centro, zoom,
    ) {
        val map = mapaLibre ?: return@LaunchedEffect
        val directorioFuentes = if (mostrarMapaBase && mbtilesPath != null) {
            try {
                withContext(Dispatchers.IO) { prepararGlifosLocales(context) }
            } catch (e: IOException) {
                Log.e(TAG, "No se pudieron preparar las fuentes offline del mapa.", e)
                errorGlifos = "No se pudieron cargar las etiquetas offline del mapa."
                return@LaunchedEffect
            }
        } else {
            null
        }
        errorGlifos = null
        val styleJson = withContext(Dispatchers.Default) {
            crearEstiloVectorial(
                mbtilesPath = mbtilesPath.takeIf { mostrarMapaBase },
                capa = capaRiesgo,
                grafo = grafo,
                reportes = reportes,
                mostrarCapaRiesgo = mostrarCapaRiesgo,
                mostrarGrafo = mostrarGrafo,
                mostrarReportes = mostrarReportes,
                directorioFuentes = directorioFuentes,
            )
        }
        map.setStyle(Style.Builder().fromJson(styleJson)) {
            if (!camaraInicializada) {
                map.cameraPosition = CameraPosition.Builder()
                    .target(centro)
                    .zoom(zoom)
                    .build()
                camaraInicializada = true
            }
        }
    }

    DisposableEffect(mapaLibre) {
        val map = mapaLibre
        if (map == null) {
            onDispose {}
        } else {
            val listener = MapLibreMap.OnMapClickListener { coordenada ->
                val punto = map.projection.toScreenLocation(coordenada)
                val reporteId = map.queryRenderedFeatures(punto, REPORTES_LAYER_ID)
                    .firstOrNull()
                    ?.getStringProperty(REPORTE_ID_PROPERTY)
                callbackSeleccion.value(reporteId)
                reporteId != null
            }
            map.addOnMapClickListener(listener)
            onDispose { map.removeOnMapClickListener(listener) }
        }
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
        )
        if (mostrarMapaBase && mbtilesPath == null) {
            Text(
                "Falta publicar el paquete vectorial MBTiles de CDMX.",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(8.dp)
                    .background(Superficie, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        errorGlifos?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(8.dp)
                    .background(Superficie, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Text(
            "© OpenMapTiles · © OpenStreetMap contributors · MapLibre",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .background(Superficie, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

private fun crearMapView(context: Context, onMapReady: (MapLibreMap) -> Unit): MapView {
    MapLibre.getInstance(context.applicationContext)
    return MapView(context).apply {
        onCreate(null)
        getMapAsync(onMapReady)
    }
}

private fun prepararGlifosLocales(context: Context): File {
    val directorioFuentes = File(context.filesDir, "p12/mapas/fonts")
    val directorioOpenSans = File(directorioFuentes, "Open Sans Regular")
    if (!directorioOpenSans.isDirectory && !directorioOpenSans.mkdirs()) {
        throw IOException("No se pudo preparar el directorio de fuentes del mapa.")
    }

    listOf("0-255", "256-511").forEach { rango ->
        val destino = File(directorioOpenSans, "$rango.pbf")
        if (destino.isFile && destino.length() > 0) return@forEach
        if (destino.exists() && !destino.delete()) {
            throw IOException("No se pudo reemplazar el archivo de fuente $rango.")
        }

        val temporal = File(directorioOpenSans, "$rango.pbf.tmp")
        try {
            context.assets.open("mapfonts/Open Sans Regular/$rango.pbf").use { entrada ->
                FileOutputStream(temporal).use { salida -> entrada.copyTo(salida) }
            }
            if (!temporal.renameTo(destino)) {
                throw IOException("No se pudo guardar el archivo de fuente $rango.")
            }
        } catch (e: IOException) {
            temporal.delete()
            throw IOException("No se pudo copiar la fuente offline del mapa ($rango).", e)
        }
    }
    return directorioFuentes
}

private fun crearEstiloVectorial(
    mbtilesPath: String?,
    capa: CapaRiesgoDto?,
    grafo: GrafoMapaDto?,
    reportes: List<ReporteComunitarioPublicoDto>,
    mostrarCapaRiesgo: Boolean,
    mostrarGrafo: Boolean,
    mostrarReportes: Boolean,
    directorioFuentes: File?,
): String {
    val sources = JSONObject()
    if (mbtilesPath != null) {
        sources.put(
            "basemap",
            JSONObject()
                .put("type", "vector")
                .put("url", "mbtiles://$mbtilesPath"),
        )
    }
    sources
        .put("risk", JSONObject().put("type", "geojson").put("data", geoJsonCapa(capa)))
        .put("road-graph", JSONObject().put("type", "geojson").put("data", geoJsonGrafo(grafo)))
        .put("community-reports", JSONObject().put("type", "geojson").put("data", geoJsonReportes(reportes)))

    val layers = JSONArray()
        .put(
            JSONObject()
                .put("id", "background")
                .put("type", "background")
                .put("paint", JSONObject().put("background-color", "#f0eee8")),
        )
    if (mbtilesPath != null) {
        layers
            .put(
                vectorFillLayer("landcover", "landcover", "#e7eddc"),
            )
            .put(
                vectorFillLayer("landuse", "landuse", "#dce8d2"),
            )
            .put(
                vectorFillLayer("water", "water", "#a9d8ed"),
            )
            .put(
                vectorFillLayer("buildings", "building", "#d7d1c8"),
            )
            .put(
                vectorLineLayer("roads-casing", "#c7b9a7", 1.0, 4.5, 9.0),
            )
            .put(
                vectorLineLayer("roads", "#fffdf8", 0.5, 2.5, 6.0),
            )
            .put(
            vectorSymbolLayer("place-labels", "place", "point", 10.0, 14.0),
            )
            .put(
            vectorSymbolLayer("road-labels", "transportation_name", "line", 8.0, 12.0),
            )
    }
    if (mostrarCapaRiesgo) {
        layers.put(
            JSONObject()
                .put("id", "risk-zones")
                .put("type", "fill")
                .put("source", "risk")
                .put(
                    "paint",
                    JSONObject()
                        .put(
                            "fill-color",
                            JSONArray()
                                .put("match")
                                .put(JSONArray().put("get").put("nivel"))
                                .put("ALTO").put("#d32f2f")
                                .put("MEDIO").put("#f9a825")
                                .put("#2e7d32"),
                        )
                        .put("fill-opacity", 0.35)
                        .put("fill-outline-color", "#6d4c41"),
                ),
        )
    }
    if (mostrarGrafo) {
        layers.put(
            JSONObject()
                .put("id", "road-graph-edges")
                .put("type", "line")
                .put("source", "road-graph")
                .put(
                    "paint",
                    JSONObject()
                        .put(
                            "line-color",
                            JSONArray()
                                .put("case")
                                .put(JSONArray().put(">=").put(JSONArray().put("get").put("riesgo")).put(0.65))
                                .put("#c62828")
                                .put(JSONArray().put(">=").put(JSONArray().put("get").put("riesgo")).put(0.35))
                                .put("#ef6c00")
                                .put("#1565c0"),
                        )
                        .put("line-width", 5),
                ),
        )
            .put(
            JSONObject()
                .put("id", "road-graph-nodes")
                .put("type", "circle")
                .put("source", "road-graph")
                .put(
                    "paint",
                    JSONObject()
                        .put("circle-radius", 5)
                        .put("circle-color", "#0d47a1")
                        .put("circle-stroke-color", "#ffffff")
                        .put("circle-stroke-width", 2),
                ),
            )
    }
    if (mostrarReportes) {
        layers.put(
            JSONObject()
                .put("id", REPORTES_LAYER_ID)
                .put("type", "circle")
                .put("source", "community-reports")
                .put(
                    "paint",
                    JSONObject()
                        .put("circle-radius", 8)
                        .put("circle-color", "#ad1457")
                        .put("circle-stroke-color", "#ffffff")
                        .put("circle-stroke-width", 2),
                ),
        )
    }

    val style = JSONObject()
        .put("version", 8)
        .put("name", "Sendero Seguro vectorial")
        .put("sources", sources)
        .put("layers", layers)
    if (mbtilesPath != null && directorioFuentes != null) {
        style.put(
            "glyphs",
            "file://${directorioFuentes.absolutePath}/{fontstack}/{range}.pbf",
        )
    }
    return style.toString()
}

private fun vectorSymbolLayer(
    id: String,
    sourceLayer: String,
    placement: String,
    sizeAtZoom10: Double,
    sizeAtZoom16: Double,
): JSONObject =
    JSONObject()
        .put("id", id)
        .put("type", "symbol")
        .put("source", "basemap")
        .put("source-layer", sourceLayer)
        .put(
            "layout",
            JSONObject()
                .put("symbol-placement", placement)
                .put(
                    "text-field",
                    JSONArray()
                        .put("coalesce")
                        .put(JSONArray().put("get").put("name:es"))
                        .put(JSONArray().put("get").put("name"))
                        .put(JSONArray().put("get").put("name:latin")),
                )
                .put("text-font", JSONArray().put("Open Sans Regular"))
                .put(
                    "text-size",
                    JSONArray()
                        .put("interpolate")
                        .put(JSONArray().put("linear"))
                        .put(JSONArray().put("zoom"))
                        .put(10).put(sizeAtZoom10)
                        .put(16).put(sizeAtZoom16),
                )
                .put("text-max-angle", 35)
                .put("text-keep-upright", true),
        )
        .put(
            "paint",
            JSONObject()
                .put("text-color", "#544b43")
                .put("text-halo-color", "#fffdf8")
                .put("text-halo-width", 1.2),
        )

private fun vectorFillLayer(id: String, sourceLayer: String, color: String): JSONObject =
    JSONObject()
        .put("id", id)
        .put("type", "fill")
        .put("source", "basemap")
        .put("source-layer", sourceLayer)
        .put("paint", JSONObject().put("fill-color", color))

private fun vectorLineLayer(
    id: String,
    color: String,
    widthAtZoom10: Double,
    widthAtZoom15: Double,
    widthAtZoom19: Double,
): JSONObject =
    JSONObject()
        .put("id", id)
        .put("type", "line")
        .put("source", "basemap")
        .put("source-layer", "transportation")
        .put(
            "paint",
            JSONObject()
                .put("line-color", color)
                .put(
                    "line-width",
                    JSONArray()
                        .put("interpolate")
                        .put(JSONArray().put("linear"))
                        .put(JSONArray().put("zoom"))
                        .put(10).put(widthAtZoom10)
                        .put(15).put(widthAtZoom15)
                        .put(19).put(widthAtZoom19),
                ),
        )

private fun geoJsonCapa(capa: CapaRiesgoDto?): JSONObject =
    JSONObject().put("type", "FeatureCollection").put(
        "features",
        JSONArray().apply {
            capa?.zonas.orEmpty().forEach { zona ->
                put(
                    JSONObject()
                        .put("type", "Feature")
                        .put("properties", JSONObject().put("nivel", zona.nivel))
                        .put("geometry", JSONObject(zona.geometria.toString())),
                )
            }
        },
    )

private fun geoJsonGrafo(grafo: GrafoMapaDto?): JSONObject =
    JSONObject().put("type", "FeatureCollection").put(
        "features",
        JSONArray().apply {
            val nodosPorId = grafo?.nodos.orEmpty().associateBy { it.id }
            grafo?.aristas.orEmpty().forEach { arista ->
                val origen = nodosPorId[arista.origen] ?: return@forEach
                val destino = nodosPorId[arista.destino] ?: return@forEach
                put(
                    JSONObject()
                        .put("type", "Feature")
                        .put(
                            "properties",
                            JSONObject().put("id", arista.id).put("riesgo", arista.riesgo),
                        )
                        .put(
                            "geometry",
                            JSONObject()
                                .put("type", "LineString")
                                .put(
                                    "coordinates",
                                    JSONArray()
                                        .put(JSONArray().put(origen.longitud).put(origen.latitud))
                                        .put(JSONArray().put(destino.longitud).put(destino.latitud)),
                                ),
                        ),
                )
            }
            grafo?.nodos.orEmpty().forEach { nodo ->
                put(
                    JSONObject()
                        .put("type", "Feature")
                        .put("properties", JSONObject().put("id", nodo.id))
                        .put(
                            "geometry",
                            JSONObject()
                                .put("type", "Point")
                                .put("coordinates", JSONArray().put(nodo.longitud).put(nodo.latitud)),
                        ),
                )
            }
        },
    )

private fun geoJsonReportes(reportes: List<ReporteComunitarioPublicoDto>): JSONObject =
    JSONObject().put("type", "FeatureCollection").put(
        "features",
        JSONArray().apply {
            reportes.forEach { reporte ->
                put(
                    JSONObject()
                        .put("type", "Feature")
                        .put("properties", JSONObject().put(REPORTE_ID_PROPERTY, reporte.id))
                        .put(
                            "geometry",
                            JSONObject()
                                .put("type", "Point")
                                .put("coordinates", JSONArray().put(reporte.longitud).put(reporte.latitud)),
                        ),
                )
            }
        },
    )

private const val TAG = "MapaVectorialP12"
private const val REPORTES_LAYER_ID = "community-report-points"
private const val REPORTE_ID_PROPERTY = "reporteId"
