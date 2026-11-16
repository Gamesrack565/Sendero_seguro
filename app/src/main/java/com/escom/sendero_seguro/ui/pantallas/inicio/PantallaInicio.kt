package com.escom.sendero_seguro.ui.pantallas.inicio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.escom.sendero_seguro.emergencias.DatosPruebaAlerta
import com.escom.sendero_seguro.emergencias.EstadoEnvioAlerta
import com.escom.sendero_seguro.emergencias.EtapaEnvioAlerta
import com.escom.sendero_seguro.ui.componentes.BotonPanico
import com.escom.sendero_seguro.ui.componentes.ChipCanal
import com.escom.sendero_seguro.ui.componentes.Tarjeta
import com.escom.sendero_seguro.ui.componentes.TarjetaAcceso
import com.escom.sendero_seguro.ui.modelo.CanalEnvio
import com.escom.sendero_seguro.ui.theme.Malva
import com.escom.sendero_seguro.ui.theme.Sendero_seguroTheme
import com.escom.sendero_seguro.ui.theme.TextoSecundario
import com.escom.sendero_seguro.ui.theme.Tinta

/** Lo que muestra A-04. Lo llena quien la use (datos de ejemplo o, después, los módulos reales). */
data class EstadoInicio(
    val nombre: String,
    val canal: CanalEnvio,
    val contactos: Int,
    val circulo: String?,
    val integrantesCirculo: Int
)

/** A-04 · Inicio: botón de pánico (CU-11, CU-27). */
@Composable
fun PantallaInicio(
    estado: EstadoInicio,
    onPedirAyuda: () -> Unit,
    onConectividad: () -> Unit,
    onRutaSegura: () -> Unit,
    onReportar: () -> Unit,
    onRedApoyo: () -> Unit,
    modifier: Modifier = Modifier,
    onEnviarAlertaPrueba: ((DatosPruebaAlerta) -> Unit)? = null,
    estadoEnvioAlerta: EstadoEnvioAlerta? = null
) {
    var mostrarFormulario by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Hola, ${estado.nombre}",
                style = MaterialTheme.typography.headlineSmall,
                color = Tinta,
                modifier = Modifier.weight(1f)
            )
            ChipCanal(estado.canal, Modifier.clickable(onClick = onConectividad), comoModo = true)
        }

        Spacer(Modifier.weight(1f))
        BotonPanico(
            onClick = {
                if (onEnviarAlertaPrueba != null) mostrarFormulario = true else onPedirAyuda()
            },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(Modifier.size(20.dp))
        Text(
            "Un toque. Tendrás 5 segundos para cancelar o elegir qué pasa.",
            style = MaterialTheme.typography.bodyLarge,
            color = Tinta,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
        )
        Spacer(Modifier.size(16.dp))
        Tarjeta {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PhoneAndroid, null, tint = Malva, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    "Sin desbloquear: presiona 5 veces el botón de encendido o usa el widget.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextoSecundario
                )
            }
        }
        Spacer(Modifier.weight(1f))

        // Accesos en el tercio inferior (alcance del pulgar)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TarjetaAcceso("Ruta segura", Icons.Outlined.Route, onRutaSegura, Modifier.weight(1f))
            TarjetaAcceso("Reportar", Icons.Outlined.WarningAmber, onReportar, Modifier.weight(1f))
        }
        Spacer(Modifier.size(12.dp))
        val red = buildString {
            append("${estado.contactos} contactos")
            if (estado.circulo != null) append(" · Círculo ${estado.circulo} (${estado.integrantesCirculo} integrantes)")
        }
        TarjetaAcceso("Mi red de apoyo", Icons.Outlined.Groups, onRedApoyo, subtitulo = red, destacada = true)

        estadoEnvioAlerta?.let { estado ->
            Spacer(Modifier.size(12.dp))
            Tarjeta {
                Text(
                    "Prueba de transmisión: ${estado.etapa.etiqueta()}",
                    style = MaterialTheme.typography.titleSmall,
                    color = Tinta
                )
                Text(estado.mensaje, style = MaterialTheme.typography.bodySmall, color = TextoSecundario)
                estado.folio?.let { Text("Folio: $it", style = MaterialTheme.typography.bodySmall, color = Tinta) }
                estado.estadoServidor?.let {
                    Text("Estado en evento_alerta: $it", style = MaterialTheme.typography.bodySmall, color = Tinta)
                }
                if (estado.alertaId.isNotBlank()) {
                    Text("ID: ${estado.alertaId}", style = MaterialTheme.typography.labelSmall, color = TextoSecundario)
                }
            }
        }
    }

    if (mostrarFormulario) {
        FormularioPruebaAlerta(
            alEnviar = { datos ->
                mostrarFormulario = false
                onEnviarAlertaPrueba?.invoke(datos)
            },
            alCerrar = { mostrarFormulario = false }
        )
    }
}

