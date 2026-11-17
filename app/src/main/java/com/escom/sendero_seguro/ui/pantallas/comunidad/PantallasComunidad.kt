package com.escom.sendero_seguro.ui.pantallas.comunidad

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.escom.sendero_seguro.emergencias.ComparativaRutasP7
import com.escom.sendero_seguro.emergencias.OpcionRutaCalculada
import com.escom.sendero_seguro.emergencias.ResultadoClasificacionIA1
import com.escom.sendero_seguro.mapas.remoto.CapaRiesgoDto
import com.escom.sendero_seguro.mapas.remoto.ZonaMapaDto
import com.escom.sendero_seguro.reportes.EvidenciaReporteLocal
import com.escom.sendero_seguro.reportes.ReporteComunitarioPublicoDto
import com.escom.sendero_seguro.reportes.TipoReporteDto
import com.escom.sendero_seguro.ui.componentes.BotonSendero
import com.escom.sendero_seguro.ui.componentes.Chip
import com.escom.sendero_seguro.ui.componentes.ChipRiesgo
import com.escom.sendero_seguro.ui.componentes.MensajeError
import com.escom.sendero_seguro.ui.componentes.NotaPie
import com.escom.sendero_seguro.ui.componentes.PantallaConPie
import com.escom.sendero_seguro.ui.componentes.Segmentado
import com.escom.sendero_seguro.ui.componentes.Tarjeta
import com.escom.sendero_seguro.ui.componentes.TipoBoton
import com.escom.sendero_seguro.ui.mapa.LineaMapa
import com.escom.sendero_seguro.ui.mapa.MapaSendero
import com.escom.sendero_seguro.ui.mapa.MarcadorMapa
import com.escom.sendero_seguro.ui.mapa.PoligonoMapa
import com.escom.sendero_seguro.ui.mapa.Punto
import com.escom.sendero_seguro.ui.modelo.NivelRiesgo
import com.escom.sendero_seguro.ui.theme.Blanco
import com.escom.sendero_seguro.ui.theme.Borde
import com.escom.sendero_seguro.ui.theme.Fondo
import com.escom.sendero_seguro.ui.theme.Lila
import com.escom.sendero_seguro.ui.theme.LilaTexto
import com.escom.sendero_seguro.ui.theme.LilaTinte
import com.escom.sendero_seguro.ui.theme.Malva
import com.escom.sendero_seguro.ui.theme.Rojo
import com.escom.sendero_seguro.ui.theme.RojoTexto
import com.escom.sendero_seguro.ui.theme.RojoTinte
import com.escom.sendero_seguro.ui.theme.Superficie
import com.escom.sendero_seguro.ui.theme.TextoMono
import com.escom.sendero_seguro.ui.theme.TextoSecundario
import com.escom.sendero_seguro.ui.theme.Tinta
import com.escom.sendero_seguro.ui.util.categoriaLegible
import com.escom.sendero_seguro.ui.util.distanciaMetros
import com.escom.sendero_seguro.ui.util.distanciaTexto
import com.escom.sendero_seguro.ui.util.fechaHoraDe
import com.escom.sendero_seguro.ui.util.haceCuanto
import com.escom.sendero_seguro.ui.util.instanteMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

// Bloque 4 · Comunidad: A-15 Levantar reporte, A-16 Feed, A-17 Mapa de calor, A-18 Ruta segura
// (CU-28, CU-29, CU-32 a CU-36 · RF-19, RF-21 a RF-25).

private val ZONA_SERVICIO = Punto(19.5045, -99.1469) // ESCOM, Zacatenco: centro por omisión sin ubicación

/** Círculo aproximado de [radioM] metros alrededor de un punto (ubicación pública del reporte, ~150 m). */
internal fun circulo(centro: Punto, radioM: Double): List<Punto> = (0..36).map { i ->
    val a = Math.toRadians(i * 10.0)
    val dLat = radioM / 111_320.0 * Math.cos(a)
    val dLon = radioM / (111_320.0 * Math.cos(Math.toRadians(centro.latitud))) * Math.sin(a)
    Punto(centro.latitud + dLat, centro.longitud + dLon)
}

