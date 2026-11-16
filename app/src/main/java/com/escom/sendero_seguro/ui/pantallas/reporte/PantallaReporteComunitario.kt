package com.escom.sendero_seguro.ui.pantallas.reporte

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteException
import android.location.Location
import android.location.LocationManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.escom.sendero_seguro.identidad.datos.remoto.ClienteHttp
import com.escom.sendero_seguro.reportes.AvisoReporteSincronizado
import com.escom.sendero_seguro.reportes.AvisoReporteUsuario
import com.escom.sendero_seguro.reportes.EvidenciaReporteLocal
import com.escom.sendero_seguro.reportes.MAX_EVIDENCIA_REPORTE_BYTES
import com.escom.sendero_seguro.reportes.ModuloReportes
import com.escom.sendero_seguro.reportes.ErrorReporteDto
import com.escom.sendero_seguro.reportes.ReporteEntradaDto
import com.escom.sendero_seguro.reportes.ReporteRecibidoDto
import com.escom.sendero_seguro.reportes.ResultadoEnvioReporte
import com.escom.sendero_seguro.reportes.TipoReporteDto
import com.escom.sendero_seguro.reportes.crearArchivoAudioReporte
import com.escom.sendero_seguro.reportes.guardarEvidenciaReporte
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID

private const val MAX_EVIDENCIAS_POR_REPORTE = 3

