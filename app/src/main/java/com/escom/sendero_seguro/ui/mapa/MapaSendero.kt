package com.escom.sendero_seguro.ui.mapa

import android.graphics.Color
import android.graphics.DashPathEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.escom.sendero_seguro.ui.theme.Superficie
import com.escom.sendero_seguro.ui.theme.TextoSecundario
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.events.MapEventsReceiver

data class Punto(val latitud: Double, val longitud: Double)

data class MarcadorMapa(val punto: Punto, val titulo: String)

data class PoligonoMapa(val puntos: List<Punto>, val nivel: String)

/** Trazo de una ruta (A-18). [punteada] para la alternativa. */
data class LineaMapa(val puntos: List<Punto>, val color: Int, val punteada: Boolean = false)

/** Proveedor con el que se dibuja el mapa. Se muestra en la esquina para saber cuál está activo. */
enum class ProveedorMapa(val etiqueta: String) {
    MAPBOX("Mapa: MapBox"),
    BASE_OFFLINE("Mapa base (respaldo)")
}

/**
 * Decide qué proveedor usar. MapBox es el principal (E2, CU-34 y CU-35); si no está
 * disponible (sin llave, sin red o con error) se usa el mapa base de OpenStreetMap con
 * teselas en caché, que siempre se puede dibujar.
 */
object SelectorMapa {
    /** Llave de MapBox. Mientras no se configure, el mapa usa el respaldo. */
    var llaveMapbox: String? = null

    fun proveedor(hayRed: Boolean): ProveedorMapa =
        if (!llaveMapbox.isNullOrBlank() && hayRed) ProveedorMapa.MAPBOX else ProveedorMapa.BASE_OFFLINE
}

/** Mapa único de la app. Las pantallas solo usan este componente, nunca un proveedor directo. */
@Composable
fun MapaSendero(
    centro: Punto,
    modifier: Modifier = Modifier,
    zoom: Double = 16.5,
    marcadores: List<MarcadorMapa> = emptyList(),
    poligonos: List<PoligonoMapa> = emptyList(),
    hayRed: Boolean = true,
    nota: String? = null,
    lineas: List<LineaMapa> = emptyList(),
    /** Toque en el mapa (elegir destino en A-18, zona en A-17). */
    onToque: ((Punto) -> Unit)? = null
) {
    val proveedor = SelectorMapa.proveedor(hayRed)
    Box(modifier) {
        when (proveedor) {
            // El SDK de MapBox se integra cuando el equipo tenga la llave; hasta entonces cae al respaldo.
            ProveedorMapa.MAPBOX, ProveedorMapa.BASE_OFFLINE -> MapaBase(centro, zoom, marcadores, poligonos, lineas, onToque)
        }
        Text(
            listOfNotNull(proveedor.etiqueta, nota).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = TextoSecundario,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Superficie)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun MapaBase(
    centro: Punto,
    zoom: Double,
    marcadores: List<MarcadorMapa>,
    poligonos: List<PoligonoMapa>,
    lineas: List<LineaMapa>,
    onToque: ((Punto) -> Unit)?
) {
    val contexto = LocalContext.current
    remember(contexto) { ConfiguracionMapaOffline.inicializar(contexto) }
    val toque = rememberUpdatedState(onToque)
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                        val f = toque.value ?: return false
                        f(Punto(p.latitude, p.longitude)); return true
                    }
                    override fun longPressHelper(p: GeoPoint): Boolean = false
                }))
            }
        },
        update = { mapa ->
            // Solo se recentra cuando cambia el centro, para no deshacer lo que la persona movió.
            val clave = Triple(centro.latitud, centro.longitud, zoom)
            if (mapa.tag != clave) {
                mapa.tag = clave
                mapa.controller.setZoom(zoom)
                mapa.controller.setCenter(GeoPoint(centro.latitud, centro.longitud))
            }
            mapa.overlays.removeAll { it is Marker || it is Polygon || it is Polyline }
            lineas.forEach { l ->
                mapa.overlays.add(Polyline(mapa).apply {
                    setPoints(l.puntos.map { GeoPoint(it.latitud, it.longitud) })
                    outlinePaint.color = l.color
                    outlinePaint.strokeWidth = 12f
                    if (l.punteada) outlinePaint.pathEffect = DashPathEffect(floatArrayOf(18f, 14f), 0f)
                    infoWindow = null
                })
            }
            poligonos.forEach { poligono ->
                mapa.overlays.add(Polygon(mapa).apply {
                    points = poligono.puntos.map { GeoPoint(it.latitud, it.longitud) }
                    fillPaint.color = colorDeRiesgo(poligono.nivel)
                    outlinePaint.color = colorDeRiesgo(poligono.nivel).let { Color.argb(220, Color.red(it), Color.green(it), Color.blue(it)) }
                    outlinePaint.strokeWidth = 3f
                    infoWindow = null
                    setOnClickListener { _, _, p ->
                        toque.value?.invoke(Punto(p.latitude, p.longitude))
                        toque.value != null
                    }
                })
            }
            marcadores.forEach { m ->
                mapa.overlays.add(Marker(mapa).apply {
                    position = GeoPoint(m.punto.latitud, m.punto.longitud)
                    title = m.titulo
                    infoWindow = null // sin globo: el título se usa para accesibilidad
                    setOnMarkerClickListener { _, _ -> false }
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
            }
            mapa.invalidate()
        }
    )
}

// Niveles con la paleta de la guía de estilo: ALTO rojo, MEDIO amarillo, BAJO lila (igual que ChipRiesgo).
private fun colorDeRiesgo(nivel: String): Int = when (nivel.uppercase()) {
    "ALTO" -> Color.argb(110, 0xD7, 0x33, 0x45)
    "MEDIO" -> Color.argb(110, 0xFD, 0xCA, 0x51)
    else -> Color.argb(110, 0xE5, 0xA1, 0xE5)
}