/** Anillo exterior de la geometría GeoJSON de una zona (Polygon o MultiPolygon). */
internal fun anillosDe(geometria: JsonObject): List<List<Punto>> {
    val tipo = (geometria["type"] as? JsonPrimitive)?.content
    val coords = geometria["coordinates"] as? JsonArray ?: return emptyList()
    fun anillo(a: JsonArray) = a.mapNotNull { p ->
        val par = p as? JsonArray ?: return@mapNotNull null
        val lon = (par.getOrNull(0) as? JsonPrimitive)?.content?.toDoubleOrNull()
        val lat = (par.getOrNull(1) as? JsonPrimitive)?.content?.toDoubleOrNull()
        if (lat != null && lon != null) Punto(lat, lon) else null
    }
    return when (tipo) {
        "Polygon" -> listOfNotNull((coords.firstOrNull() as? JsonArray)?.let(::anillo))
        "MultiPolygon" -> coords.mapNotNull { (it as? JsonArray)?.firstOrNull()?.jsonArray?.let(::anillo) }
        else -> emptyList()
    }
}

private fun nivelDe(texto: String): NivelRiesgo = runCatching { NivelRiesgo.valueOf(texto.uppercase()) }.getOrDefault(NivelRiesgo.BAJO)

// ================================================================ A-15

/** Resultado del envío de A-15 para el mensaje de confirmación. */
sealed interface EnvioReporte {
    data class Recibido(val folio: String, val estado: String) : EnvioReporte
    data object EnCola : EnvioReporte
    data class Error(val mensaje: String) : EnvioReporte
}

