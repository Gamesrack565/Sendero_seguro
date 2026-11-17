package com.escom.sendero_seguro.ui.pantallas.alerta

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.escom.sendero_seguro.emergencias.AlertaEmergencia
import com.escom.sendero_seguro.emergencias.CanalRecepcion
import com.escom.sendero_seguro.emergencias.EstadoAlertaE01
import com.escom.sendero_seguro.emergencias.EventoHistorialAlerta
import com.escom.sendero_seguro.emergencias.EstadoSeguimientoAtencion
import com.escom.sendero_seguro.emergencias.ResultadoClasificacionIA1
import com.escom.sendero_seguro.identidad.dominio.CancelacionMotivo
import com.escom.sendero_seguro.identidad.dominio.ResultadoCancelacion
import com.escom.sendero_seguro.identidad.dominio.Validaciones
import com.escom.sendero_seguro.identidad.plataforma.AutenticadorBiometrico
import com.escom.sendero_seguro.identidad.ui.EstadoCancelacion
import com.escom.sendero_seguro.reportes.TipoReporteDto
import com.escom.sendero_seguro.ui.componentes.BotonSendero
import com.escom.sendero_seguro.ui.componentes.Chip
import com.escom.sendero_seguro.ui.componentes.ChipCanal
import com.escom.sendero_seguro.ui.componentes.FilaDato
import com.escom.sendero_seguro.ui.componentes.MensajeError
import com.escom.sendero_seguro.ui.componentes.NotaPie
import com.escom.sendero_seguro.ui.componentes.OpcionRadio
import com.escom.sendero_seguro.ui.componentes.PantallaConPie
import com.escom.sendero_seguro.ui.componentes.PuntosPin
import com.escom.sendero_seguro.ui.componentes.TecladoPin
import com.escom.sendero_seguro.ui.componentes.TipoBoton
import com.escom.sendero_seguro.ui.componentes.TituloSeccion
import com.escom.sendero_seguro.ui.mapa.MapaSendero
import com.escom.sendero_seguro.ui.mapa.MarcadorMapa
import com.escom.sendero_seguro.ui.mapa.Punto
import com.escom.sendero_seguro.ui.modelo.CanalEnvio
import com.escom.sendero_seguro.ui.modelo.EstadoAlerta
import com.escom.sendero_seguro.ui.theme.Amarillo
import com.escom.sendero_seguro.ui.theme.Archivo
import com.escom.sendero_seguro.ui.theme.Blanco
import com.escom.sendero_seguro.ui.theme.Borde
import com.escom.sendero_seguro.ui.theme.Fondo
import com.escom.sendero_seguro.ui.theme.Lila
import com.escom.sendero_seguro.ui.theme.LilaTexto
import com.escom.sendero_seguro.ui.theme.LilaTinte
import com.escom.sendero_seguro.ui.theme.Malva
import com.escom.sendero_seguro.ui.theme.Rojo
import com.escom.sendero_seguro.ui.theme.RojoOscuro
import com.escom.sendero_seguro.ui.theme.RojoTexto
import com.escom.sendero_seguro.ui.theme.RojoTinte
import com.escom.sendero_seguro.ui.theme.Superficie
import com.escom.sendero_seguro.ui.theme.TextoMono
import com.escom.sendero_seguro.ui.theme.TextoSecundario
import com.escom.sendero_seguro.ui.theme.Tinta
import com.escom.sendero_seguro.ui.util.categoriaLegible
import com.escom.sendero_seguro.ui.util.distanciaTexto
import com.escom.sendero_seguro.ui.util.horaDe
import kotlinx.coroutines.launch

// Bloque 2 · Alerta: A-05 Cuenta regresiva, A-06 Describir con voz, A-07 Alerta activa, A-08 Cancelar
// (CU-11, CU-12, CU-13, CU-21, CU-22 · RF-5, RF-6, RF-7, RF-9, RF-11, RF-13). Sin barra inferior.