@Composable
fun PantallaReporteComunitario(
    moduloReportes: ModuloReportes,
    prepararSesion: suspend () -> String?,
    onVolver: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val avisoEnVivo by AvisoReporteUsuario.ultimoAviso.collectAsState()
    var avisoGuardado by remember { mutableStateOf<AvisoReporteSincronizado?>(null) }
    var intentoCargaCatalogo by remember { mutableStateOf(0) }
    var versionCatalogoId by remember { mutableStateOf<Int?>(null) }
    var tipos by remember { mutableStateOf<List<TipoReporteDto>>(emptyList()) }
    var catalogoDesdeCache by remember { mutableStateOf(false) }
    var tipoSeleccionado by remember { mutableStateOf<TipoReporteDto?>(null) }
    var catalogoCargando by remember { mutableStateOf(true) }
    var descripcion by remember { mutableStateOf("") }
    var evidencias by remember { mutableStateOf<List<EvidenciaReporteLocal>>(emptyList()) }
    var cargandoEvidencias by remember { mutableStateOf(false) }
    var grabadoraAudio by remember { mutableStateOf<MediaRecorder?>(null) }
    var archivoAudioGrabando by remember { mutableStateOf<File?>(null) }
    var grabandoAudio by remember { mutableStateOf(false) }
    var latitud by remember { mutableStateOf("") }
    var longitud by remember { mutableStateOf("") }
    var precisionM by remember { mutableStateOf<Double?>(null) }
    var esAnonimo by remember { mutableStateOf(false) }
    var idReporte by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var creadoEn by remember { mutableStateOf(Instant.now().toString()) }
    var menuTiposAbierto by remember { mutableStateOf(false) }
    var ubicacionMensaje by remember { mutableStateOf<String?>(null) }
    var cargandoUbicacion by remember { mutableStateOf(false) }
    var enviando by remember { mutableStateOf(false) }
    var envioIntentado by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var resultado by remember { mutableStateOf<ReporteRecibidoDto?>(null) }
    var reporteEnCola by remember { mutableStateOf(false) }
    var jsonEnviado by remember { mutableStateOf<String?>(null) }
    var payloadEnviado by remember { mutableStateOf<ReporteEntradaDto?>(null) }

    fun detenerGrabacionAudio() {
        val grabadora = grabadoraAudio ?: return
        val archivo = archivoAudioGrabando
        grabadoraAudio = null
        archivoAudioGrabando = null
        grabandoAudio = false

        var errorAlCerrar: RuntimeException? = null
        try {
            grabadora.stop()
        } catch (e: RuntimeException) {
            errorAlCerrar = e
        } finally {
            grabadora.release()
        }

        val tamano = archivo?.length() ?: 0L
        if (archivo != null && tamano in 1..MAX_EVIDENCIA_REPORTE_BYTES &&
            evidencias.size < MAX_EVIDENCIAS_POR_REPORTE
        ) {
            evidencias = evidencias + EvidenciaReporteLocal(archivo, "audio/mp4", archivo.name)
            error = if (errorAlCerrar == null) null else "La grabación se cerró al alcanzar el límite de tamaño."
        } else {
            val noSePudoBorrar = archivo?.let { it.exists() && !it.delete() } == true
            error = when {
                noSePudoBorrar -> "La grabación no se pudo guardar y tampoco se pudo limpiar el archivo temporal."
                tamano > MAX_EVIDENCIA_REPORTE_BYTES -> "El audio superó el límite de 10 MB y se descartó."
                evidencias.size >= MAX_EVIDENCIAS_POR_REPORTE -> "Ya se alcanzó el límite de evidencias del reporte."
                else -> "La grabación fue demasiado corta o no se pudo finalizar. Intenta grabar otra vez."
            }
        }
    }

    fun iniciarGrabacionAudio() {
        if (evidencias.size >= MAX_EVIDENCIAS_POR_REPORTE) {
            error = "Puedes adjuntar hasta $MAX_EVIDENCIAS_POR_REPORTE evidencias por reporte."
            return
        }
        val archivo = try {
            crearArchivoAudioReporte(context)
        } catch (e: IOException) {
            error = e.localizedMessage ?: "No se pudo preparar el archivo de audio."
            return
        } catch (_: SecurityException) {
            error = "No se pudo acceder al almacenamiento privado para guardar el audio."
            return
        }
        @Suppress("DEPRECATION")
        val grabadora = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        try {
            grabadora.setAudioSource(MediaRecorder.AudioSource.MIC)
            grabadora.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            grabadora.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            grabadora.setAudioEncodingBitRate(64_000)
            grabadora.setOutputFile(archivo.absolutePath)
            grabadora.setMaxFileSize(MAX_EVIDENCIA_REPORTE_BYTES)
            grabadora.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    scope.launch { detenerGrabacionAudio() }
                }
            }
            grabadora.prepare()
            grabadora.start()
            grabadoraAudio = grabadora
            archivoAudioGrabando = archivo
            grabandoAudio = true
            error = null
        } catch (e: Exception) {
            grabadora.release()
            if (archivo.exists() && !archivo.delete()) {
                error = "No se pudo iniciar la grabación ni limpiar el archivo temporal."
            } else {
                error = e.localizedMessage ?: "No se pudo iniciar la grabación. Revisa el permiso del micrófono."
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            grabadoraAudio?.let { grabadora ->
                try {
                    grabadora.stop()
                } catch (_: RuntimeException) {
                    Log.w("ReporteComunitario", "La grabación se interrumpió al salir del formulario.")
                } finally {
                    grabadora.release()
                }
            }
            archivoAudioGrabando?.let { archivo ->
                if (archivo.exists() && !archivo.delete()) {
                    Log.e("ReporteComunitario", "No se pudo limpiar el audio temporal al salir del formulario.")
                }
            }
        }
    }

    fun actualizarUbicacion(ubicacion: Location?, esActual: Boolean) {
        if (ubicacion == null) {
            ubicacionMensaje = "No hay una ubicación reciente. Ingresa las coordenadas manualmente."
        } else {
            latitud = ubicacion.latitude.toString()
            longitud = ubicacion.longitude.toString()
            precisionM = ubicacion.accuracy.takeIf { it > 0 }?.toDouble()
            ubicacionMensaje = if (esActual) {
                "Ubicación actual obtenida del dispositivo."
            } else {
                "Se usó la última ubicación conocida; revisa las coordenadas antes de enviar."
            }
        }
        cargandoUbicacion = false
    }

    fun leerUbicacion() {
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val fine = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val proveedores = if (fine) {
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            } else {
                listOf(LocationManager.NETWORK_PROVIDER)
            }
            val proveedor = proveedores.firstOrNull { locationManager.isProviderEnabled(it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && proveedor != null) {
                locationManager.getCurrentLocation(proveedor, null, context.mainExecutor) {
                    actualizarUbicacion(it, esActual = true)
                }
            } else {
                actualizarUbicacion(obtenerUltimaUbicacion(context), esActual = false)
            }
        } catch (e: SecurityException) {
            cargandoUbicacion = false
            ubicacionMensaje = "No se pudo acceder a la ubicación. Revisa el permiso o escribe las coordenadas."
        }
    }

    val permisosUbicacion = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permisos ->
        val concedido = permisos[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permisos[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (concedido) leerUbicacion()
        else {
            cargandoUbicacion = false
            ubicacionMensaje = "Sin permiso de ubicación. Puedes escribir las coordenadas manualmente."
        }
    }

    val permisoMicrofono = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->
        if (concedido) iniciarGrabacionAudio()
        else error = "Se necesita permiso de micrófono para grabar el audio."
    }

    val selectorEvidencias = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val cupo = MAX_EVIDENCIAS_POR_REPORTE - evidencias.size
        if (uris.size > cupo) {
            error = "Puedes adjuntar hasta $MAX_EVIDENCIAS_POR_REPORTE evidencias por reporte."
        }
        cargandoEvidencias = true
        scope.launch {
            val nuevas = mutableListOf<EvidenciaReporteLocal>()
            try {
                withContext(Dispatchers.IO) {
                    for (uri in uris.take(cupo.coerceAtLeast(0))) {
                        nuevas += guardarEvidenciaReporte(context, uri)
                    }
                }
                evidencias = evidencias + nuevas
            } catch (e: IOException) {
                val noSePudieronBorrar = nuevas.any { it.archivo.exists() && !it.archivo.delete() }
                error = if (noSePudieronBorrar) {
                    "${e.localizedMessage ?: "No se pudo guardar el archivo seleccionado."} " +
                        "No se pudieron limpiar todos los archivos parciales."
                } else {
                    e.localizedMessage ?: "No se pudo guardar el archivo seleccionado."
                }
            } catch (e: SecurityException) {
                val noSePudieronBorrar = nuevas.any { it.archivo.exists() && !it.archivo.delete() }
                error = if (noSePudieronBorrar) {
                    "No se pudo leer el archivo y no se pudieron limpiar todos los archivos parciales."
                } else {
                    "No se pudo leer el archivo seleccionado. Intenta adjuntarlo otra vez."
                }
            } finally {
                cargandoEvidencias = false
            }
        }
    }

    LaunchedEffect(moduloReportes, intentoCargaCatalogo) {
        avisoGuardado = AvisoReporteUsuario.leerAvisoReciente(context)
        catalogoCargando = true
        error = null
        try {
            val disponible = moduloReportes.cargarCatalogo()
            val catalogo = disponible.catalogo
            versionCatalogoId = catalogo.versionCatalogoId
            catalogoDesdeCache = disponible.desdeCache
            tipos = catalogo.tipos.filter { it.activo && (it.aplicaA == "REPORTE" || it.aplicaA == "AMBOS") }
            tipoSeleccionado = tipos.firstOrNull()
            if (tipos.isEmpty()) error = "El catálogo publicado no tiene tipos disponibles para reportar."
        } catch (e: HttpException) {
            error = "No se pudo cargar el catálogo: ${mensajeErrorServidor(e)}"
        } catch (e: IOException) {
            error = "No se pudo conectar para cargar el catálogo: ${e.localizedMessage ?: "error de red"}."
        } finally {
            catalogoCargando = false
        }
    }

    val latitudNumero = latitud.replace(',', '.').toDoubleOrNull()
    val longitudNumero = longitud.replace(',', '.').toDoubleOrNull()
    val ubicacionValida = latitudNumero != null && latitudNumero in -90.0..90.0 &&
        longitudNumero != null && longitudNumero in -180.0..180.0
    val puedeEnviar = !catalogoCargando && !enviando && !cargandoEvidencias && !grabandoAudio && !envioIntentado &&
        resultado == null && !reporteEnCola &&
        tipoSeleccionado != null && versionCatalogoId != null &&
        descripcion.trim().length in 10..2000 && ubicacionValida
    val borrador = if (tipoSeleccionado != null && versionCatalogoId != null &&
        latitudNumero != null && longitudNumero != null
    ) {
        ReporteEntradaDto(
            id = idReporte,
            tipoIncidenteId = tipoSeleccionado!!.id,
            versionCatalogoId = versionCatalogoId!!,
            descripcion = descripcion.trim(),
            latitud = latitudNumero,
            longitud = longitudNumero,
            precisionM = precisionM,
            esAnonimo = esAnonimo,
            creadoEn = creadoEn,
        )
    } else null
    val jsonVistaPrevia = remember(borrador, payloadEnviado) {
        (payloadEnviado ?: borrador)?.let {
            ClienteHttp.json.encodeToString(ReporteEntradaDto.serializer(), it)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onVolver, enabled = !enviando) { Text("Volver") }
            Text("Nuevo reporte comunitario", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "Describe el incidente y su ubicación. Las coordenadas exactas se envían para revisión; si se publica, el mapa usa una celda aproximada.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (catalogoCargando) {
            Text("Cargando tipos de incidente…")
        } else if (tipos.isNotEmpty()) {
            Column {
                Text("Tipo de incidente", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { menuTiposAbierto = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !enviando && !envioIntentado && resultado == null && !reporteEnCola,
                ) {
                    Text(tipoSeleccionado?.nombre ?: "Selecciona un tipo")
                }
                DropdownMenu(
                    expanded = menuTiposAbierto,
                    onDismissRequest = { menuTiposAbierto = false },
                ) {
                    tipos.forEach { tipo ->
                        DropdownMenuItem(
                            text = { Text(tipo.nombre) },
                            onClick = {
                                tipoSeleccionado = tipo
                                menuTiposAbierto = false
                            },
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = descripcion,
            onValueChange = { descripcion = it.take(2000) },
            label = { Text("Descripción (10–2000 caracteres)") },
            supportingText = { Text("${descripcion.trim().length}/2000") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
            enabled = resultado == null && !reporteEnCola && !enviando && !envioIntentado,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Evidencias (opcional)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Adjunta hasta 3 fotos de 10 MB cada una o graba un audio de hasta 10 MB. El audio no se transcribe.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = { selectorEvidencias.launch(arrayOf("image/*")) },
                    enabled = evidencias.size < MAX_EVIDENCIAS_POR_REPORTE &&
                        !cargandoEvidencias && !grabandoAudio && !enviando && !envioIntentado &&
                        resultado == null && !reporteEnCola,
                ) {
                    Text(if (cargandoEvidencias) "Guardando foto…" else "Adjuntar fotos")
                }
                OutlinedButton(
                    onClick = {
                        if (grabandoAudio) {
                            detenerGrabacionAudio()
                        } else if (ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            iniciarGrabacionAudio()
                        } else {
                            permisoMicrofono.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    enabled = !cargandoEvidencias && !enviando && !envioIntentado &&
                        resultado == null && !reporteEnCola &&
                        (grabandoAudio || evidencias.size < MAX_EVIDENCIAS_POR_REPORTE),
                ) {
                    Text(if (grabandoAudio) "Detener grabación" else "Grabar audio")
                }
                if (grabandoAudio) Text("Grabando desde el micrófono…")
                evidencias.forEach { evidencia ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${evidencia.nombre} · ${evidencia.mime} · ${evidencia.archivo.length() / 1024} KB",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!envioIntentado && resultado == null && !reporteEnCola) {
                            TextButton(
                                onClick = {
                                    if (evidencia.archivo.exists() && !evidencia.archivo.delete()) {
                                        error = "No se pudo quitar el archivo local."
                                    } else {
                                        evidencias = evidencias - evidencia
                                    }
                                },
                            ) {
                                Text("Quitar")
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = latitud,
                onValueChange = {
                    latitud = it
                    precisionM = null
                },
                label = { Text("Latitud") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                enabled = resultado == null && !reporteEnCola && !enviando && !envioIntentado,
                singleLine = true,
            )
            OutlinedTextField(
                value = longitud,
                onValueChange = {
                    longitud = it
                    precisionM = null
                },
                label = { Text("Longitud") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                enabled = resultado == null && !reporteEnCola && !enviando && !envioIntentado,
                singleLine = true,
            )
        }
        OutlinedButton(
            onClick = {
                val fine = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                val coarse = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (fine || coarse) {
                    cargandoUbicacion = true
                    leerUbicacion()
                } else {
                    cargandoUbicacion = true
                    permisosUbicacion.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    )
                }
            },
            enabled = resultado == null && !reporteEnCola && !enviando &&
                !envioIntentado && !cargandoUbicacion,
        ) {
            Text(if (cargandoUbicacion) "Obteniendo ubicación…" else "Usar ubicación del dispositivo")
        }
        ubicacionMensaje?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if ((latitud.isNotBlank() || longitud.isNotBlank()) && !ubicacionValida) {
            Text("Verifica que la latitud esté entre −90 y 90 y la longitud entre −180 y 180.")
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = esAnonimo,
                onCheckedChange = { esAnonimo = it },
                enabled = resultado == null && !reporteEnCola && !enviando && !envioIntentado,
            )
            Column {
                Text("Enviar como anónimo")
                Text(
                    if (esAnonimo) {
                        "Tu identidad se cifra en el servidor y no se muestra a quienes consultan el reporte."
                    } else {
                        "El reporte quedará asociado a tu cuenta; tu identidad no se muestra en el mapa público."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        borrador?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("JSON que se enviará", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (resultado != null) jsonEnviado.orEmpty() else jsonVistaPrevia.orEmpty(),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (catalogoDesdeCache) {
            Text(
                "Sin conexión: usando el catálogo guardado (versión $versionCatalogoId).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!catalogoCargando && (catalogoDesdeCache || tipos.isEmpty())) {
            OutlinedButton(
                onClick = { intentoCargaCatalogo++ },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (catalogoDesdeCache) "Actualizar catálogo" else "Reintentar carga del catálogo")
            }
        }
        val aviso = avisoEnVivo ?: avisoGuardado
        if (reporteEnCola && aviso?.reporteId == idReporte) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Reporte enviado al recuperar conexión", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (aviso.resultado == "ACEPTADO") {
                            "El servidor lo recibió y queda pendiente de moderación."
                        } else {
                            "El servidor ya había recibido este reporte."
                        }
                    )
                }
            }
        }
        resultado?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Reporte recibido por el servidor", style = MaterialTheme.typography.titleSmall)
                    Text("Folio: ${it.folio}")
                    Text("Estado: ${it.estado}")
                    Text("Celda: ${it.celda}")
                    Text("Queda pendiente de moderación; todavía no aparece en el mapa de calor.")
                }
            }
        }
        if (reporteEnCola && aviso?.reporteId != idReporte) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Reporte guardado en la cola P11", style = MaterialTheme.typography.titleSmall)
                    Text("Se reintentará automáticamente cuando haya conexión y una sesión válida.")
                    Text("La cola conserva el reporte hasta 6 días. Aún no tiene folio del servidor ni aparece en el mapa.")
                }
            }
        }

        if (resultado == null && !reporteEnCola) {
            Button(
                onClick = {
                    val reporte = borrador ?: return@Button
                    enviando = true
                    error = null
                    scope.launch {
                        try {
                            val errorSesion = prepararSesion()
                            error = errorSesion
                            payloadEnviado = reporte
                            jsonEnviado = ClienteHttp.json.encodeToString(ReporteEntradaDto.serializer(), reporte)
                            envioIntentado = true
                            when (val envio = moduloReportes.enviarOEncolar(reporte, evidencias)) {
                                is ResultadoEnvioReporte.Recibido -> {
                                    resultado = envio.reporte
                                    payloadEnviado = envio.payload
                                    jsonEnviado = ClienteHttp.json.encodeToString(
                                        ReporteEntradaDto.serializer(),
                                        envio.payload,
                                    )
                                    error = null
                                }
                                is ResultadoEnvioReporte.EnCola -> {
                                    reporteEnCola = true
                                    payloadEnviado = envio.payload
                                    jsonEnviado = ClienteHttp.json.encodeToString(
                                        ReporteEntradaDto.serializer(),
                                        envio.payload,
                                    )
                                    error = null
                                }
                            }
                        } catch (e: HttpException) {
                            error = "El servidor rechazó el reporte: ${mensajeErrorServidor(e)}"
                        } catch (e: IOException) {
                            error = "No se pudo enviar ni guardar el reporte: ${e.localizedMessage ?: "error local"}."
                        } catch (e: SQLiteException) {
                            error = "No se pudo guardar el reporte en la cola local: ${e.localizedMessage ?: "error de almacenamiento"}."
                        } finally {
                            enviando = false
                        }
                    }
                },
                enabled = if (envioIntentado) !enviando else puedeEnviar,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        enviando -> "Enviando…"
                        envioIntentado -> "Reintentar el mismo envío"
                        else -> "Enviar reporte"
                    }
                )
            }
            if (envioIntentado && !enviando) {
                TextButton(onClick = {
                    idReporte = UUID.randomUUID().toString()
                    creadoEn = Instant.now().toString()
                    envioIntentado = false
                    jsonEnviado = null
                    payloadEnviado = null
                    error = null
                }) {
                    Text("Editar datos (inicia un envío nuevo)")
                }
            }
        } else {
            Button(
                onClick = {
                    idReporte = UUID.randomUUID().toString()
                    creadoEn = Instant.now().toString()
                    descripcion = ""
                    evidencias = emptyList()
                    latitud = ""
                    longitud = ""
                    precisionM = null
                    esAnonimo = false
                    resultado = null
                    envioIntentado = false
                    jsonEnviado = null
                    payloadEnviado = null
                    error = null
                    ubicacionMensaje = null
                    reporteEnCola = false
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Crear otro reporte")
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun obtenerUltimaUbicacion(context: Context): Location? {
    val fine = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val proveedores = if (fine) {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    } else {
        listOf(LocationManager.NETWORK_PROVIDER)
    }
    return proveedores
        .filter { locationManager.isProviderEnabled(it) }
        .mapNotNull { locationManager.getLastKnownLocation(it) }
        .maxByOrNull { it.time }
}

private fun mensajeErrorServidor(error: HttpException): String {
    val mensaje = try {
        error.response()?.errorBody()?.string()?.let {
            ClienteHttp.json.decodeFromString(ErrorReporteDto.serializer(), it).let { apiError ->
                "${apiError.codigo}: ${apiError.mensaje}"
            }
        }
    } catch (e: IOException) {
        null
    } catch (e: SerializationException) {
        null
    }
    return mensaje ?: "HTTP ${error.code()}"
}