/**
 * A-15. Reporte no urgente con sugerencia de tipo por IA-1, ubicación que se publica aproximada, fotos opcionales
 * y modalidad anónima protegida. Sin conexión entra a la cola local (CU-26).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PantallaLevantarReporte(
    tipos: List<TipoReporteDto>,
    ubicacion: Punto?,
    precisionM: Float?,
    clasificar: (String) -> ResultadoClasificacionIA1,
    guardarFoto: suspend (android.net.Uri) -> EvidenciaReporteLocal,
    onEnviar: suspend (tipoId: Int, descripcion: String, anonimo: Boolean, fotos: List<EvidenciaReporteLocal>) -> EnvioReporte,
    onListo: () -> Unit,
    onVolver: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var descripcion by rememberSaveable { mutableStateOf("") }
    var tipoId by rememberSaveable { mutableStateOf<Int?>(null) }
    var verTodos by rememberSaveable { mutableStateOf(false) }
    var anonimo by rememberSaveable { mutableStateOf(true) }
    var fotos by remember { mutableStateOf<List<EvidenciaReporteLocal>>(emptyList()) }
    var sugerencia by remember { mutableStateOf<ResultadoClasificacionIA1?>(null) }
    var enviando by remember { mutableStateOf(false) }
    var resultado by remember { mutableStateOf<EnvioReporte?>(null) }
    var errorFoto by remember { mutableStateOf<String?>(null) }

    // IA-1 sugiere el tipo cuando la persona deja de escribir.
    LaunchedEffect(descripcion) {
        if (descripcion.trim().length < 10) { sugerencia = null; return@LaunchedEffect }
        delay(700)
        val s = clasificar(descripcion)
        sugerencia = s.takeIf { it.superaUmbral && tipos.any { t -> t.id == it.tipoSugeridoId } }
        if (tipoId == null) sugerencia?.let { tipoId = it.tipoSugeridoId }
    }

    val selector = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        errorFoto = null
        scope.launch {
            for (uri in uris.take(3 - fotos.size)) {
                try {
                    val f = guardarFoto(uri)
                    if (f.archivo.length() > 5L * 1024 * 1024) { f.archivo.delete(); errorFoto = "Cada foto puede pesar hasta 5 MB." }
                    else fotos = fotos + f
                } catch (e: Exception) {
                    errorFoto = e.localizedMessage ?: "No se pudo adjuntar la foto."
                }
            }
            if (uris.size > 3 - fotos.size && fotos.size == 3) errorFoto = "Puedes adjuntar hasta 3 fotos."
        }
    }

    resultado?.takeIf { it !is EnvioReporte.Error }?.let { r ->
        PantallaConPie(titulo = "Nuevo reporte", onVolver = onListo, pie = { BotonSendero("Ver reportes", onListo) }) {
            Spacer(Modifier.height(24.dp))
            Text(
                if (r is EnvioReporte.Recibido) "Reporte enviado" else "Guardado en pendientes",
                style = MaterialTheme.typography.headlineSmall, color = Tinta
            )
            Text(
                if (r is EnvioReporte.Recibido) {
                    if (r.estado == "PUBLICADO") "Folio ${r.folio}. Ya está publicado en el feed con ubicación aproximada."
                    else "Folio ${r.folio}. Queda ${r.estado} hasta que el Administrador lo revise; después se publica con ubicación aproximada."
                }
                else "No hay señal. Se enviará solo al recuperar internet; puedes verlo en Conectividad y pendientes.",
                style = MaterialTheme.typography.bodyLarge, color = Tinta
            )
        }
        return
    }

    PantallaConPie(
        titulo = "Nuevo reporte",
        onVolver = onVolver,
        pie = {
            MensajeError((resultado as? EnvioReporte.Error)?.mensaje)
            BotonSendero(
                if (enviando) "Enviando…" else "Enviar reporte",
                {
                    val t = tipoId ?: return@BotonSendero
                    enviando = true
                    scope.launch {
                        resultado = onEnviar(t, descripcion.trim(), anonimo, fotos)
                        enviando = false
                    }
                },
                habilitado = !enviando && descripcion.trim().length in 10..500 && tipoId != null && ubicacion != null
            )
        }
    ) {
        Text("¿Qué viste? (qué, dónde, quién, cuándo)", style = MaterialTheme.typography.labelMedium, color = Tinta)
        OutlinedTextField(
            value = descripcion,
            onValueChange = { descripcion = it.take(500) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = RoundedCornerShape(14.dp),
            supportingText = { Text(if (descripcion.trim().length < 10) "Mínimo 10 caracteres" else "${descripcion.length}/500") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Superficie, unfocusedContainerColor = Superficie, unfocusedBorderColor = Borde, focusedBorderColor = Malva
            )
        )

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Superficie).border(1.5.dp, Lila, RoundedCornerShape(16.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val s = sugerencia
            if (s != null) Chip("Tipo sugerido · ${(s.confianza * 100).toInt()} %", LilaTinte, LilaTexto, icono = Icons.Outlined.AutoAwesome)
            else Text("Tipo", style = MaterialTheme.typography.labelMedium, color = Tinta)
            val visibles = when {
                verTodos || s == null -> tipos
                else -> tipos.filter { it.id == s.tipoSugeridoId } + tipos.filter { it.id != s.tipoSugeridoId && it.categoria == tipos.firstOrNull { t -> t.id == s.tipoSugeridoId }?.categoria }.take(1)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                visibles.forEach { t -> ChipOpcion(t.nombre, t.id == tipoId) { tipoId = t.id } }
                if (!verTodos && s != null) ChipOpcion("Otro…", false) { verTodos = true }
            }
        }

        Text("Ubicación", style = MaterialTheme.typography.labelMedium, color = Tinta)
        Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp)).clipToBounds()) {
            if (ubicacion != null) {
                MapaSendero(
                    centro = ubicacion, modifier = Modifier.fillMaxSize(), zoom = 16.0,
                    poligonos = listOf(PoligonoMapa(circulo(ubicacion, 150.0), "BAJO")),
                    nota = "Se publica con ubicación aproximada (~150 m)"
                )
            } else {
                Box(Modifier.fillMaxSize().background(Borde), contentAlignment = Alignment.Center) {
                    Text("Buscando tu ubicación…", color = TextoSecundario)
                }
            }
        }

        Text("Fotos (opcional)", style = MaterialTheme.typography.labelMedium, color = Tinta)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            fotos.forEach { f ->
                Box(Modifier.size(76.dp).clip(RoundedCornerShape(12.dp)).background(Tinta)) {
                    val img = remember(f.archivo) { runCatching { BitmapFactory.decodeFile(f.archivo.path, BitmapFactory.Options().apply { inSampleSize = 8 }) }.getOrNull() }
                    if (img != null) Image(img.asImageBitmap(), contentDescription = "Foto adjunta", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Icon(
                        Icons.Outlined.Close, contentDescription = "Quitar foto", tint = Blanco,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp).clickable { f.archivo.delete(); fotos = fotos - f }
                    )
                }
            }
            if (fotos.size < 3) {
                Box(
                    Modifier.size(76.dp).clip(RoundedCornerShape(12.dp)).border(1.5.dp, Borde, RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) { selector.launch(arrayOf("image/jpeg", "image/png")) },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Outlined.Add, contentDescription = "Agregar foto", tint = TextoSecundario) }
            }
        }
        MensajeError(errorFoto)

        Segmentado(listOf(false to "Con mi nombre", true to "Anónimo"), anonimo, { anonimo = it })
        Text(
            if (anonimo) "Anónimo protegido: nadie ve tu nombre; solo el Administrador puede consultarlo, con justificación y registro, si el reporte es abusivo."
            else "Tu nombre aparecerá en el reporte publicado.",
            style = MaterialTheme.typography.bodySmall, color = TextoSecundario
        )
    }
}

@Composable
private fun ChipOpcion(texto: String, elegido: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (elegido) LilaTinte else Superficie)
            .border(1.5.dp, if (elegido) Malva else Borde, RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)
    ) { Text(texto, style = MaterialTheme.typography.labelMedium, color = if (elegido) LilaTexto else Tinta) }
}

// ================================================================ A-16

/** A-16. Reportes publicados, filtrables por cercanía, categoría y periodo. Nunca muestra al autor anónimo ni la ubicación exacta. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PantallaFeed(
    reportes: List<ReporteComunitarioPublicoDto>,
    desdeCache: Boolean,
    guardadoEnMs: Long?,
    cargando: Boolean,
    error: String?,
    miUbicacion: Punto?,
    onCargar: () -> Unit,
    onNuevo: () -> Unit
) {
    LaunchedEffect(Unit) { onCargar() }
    var cerca by rememberSaveable { mutableStateOf(false) }
    var categoria by rememberSaveable { mutableStateOf<String?>(null) }
    var dias by rememberSaveable { mutableStateOf(7) }
    val ahora = System.currentTimeMillis()
    val filtrados = reportes.filter { r ->
        val enPeriodo = instanteMs(r.publicadoEn)?.let { ahora - it <= dias * 86_400_000L } ?: true
        val enCategoria = categoria == null || r.categoria.equals(categoria, ignoreCase = true)
        val cercano = !cerca || run {
            val d = r.distanciaM ?: miUbicacion?.let { distanciaMetros(it, Punto(r.latitud, r.longitud)) }
            d == null || d <= 2_000
        }
        enPeriodo && enCategoria && cercano
    }.sortedByDescending { instanteMs(it.publicadoEn) ?: 0 }

    Box(Modifier.fillMaxSize().background(Fondo)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Reportes de la comunidad", style = MaterialTheme.typography.headlineSmall, color = Tinta, modifier = Modifier.padding(top = 20.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Filtro("Cerca de mí", cerca) { cerca = !cerca }
                listOf("FISICO", "SOCIAL", "DIGITAL").forEach { c -> Filtro(categoriaLegible(c), categoria == c) { categoria = if (categoria == c) null else c } }
                Filtro("$dias días", true) { dias = if (dias == 7) 30 else 7 }
            }
            if (desdeCache && guardadoEnMs != null) NotaPie("Sin conexión: reportes guardados el ${fechaHoraDe(guardadoEnMs)}.")
            MensajeError(error)
            if (cargando && reportes.isEmpty()) NotaPie("Cargando reportes…")
            if (!cargando && filtrados.isEmpty()) {
                Spacer(Modifier.height(40.dp))
                NotaPie(if (reportes.isEmpty()) "Todavía no hay reportes publicados." else "No hay reportes con estos filtros.")
            }
            filtrados.forEach { r -> TarjetaReporte(r) }
            Spacer(Modifier.height(90.dp))
        }
        ExtendedFloatingActionButton(
            onClick = onNuevo,
            containerColor = Rojo, contentColor = Blanco,
            icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
            text = { Text("Nuevo reporte", style = MaterialTheme.typography.labelLarge) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        )
    }
}

@Composable
private fun Filtro(texto: String, activo: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (activo) Tinta else Superficie)
            .border(1.dp, if (activo) Tinta else Borde, RoundedCornerShape(50))
            .clickable(role = Role.Checkbox, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)
    ) { Text(texto, style = MaterialTheme.typography.labelMedium, color = if (activo) Blanco else Tinta) }
}

@Composable
private fun TarjetaReporte(r: ReporteComunitarioPublicoDto) {
    Tarjeta {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (fondo, color) = when (r.categoria?.uppercase()) {
                "FISICO" -> RojoTinte to RojoTexto
                "SOCIAL" -> LilaTinte to LilaTexto
                else -> Borde to Tinta
            }
            Chip(categoriaLegible(r.categoria).uppercase(), fondo, color)
            Spacer(Modifier.weight(1f))
            Text(instanteMs(r.publicadoEn)?.let { haceCuanto(it) } ?: "", style = MaterialTheme.typography.bodySmall, color = TextoSecundario)
        }
        Text(r.tipo, style = MaterialTheme.typography.titleMedium, color = Tinta, modifier = Modifier.padding(top = 8.dp))
        Text(r.descripcion, style = MaterialTheme.typography.bodyLarge, color = Tinta, modifier = Modifier.padding(top = 4.dp))
        Text(
            listOfNotNull(r.referencia, r.autor).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = TextoSecundario, modifier = Modifier.padding(top = 6.dp)
        )
    }
}

// ================================================================ A-17

/**
 * A-17. Capa de riesgo histórica (30 días) o «Pronóstico IA» por franja horaria. Al tocar una zona se ve su
 * nivel y los motivos. Funciona sin conexión con la capa en caché.
 */