/** Canal de la alerta (E-05) para los chips. */
internal fun CanalRecepcion.aCanal(): CanalEnvio = when (this) {
    CanalRecepcion.INTERNET -> CanalEnvio.INTERNET
    CanalRecepcion.MALLA -> CanalEnvio.MALLA
    CanalRecepcion.SMS -> CanalEnvio.SMS
    CanalRecepcion.COLA_LOCAL -> CanalEnvio.EN_COLA
}

internal fun EstadoAlertaE01.aUi(): EstadoAlerta = EstadoAlerta.valueOf(name)

/** Abre el marcador con el número (no requiere permiso de llamadas). */
internal fun marcar(context: Context, numero: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$numero")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// ================================================================ A-05

/**
 * A-05. Pantalla roja de alto contraste. Durante la cuenta regresiva la persona elige el tipo con un toque,
 * lo dice con voz o cancela. Si no hace nada, la alerta sale como «genérica» (flujo 3a de CU-11).
 */
@Composable
fun PantallaCuentaRegresiva(
    segundos: Int?,
    precisionM: Float?,
    tipos: List<TipoReporteDto>,
    tipoElegido: Int?,
    onElegirTipo: (TipoReporteDto) -> Unit,
    onVoz: () -> Unit,
    onEnviarYa: () -> Unit,
    onCancelar: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(RojoOscuro).padding(horizontal = 20.dp)) {
        Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(84.dp).border(5.dp, Blanco, CircleShape).semantics { contentDescription = "Faltan ${segundos ?: 0} segundos" },
                contentAlignment = Alignment.Center
            ) {
                Text("${segundos ?: 0}", color = Blanco, fontFamily = Archivo, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Enviando alerta…", style = MaterialTheme.typography.titleLarge, color = Blanco)
                Text(
                    "Si no eliges nada se envía como genérica." + (precisionM?.let { " Ubicación lista (±${it.toInt()} m)." } ?: " Buscando tu ubicación…"),
                    style = MaterialTheme.typography.bodyMedium, color = Blanco.copy(alpha = 0.85f)
                )
            }
        }
        Text(
            "¿Qué está pasando?", style = MaterialTheme.typography.titleMedium, color = Blanco,
            modifier = Modifier.padding(top = 22.dp, bottom = 4.dp)
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            val grupos = tipos.filterNot { it.esGenerica }.groupBy { it.categoria ?: "OTRO" }
            val orden = listOf("FISICO", "SOCIAL", "DIGITAL", "OTRO")
            val categorias = grupos.keys.sortedBy { orden.indexOf(it).let { i -> if (i < 0) 9 else i } }
            categorias.forEachIndexed { indice, cat ->
                TituloSeccion(categoriaLegible(cat.takeIf { it != "OTRO" }), color = Blanco.copy(alpha = 0.8f))
                val celdas = grupos.getValue(cat).map<TipoReporteDto, (@Composable (Modifier) -> Unit)> { t ->
                    { m -> BotonTipo(t.nombre, t.id == tipoElegido, { onElegirTipo(t) }, m) }
                }.toMutableList()
                // «Decirlo con voz» va con la última categoría, como en el diseño.
                if (indice == categorias.lastIndex) celdas += { m -> BotonVoz(onVoz, m) }
                celdas.chunked(2).forEach { fila ->
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        fila.forEach { it(Modifier.weight(1f)) }
                        if (fila.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            if (tipos.none { !it.esGenerica }) {
                Text(
                    "No hay catálogo guardado en el teléfono. La alerta saldrá como genérica.",
                    style = MaterialTheme.typography.bodyMedium, color = Blanco.copy(alpha = 0.85f), modifier = Modifier.padding(top = 8.dp)
                )
                Row(Modifier.padding(top = 8.dp)) { BotonVoz(onVoz, Modifier.weight(1f)); Spacer(Modifier.weight(1f)) }
            }
        }
        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onEnviarYa,
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Superficie, contentColor = RojoTexto)
            ) { Text("ENVIAR YA", fontFamily = Archivo, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp) }
            OutlinedButton(
                onClick = onCancelar,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(2.dp, Blanco),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Blanco)
            ) { Text("Cancelar (PIN o huella)", style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
private fun BotonTipo(texto: String, elegido: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .heightIn(min = 76.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (elegido) RojoTinte else Superficie)
            .border(if (elegido) 3.dp else 0.dp, if (elegido) Blanco else Color.Transparent, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(texto, style = MaterialTheme.typography.titleSmall, color = Tinta)
    }
}

@Composable
private fun BotonVoz(onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .heightIn(min = 76.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Malva)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Outlined.Mic, contentDescription = null, tint = Blanco)
        Spacer(Modifier.width(8.dp))
        Text("Decirlo con voz", style = MaterialTheme.typography.titleSmall, color = Blanco)
    }
}

// ================================================================ A-06

/**
 * A-06. La alerta ya salió como genérica: lo que se diga se agrega después, sin retrasar el envío.
 * La voz se transcribe en el teléfono (máx. 60 s) y IA-1 sugiere el tipo y extrae qué, dónde, quién y cuándo.
 */
@Composable
fun PantallaDescribirVoz(
    canal: CanalEnvio,
    clasificar: (String) -> ResultadoClasificacionIA1,
    tipos: List<TipoReporteDto>,
    onUsar: (tipoId: Int, nombre: String, texto: String) -> Unit,
    onSoloTexto: (String) -> Unit,
    onOmitir: () -> Unit
) {
    val context = LocalContext.current
    var texto by rememberSaveable { mutableStateOf("") }
    var escuchando by remember { mutableStateOf(false) }
    var segundos by remember { mutableIntStateOf(0) }
    var sugerencia by remember { mutableStateOf<ResultadoClasificacionIA1?>(null) }
    var eligiendoOtro by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val reconocedor = remember { ReconocedorRelato(context) }

    DisposableEffect(Unit) { onDispose { reconocedor.detener(); reconocedor.liberar() } }
    LaunchedEffect(escuchando) {
        segundos = 0
        while (escuchando && segundos < 60) {
            kotlinx.coroutines.delay(1000); segundos++
        }
        if (escuchando) { reconocedor.detener(); escuchando = false }
    }

    fun iniciar() {
        error = null
        reconocedor.iniciar(
            onParcial = { texto = it.take(500) },
            onFinal = { final ->
                texto = final.take(500)
                escuchando = false
                if (texto.length >= 10) sugerencia = clasificar(texto)
            },
            onError = { escuchando = false; error = it }
        )
        escuchando = true
    }

    val permiso = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) iniciar() else error = "Sin permiso de micrófono. Puedes escribirlo."
    }

    Column(Modifier.fillMaxSize().background(Fondo)) {
        Column(Modifier.fillMaxWidth().background(RojoOscuro).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Alerta enviada", style = MaterialTheme.typography.titleLarge, color = Blanco, modifier = Modifier.weight(1f))
                ChipCanal(canal)
            }
            Text(
                "Ya pedimos ayuda como «genérica». Lo que digas se agrega a la alerta; no retrasa el envío.",
                style = MaterialTheme.typography.bodyMedium, color = Blanco.copy(alpha = 0.85f), modifier = Modifier.padding(top = 6.dp)
            )
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(Malva).clickable(role = Role.Button) {
                        if (escuchando) { reconocedor.detener(); escuchando = false }
                        else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) iniciar()
                        else permiso.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (escuchando) Icons.Outlined.Stop else Icons.Outlined.Mic,
                        contentDescription = if (escuchando) "Detener" else "Grabar", tint = Blanco, modifier = Modifier.size(30.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        if (escuchando) "Escuchando… 0:%02d".format(segundos) else "Toca para hablar (máx. 60 s)",
                        style = MaterialTheme.typography.titleSmall, color = Tinta
                    )
                    Text("Voz a texto en el teléfono; funciona sin internet.", style = MaterialTheme.typography.bodySmall, color = TextoSecundario)
                }
            }
            OutlinedTextField(
                value = texto,
                onValueChange = { texto = it.take(500); sugerencia = null },
                modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                placeholder = { Text("Lo que digas aparece aquí; también puedes escribirlo.") },
                textStyle = MaterialTheme.typography.bodyLarge,
                shape = RoundedCornerShape(14.dp),
                supportingText = { Text("${texto.length}/500") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Superficie, unfocusedContainerColor = Superficie,
                    unfocusedBorderColor = Borde, focusedBorderColor = Malva
                )
            )
            MensajeError(error)
            if (sugerencia == null && texto.length >= 10 && !escuchando) {
                BotonSendero("Analizar con IA", { sugerencia = clasificar(texto) }, tipo = TipoBoton.SECUNDARIO)
            }
            sugerencia?.let { s ->
                if (s.superaUmbral) TarjetaSugerencia(s)
                else NotaPie("La IA no está segura del tipo (${(s.confianza * 100).toInt()} %). Elige uno o envía solo el texto.")
            }
            if (eligiendoOtro) {
                TituloSeccion("Elige el tipo")
                tipos.filterNot { it.esGenerica }.chunked(2).forEach { fila ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        fila.forEach { t ->
                            OutlinedButton(
                                onClick = { onUsar(t.id, t.nombre, texto) },
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Borde),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = Superficie, contentColor = Tinta)
                            ) { Text(t.nombre, textAlign = TextAlign.Center) }
                        }
                        if (fila.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val s = sugerencia
            if (s != null && s.superaUmbral) {
                BotonSendero("Usar y agregar a la alerta", { onUsar(s.tipoSugeridoId, s.nombreTipoSugerido, texto) }, tipo = TipoBoton.MALVA)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BotonSendero("Elegir otro tipo", { eligiendoOtro = !eligiendoOtro }, Modifier.weight(1f), tipo = TipoBoton.SECUNDARIO)
                BotonSendero(
                    if (texto.isBlank()) "Omitir" else "Solo enviar texto",
                    { if (texto.isBlank()) onOmitir() else onSoloTexto(texto) },
                    Modifier.weight(1f), tipo = TipoBoton.CONTORNO
                )
            }
        }
    }
}

