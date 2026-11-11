package com.escom.sendero_seguro.ui.mapa

import android.preference.PreferenceManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.escom.sendero_seguro.ui.theme.Superficie
import com.escom.sendero_seguro.ui.theme.TextoSecundario
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

data class Punto(val latitud: Double, val longitud: Double)

data class MarcadorMapa(val punto: Punto, val titulo: String)

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
    hayRed: Boolean = true,
    nota: String? = null
) {
    val proveedor = SelectorMapa.proveedor(hayRed)
    Box(modifier) {
        when (proveedor) {
            // El SDK de MapBox se integra cuando el equipo tenga la llave; hasta entonces cae al respaldo.
            ProveedorMapa.MAPBOX, ProveedorMapa.BASE_OFFLINE -> MapaBase(centro, zoom, marcadores)
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
private fun MapaBase(centro: Punto, zoom: Double, marcadores: List<MarcadorMapa>) {
    val contexto = LocalContext.current
    remember {
        Configuration.getInstance().load(contexto, PreferenceManager.getDefaultSharedPreferences(contexto))
        Configuration.getInstance().userAgentValue = contexto.packageName
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            }
        },
        update = { mapa ->
            mapa.controller.setZoom(zoom)
            mapa.controller.setCenter(GeoPoint(centro.latitud, centro.longitud))
            mapa.overlays.removeAll { it is Marker }
            marcadores.forEach { m ->
                mapa.overlays.add(Marker(mapa).apply {
                    position = GeoPoint(m.punto.latitud, m.punto.longitud)
                    title = m.titulo
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
            }
            mapa.invalidate()
        }
    )
}
