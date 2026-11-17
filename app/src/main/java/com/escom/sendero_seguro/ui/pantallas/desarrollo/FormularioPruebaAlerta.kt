package com.escom.sendero_seguro.ui.pantallas.desarrollo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.escom.sendero_seguro.emergencias.DatosPruebaAlerta
import com.escom.sendero_seguro.emergencias.EtapaEnvioAlerta

// Formulario de prueba local de alerta (docs/prueba-alerta-local.md). Solo en la versión debug.

@Composable
internal fun FormularioPruebaAlerta(
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

internal fun EtapaEnvioAlerta.etiqueta(): String = when (this) {
    EtapaEnvioAlerta.PREPARANDO -> "Preparando"
    EtapaEnvioAlerta.INTERNET -> "Internet"
    EtapaEnvioAlerta.MALLA -> "Malla P5.1"
    EtapaEnvioAlerta.SMS -> "SMS"
    EtapaEnvioAlerta.COLA_LOCAL -> "Cola local P11"
    EtapaEnvioAlerta.REGISTRADA -> "Registrada en servidor"
    EtapaEnvioAlerta.RECHAZADA -> "Rechazada"
    EtapaEnvioAlerta.ERROR -> "Error"
}