@Composable
private fun TarjetaSugerencia(s: ResultadoClasificacionIA1) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Superficie).border(1.5.dp, Lila, RoundedCornerShape(16.dp)).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip("Sugerencia IA · ${(s.confianza * 100).toInt()} %", LilaTinte, LilaTexto, icono = Icons.Outlined.AutoAwesome)
            Spacer(Modifier.width(8.dp))
            Text("${categoriaLegible(s.categoria)} · prioridad ${s.prioridadCatalogo}", style = MaterialTheme.typography.bodySmall, color = TextoSecundario)
        }
        Text(s.nombreTipoSugerido, style = MaterialTheme.typography.headlineSmall, color = Tinta, modifier = Modifier.padding(vertical = 8.dp))
        listOf("Qué" to s.que, "Dónde" to s.donde, "Quién" to s.quien, "Cuándo" to s.cuando)
            .filter { it.second.isNotBlank() }
            .forEachIndexed { i, (e, v) ->
                if (i > 0) HorizontalDivider(color = Borde)
                FilaDato(e, v)
            }
    }
}

/** Reconocimiento de voz del sistema, preferentemente en el teléfono. No busca palabras clave: solo transcribe. */
private class ReconocedorRelato(private val context: Context) {
    private var sr: SpeechRecognizer? = null
    private var acumulado = ""