@Composable
fun PantallaMapaCalor(
    capa: CapaRiesgoDto?,
    desdeCacheEnMs: Long?,
    miUbicacion: Punto?,
    actualizando: Boolean,
    onActualizar: () -> Unit,
    onEvitar: (ZonaMapaDto) -> Unit
) {
    LaunchedEffect(Unit) { onActualizar() }
    var pronostico by rememberSaveable { mutableStateOf(false) }
    var franja by remember { mutableFloatStateOf(7f) }
    var zonaId by rememberSaveable { mutableStateOf<Long?>(null) }
    val zonas = capa?.zonas.orEmpty()
    val zona = zonas.firstOrNull { it.id == zonaId }
    val centro = miUbicacion ?: zonas.firstOrNull()?.let { Punto(it.centroLatitud, it.centroLongitud) } ?: ZONA_SERVICIO

    Column(Modifier.fillMaxSize().background(Fondo)) {
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            MapaSendero(
                centro = centro,
                modifier = Modifier.fillMaxSize(),
                zoom = 16.5,
                marcadores = listOfNotNull(miUbicacion?.let { MarcadorMapa(it, "Tú") }),
                poligonos = zonas.flatMap { z -> anillosDe(z.geometria).map { PoligonoMapa(it, z.nivel) } },
                nota = capa?.let { "Capa v${it.version}" + (desdeCacheEnMs?.let { " · en caché" } ?: "") },
                onToque = { p ->
                    zonaId = zonas.firstOrNull { z -> anillosDe(z.geometria).any { dentro(p, it) } }?.id
                        ?: zonas.minByOrNull { distanciaMetros(p, Punto(it.centroLatitud, it.centroLongitud)) }
                            ?.takeIf { distanciaMetros(p, Punto(it.centroLatitud, it.centroLongitud)) < 250 }?.id
                }
            )
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Segmentado(listOf(false to "Reportes (30 días)", true to "Pronóstico IA"), pronostico, { pronostico = it })
                if (pronostico) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Superficie).padding(12.dp)) {
                        Row {
                            Text("Franja horaria", style = MaterialTheme.typography.labelMedium, color = Tinta, modifier = Modifier.weight(1f))
                            val h = franja.toInt() * 3
                            Text("%02d:00–%02d:00".format(h, h + 3), style = TextoMono, color = Tinta)
                        }
                        Slider(
                            value = franja, onValueChange = { franja = it }, valueRange = 0f..7f, steps = 6,
                            colors = SliderDefaults.colors(thumbColor = Malva, activeTrackColor = Malva)
                        )
                        Text(
                            "El pronóstico IA-2 todavía no está publicado. Se muestra la capa de reportes de 30 días.",
                            style = MaterialTheme.typography.bodySmall, color = TextoSecundario
                        )
                    }
                }
            }
            Column(
                Modifier.align(Alignment.TopEnd).padding(top = if (pronostico) 210.dp else 80.dp, end = 12.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Superficie).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(NivelRiesgo.ALTO to Rojo, NivelRiesgo.MEDIO to com.escom.sendero_seguro.ui.theme.Amarillo, NivelRiesgo.BAJO to Lila).forEach { (n, c) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(c))
                        Spacer(Modifier.width(6.dp))
                        Text(n.name, style = MaterialTheme.typography.labelSmall, color = Tinta)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(Superficie).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                zona != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChipRiesgo(nivelDe(zona.nivel))
                        Spacer(Modifier.width(10.dp))
                        Text("Zona ${zona.id}", style = MaterialTheme.typography.titleMedium, color = Tinta)
                    }
                    Text(
                        "Riesgo ${zona.nivel.lowercase()} por ${zona.numReportes} reporte${if (zona.numReportes == 1) "" else "s"} en ${zona.periodoDias} días (densidad ${"%.2f".format(zona.densidadPonderada)}).",
                        style = MaterialTheme.typography.bodyMedium, color = Tinta
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BotonSendero("Evitar en mi ruta", { onEvitar(zona) }, Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        Text(capa?.let { "Capa v${it.version}" + (desdeCacheEnMs?.let { " · en caché sin conexión" } ?: "") } ?: "", style = MaterialTheme.typography.bodySmall, color = TextoSecundario, modifier = Modifier.weight(1f))
                    }
                }
                capa == null -> NotaPie(if (actualizando) "Descargando la capa de riesgo…" else "No hay capa de riesgo en el teléfono. Conéctate para descargarla.")
                zonas.isEmpty() -> NotaPie("No hay zonas de riesgo publicadas en los últimos 30 días.")
                else -> NotaPie("Toca una zona para ver su nivel y los motivos. ${capa.leyenda}")
            }
        }
    }
}