@Composable
private fun FormularioPruebaAlerta(
    alEnviar: (DatosPruebaAlerta) -> Unit,
    alCerrar: () -> Unit
) {
    var tipoId by remember { mutableStateOf("1") }
    var descripcion by remember { mutableStateOf("") }
    var latitud by remember { mutableStateOf("19.504507") }
    var longitud by remember { mutableStateOf("-99.146911") }
    var precision by remember { mutableStateOf("10") }
    var bateria by remember { mutableStateOf("80") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = alCerrar,
        title = { Text("Prueba rápida de alerta") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Tipo 1 = Genérica del catálogo local.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    tipoId, { tipoId = it }, label = { Text("ID del tipo de incidente") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true
                )
                OutlinedTextField(
                    descripcion, { descripcion = it }, label = { Text("Descripción (opcional)") },
                    minLines = 2, maxLines = 3
                )
                OutlinedTextField(
                    latitud, { latitud = it }, label = { Text("Latitud") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true
                )
                OutlinedTextField(
                    longitud, { longitud = it }, label = { Text("Longitud") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true
                )
                OutlinedTextField(
                    precision, { precision = it }, label = { Text("Precisión en metros") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true
                )
                OutlinedTextField(
                    bateria, { bateria = it }, label = { Text("Batería (0-100)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val datos = runCatching {
                    DatosPruebaAlerta(
                        tipoIncidenteId = tipoId.toInt(),
                        descripcion = descripcion,
                        latitud = latitud.toDouble(),
                        longitud = longitud.toDouble(),
                        precisionMetros = precision.toFloat(),
                        nivelBateria = bateria.toInt()
                    ).also {
                        require(it.tipoIncidenteId >= 0) { "El tipo no puede ser negativo" }
                        require(it.latitud in -90.0..90.0 && it.longitud in -180.0..180.0) {
                            "Latitud o longitud fuera de rango"
                        }
                        require(it.precisionMetros.isFinite() && it.precisionMetros >= 0f) {
                            "La precisión debe ser válida"
                        }
                        require(it.nivelBateria in 0..100) { "La batería debe estar entre 0 y 100" }
                    }
                }.getOrElse {
                    error = it.message ?: "Revisa los datos del formulario"
                    null
                }
                if (datos != null) alEnviar(datos)
            }) { Text("Enviar") }
        },
        dismissButton = { TextButton(onClick = alCerrar) { Text("Cancelar") } }
    )
}

private fun EtapaEnvioAlerta.etiqueta(): String = when (this) {
    EtapaEnvioAlerta.PREPARANDO -> "Preparando"
    EtapaEnvioAlerta.INTERNET -> "Internet"
    EtapaEnvioAlerta.MALLA -> "Malla P5.1"
    EtapaEnvioAlerta.SMS -> "SMS"
    EtapaEnvioAlerta.COLA_LOCAL -> "Cola local P11"
    EtapaEnvioAlerta.REGISTRADA -> "Registrada en servidor"
    EtapaEnvioAlerta.RECHAZADA -> "Rechazada"
    EtapaEnvioAlerta.ERROR -> "Error"
}

@Preview(showBackground = true, backgroundColor = 0xFFF2EDEB, widthDp = 360, heightDp = 760)
@Composable
private fun VistaPreviaInicio() {
    Sendero_seguroTheme {
        PantallaInicio(
            EstadoInicio("Mariana", CanalEnvio.INTERNET, 3, "6IV1", 24),
            {}, {}, {}, {}, {}
        )
    }
}