    fun iniciar(onParcial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { onError("Este teléfono no tiene reconocimiento de voz. Escríbelo."); return }
        liberar()
        acumulado = ""
        sr = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onParcial)
                }
                override fun onResults(results: Bundle?) {
                    onFinal(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                }
                override fun onError(error: Int) {
                    onError(
                        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "No se entendió. Intenta otra vez o escríbelo."
                        else "No se pudo usar el micrófono. Puedes escribirlo."
                    )
                }
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
        }
    }

    fun detener() { runCatching { sr?.stopListening() } }
    fun liberar() { runCatching { sr?.destroy() }; sr = null }
}

// ================================================================ A-07

/** Lo que muestra A-07 además de la alerta del Módulo 2. */
data class RedAvisada(
    val contactos: Int,
    val circulo: String?,
    /** Avisos RED_APOYO del servidor: entregados y total. Null mientras no hay respuesta. */
    val avisos: Pair<Int, Int>? = null
)

/**
 * A-07. Seguimiento para quien emitió la alerta: estados en lenguaje llano con hora, unidad y distancia,
 * avisos a la red de apoyo y ubicación que se comparte cada 10 s.
 */
@Composable
fun PantallaAlertaActiva(
    alerta: AlertaEmergencia,
    seguimiento: EstadoSeguimientoAtencion?,
    historial: List<EventoHistorialAlerta>,
    ubicacion: Punto?,
    precisionM: Float?,
    red: RedAvisada,
    onLlamar911: () -> Unit,
    onYaEstoyASalvo: () -> Unit
) {
    val estado = (seguimiento?.estadoActual ?: alerta.estadoActual).aUi()
    val punto = ubicacion ?: Punto(alerta.latitudInicial, alerta.longitudInicial)
    Column(Modifier.fillMaxSize().background(Fondo)) {
        Column(Modifier.fillMaxWidth().background(Rojo).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(Blanco))
                Spacer(Modifier.width(8.dp))
                Text("Alerta activa", style = MaterialTheme.typography.titleLarge, color = Blanco, modifier = Modifier.weight(1f))
                Text(seguimiento?.folio ?: alerta.folio, style = TextoMono, color = Blanco)
            }
            Text(
                "${alerta.nombreTipoIncidente} · compartiendo tu ubicación cada 10 s",
                style = MaterialTheme.typography.bodyLarge, color = Blanco, modifier = Modifier.padding(top = 6.dp)
            )
        }
        Box(Modifier.fillMaxWidth().height(230.dp).clipToBounds()) {
            MapaSendero(
                centro = punto,
                modifier = Modifier.fillMaxSize(),
                marcadores = listOf(MarcadorMapa(punto, "Tu ubicación")),
                nota = precisionM?.let { "±${it.toInt()} m GNSS" }
            )
            val unidad = seguimiento?.unidadAsignadaClave
            if (unidad != null) {
                Chip(
                    unidad + (seguimiento.distanciaUnidadMetros?.let { " · ${distanciaTexto(it)}" } ?: ""),
                    Malva, Blanco, Modifier.align(Alignment.TopEnd).padding(12.dp)
                )
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(estado.textoLlano, style = MaterialTheme.typography.titleMedium, color = Tinta, modifier = Modifier.weight(1f))
                ChipCanal(alerta.canalRecepcion.aCanal())
            }
            LineaEstados(historial, seguimiento)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(LilaTinte).border(1.5.dp, Lila, RoundedCornerShape(14.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Groups, contentDescription = null, tint = Malva)
                Spacer(Modifier.width(12.dp))
                Text(
                    buildString {
                        val avisos = red.avisos
                        if (avisos != null) append("Red de apoyo avisada: ${avisos.first} de ${avisos.second} avisos entregados")
                        else append("Tu red de apoyo recibe el aviso: ${red.contactos} contacto${if (red.contactos == 1) "" else "s"}")
                        red.circulo?.let { append(" · círculo $it") }
                    },
                    style = MaterialTheme.typography.bodyMedium, color = Tinta
                )
            }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BotonSendero("Llamar 911", onLlamar911, Modifier.weight(1f), tipo = TipoBoton.OSCURO, icono = Icons.Outlined.Call)
            BotonSendero("Ya estoy a salvo", onYaEstoyASalvo, Modifier.weight(1f), tipo = TipoBoton.CONTORNO)
        }
    }
}