/** Punto dentro de un anillo (ray casting). */
private fun dentro(p: Punto, anillo: List<Punto>): Boolean {
    var c = false
    var j = anillo.lastIndex
    for (i in anillo.indices) {
        val a = anillo[i]; val b = anillo[j]
        if ((a.latitud > p.latitud) != (b.latitud > p.latitud) &&
            p.longitud < (b.longitud - a.longitud) * (p.latitud - a.latitud) / (b.latitud - a.latitud) + a.longitud
        ) c = !c
        j = i
    }
    return c
}

// ================================================================ A-18

/**
 * A-18. Compara la ruta segura con la más corta y explica por qué se recomienda; indica si se calculó en el
 * teléfono (sin conexión). Por RNF10 la ubicación no se comparte fuera de una alerta activa.
 */
@Composable
fun PantallaRutaSegura(
    origen: Punto?,
    precisionM: Float?,
    destino: Punto?,
    nombreDestino: String,
    rutas: ComparativaRutasP7?,
    calculando: Boolean,
    error: String?,
    siguiendo: Boolean,
    restanteM: Double?,
    onNombreDestino: (String) -> Unit,
    onDestino: (Punto) -> Unit,
    onIniciar: () -> Unit,
    onTerminar: () -> Unit,
    onVolver: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Fondo)) {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onVolver) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Tinta)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CampoRuta(if (origen != null) "Mi ubicación" + (precisionM?.let { " (±${it.toInt()} m)" } ?: "") else "Buscando tu ubicación…", false)
                OutlinedTextField(
                    value = nombreDestino, onValueChange = onNombreDestino,
                    placeholder = { Text("Destino: toca el mapa") },
                    singleLine = true, textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = LilaTinte, unfocusedContainerColor = LilaTinte, unfocusedBorderColor = Lila, focusedBorderColor = Malva
                    )
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp).clipToBounds()) {
            val segura = rutas?.rutaSeguraRecomendada
            val corta = rutas?.rutaMasCorta
            MapaSendero(
                centro = origen ?: destino ?: ZONA_SERVICIO,
                modifier = Modifier.fillMaxSize(),
                zoom = 16.0,
                marcadores = listOfNotNull(origen?.let { MarcadorMapa(it, "Origen") }, destino?.let { MarcadorMapa(it, nombreDestino.ifBlank { "Destino" }) }),
                lineas = listOfNotNull(
                    corta?.let { LineaMapa(it.nodos.map { n -> Punto(n.latitud, n.longitud) }, Malva.toArgb(), punteada = true) },
                    segura?.let { LineaMapa(it.nodos.map { n -> Punto(n.latitud, n.longitud) }, Malva.toArgb()) }
                ),
                nota = if (rutas != null) "Calculada en el teléfono · sin conexión" else "Toca el mapa para elegir el destino",
                onToque = if (siguiendo) null else onDestino
            )
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MensajeError(error)
            if (calculando) NotaPie("Calculando rutas…")
            rutas?.let { c ->
                TarjetaRuta(c.rutaSeguraRecomendada, recomendada = true)
                TarjetaRuta(c.rutaMasCorta, recomendada = false)
            }
            if (siguiendo) {
                Text(
                    restanteM?.let { "Te faltan ${distanciaTexto(it)} · sigue la línea continua" } ?: "Siguiendo la ruta…",
                    style = MaterialTheme.typography.titleSmall, color = Tinta, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                )
                BotonSendero("Terminar", onTerminar, tipo = TipoBoton.CONTORNO)
            } else {
                BotonSendero("Iniciar ruta segura", onIniciar, habilitado = rutas != null)
            }
        }
    }
}