/** Línea de estados ENVIADA → ASIGNADA → ACUSADA → EN_ATENCION con la hora de cada uno (evento_alerta). */
@Composable
private fun LineaEstados(historial: List<EventoHistorialAlerta>, seguimiento: EstadoSeguimientoAtencion?) {
    val pasos = listOf(
        EstadoAlertaE01.ENVIADA to "Enviada",
        EstadoAlertaE01.ASIGNADA to "Asignada a una unidad",
        EstadoAlertaE01.ACUSADA to "Recibida por la unidad (acuse)",
        EstadoAlertaE01.EN_ATENCION to "En atención"
    )
    val horas = historial.associate { it.estadoNuevo to it.ocurridoEnMs }
    pasos.forEach { (estado, texto) ->
        val hora = horas[estado] ?: if (estado == EstadoAlertaE01.ACUSADA) seguimiento?.horaAcuseMs else null
        val detalle = when (estado) {
            EstadoAlertaE01.ENVIADA -> seguimiento?.corporacionNombre?.let { " a $it" }
            EstadoAlertaE01.ASIGNADA -> seguimiento?.unidadAsignadaClave?.let { u ->
                " $u" + (seguimiento.distanciaUnidadMetros?.let { " (${distanciaTexto(it)})" } ?: "")
            }
            else -> null
        }.orEmpty()
        val hecho = hora != null
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(14.dp).clip(CircleShape)
                    .background(if (hecho) Amarillo else Color.Transparent)
                    .border(2.dp, if (hecho) Amarillo else Lila, CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                texto + detalle,
                style = if (hecho) MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodyLarge,
                color = if (hecho) Tinta else TextoSecundario,
                modifier = Modifier.weight(1f)
            )
            Text(hora?.let { horaDe(it) } ?: "—", style = TextoMono, color = TextoSecundario)
        }
    }
}

// ================================================================ A-08

/**
 * A-08. Cancelación autenticada con motivo y PIN (o huella). Si el PIN no coincide la alerta sigue activa,
 * el intento se registra como posible coacción (flujo 3a de CU-21) y el mensaje es neutro.
 */
@Composable
fun PantallaCancelarAlerta(
    estado: EstadoCancelacion,
    motivos: List<CancelacionMotivo>,
    onMotivo: (CancelacionMotivo) -> Unit,
    onConfirmarPin: (String) -> Unit,
    onHuella: (Boolean) -> Unit,
    onVolver: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    LaunchedEffect(estado.resultado) { if (estado.resultado is ResultadoCancelacion.NoCancelada) pin = "" }
    val actividad = context as? FragmentActivity
    val huella = actividad != null && AutenticadorBiometrico.disponible(context)

    PantallaConPie(
        titulo = "Cancelar alerta",
        onVolver = onVolver,
        pie = {
            NotaPie("Si el PIN no coincide, la alerta sigue activa y el intento se registra. Al cancelar avisamos a la unidad y a tu red de apoyo.")
            BotonSendero(
                if (estado.procesando) "Cancelando…" else "Confirmar cancelación",
                { onConfirmarPin(pin) },
                habilitado = pin.length == 4 && estado.motivo != null && !estado.procesando
            )
        }
    ) {
        Text("Motivo", style = MaterialTheme.typography.labelMedium, color = Tinta)
        motivos.forEach { m -> OpcionRadio(m.etiqueta, estado.motivo == m, { onMotivo(m) }) }
        estado.retro.errores[Validaciones.MOTIVO]?.let { MensajeError(it) }
        Text(
            "Escribe tu PIN de cancelación", style = MaterialTheme.typography.titleSmall, color = Tinta,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PuntosPin(pin.length) }
        (estado.resultado as? ResultadoCancelacion.NoCancelada)?.let {
            Text(it.mensajeNeutro, style = MaterialTheme.typography.bodyMedium, color = Tinta, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        estado.retro.mensaje?.let { MensajeError(it, Modifier.fillMaxWidth()) }
        TecladoPin(
            pin = pin,
            onCambio = { pin = it },
            onHuella = if (huella) ({
                scope.launch { onHuella(AutenticadorBiometrico.autenticar(actividad!!, "Cancelar alerta")) }
            }) else null
        )
    }
}