@Composable
private fun CampoRuta(texto: String, destacado: Boolean) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (destacado) LilaTinte else Superficie)
            .border(1.dp, if (destacado) Lila else Borde, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 12.dp)
    ) { Text(texto, style = MaterialTheme.typography.bodyMedium, color = Tinta) }
}

@Composable
private fun TarjetaRuta(r: OpcionRutaCalculada, recomendada: Boolean) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (recomendada) LilaTinte else Superficie)
            .border(1.5.dp, if (recomendada) Lila else Borde, RoundedCornerShape(16.dp)).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (recomendada) "Ruta segura" else "Más corta", style = MaterialTheme.typography.titleMedium, color = Tinta)
            if (recomendada) {
                Spacer(Modifier.width(8.dp))
                Text("RECOMENDADA", style = MaterialTheme.typography.labelSmall, color = LilaTexto)
            }
            Spacer(Modifier.weight(1f))
            Text("${r.tiempoEstimadoMin} min", style = MaterialTheme.typography.titleMedium, color = Tinta)
        }
        val km = "%.1f km".format(r.distanciaTotalKm)
        if (recomendada) {
            Text(
                listOf(km, if (r.advertenciasZonasRiesgo.isEmpty()) "evita las zonas de riesgo" else "riesgo promedio ${"%.2f".format(r.puntajeRiesgoPromedio)}").joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = Tinta
            )
        } else {
            Text(
                r.advertenciasZonasRiesgo.firstOrNull() ?: "$km · sin zonas de riesgo en el trayecto",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (r.advertenciasZonasRiesgo.isEmpty()) FontWeight.Normal else FontWeight.Bold),
                color = if (r.advertenciasZonasRiesgo.isEmpty()) TextoSecundario else RojoTexto
            )
        }
    }
}
