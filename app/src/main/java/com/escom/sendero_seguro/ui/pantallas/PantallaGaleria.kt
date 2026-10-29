package com.escom.sendero_seguro.ui.pantallas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.escom.sendero_seguro.ui.componentes.BotonPanico
import com.escom.sendero_seguro.ui.componentes.BotonSendero
import com.escom.sendero_seguro.ui.componentes.CampoTexto
import com.escom.sendero_seguro.ui.componentes.ChipCanal
import com.escom.sendero_seguro.ui.componentes.ChipEstadoAlerta
import com.escom.sendero_seguro.ui.componentes.ChipRiesgo
import com.escom.sendero_seguro.ui.componentes.TarjetaAlerta
import com.escom.sendero_seguro.ui.componentes.TarjetaSugerenciaIA
import com.escom.sendero_seguro.ui.componentes.TipoBoton
import com.escom.sendero_seguro.ui.demo.DatosDemo
import com.escom.sendero_seguro.ui.mapa.MapaSendero
import com.escom.sendero_seguro.ui.mapa.MarcadorMapa
import com.escom.sendero_seguro.ui.modelo.CanalEnvio
import com.escom.sendero_seguro.ui.modelo.EstadoAlerta
import com.escom.sendero_seguro.ui.modelo.NivelRiesgo
import com.escom.sendero_seguro.ui.theme.TextoSecundario

/** Solo en desarrollo: muestra los componentes de la guía de estilo para revisarlos en el teléfono. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PantallaGaleria(onVolver: () -> Unit, modifier: Modifier = Modifier) {
    var telefono by remember { mutableStateOf("55 1234 567") }
    val errorTelefono = if (telefono.filter(Char::isDigit).length != 10) "Escribe los 10 dígitos de tu número." else null

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Galería de componentes", style = MaterialTheme.typography.displaySmall)
        Text("Guía de estilo del E3. Solo aparece en la versión de desarrollo.", color = TextoSecundario)

        Seccion("Botón de pánico · CU-11")
        BotonPanico({}, Modifier.align(Alignment.CenterHorizontally))

        Seccion("Botones")
        BotonSendero("Acción principal", {})
        BotonSendero("Acción secundaria", {}, tipo = TipoBoton.SECUNDARIO)
        BotonSendero("Acción irreversible", {}, tipo = TipoBoton.IRREVERSIBLE)
        BotonSendero("Llamar 911", {}, tipo = TipoBoton.OSCURO, icono = Icons.Outlined.Call)
        BotonSendero("Ya estoy a salvo", {}, tipo = TipoBoton.CONTORNO)

        Seccion("Canal de envío (E-05)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CanalEnvio.entries.forEach { ChipCanal(it) }
        }

        Seccion("Nivel de riesgo")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NivelRiesgo.entries.forEach { ChipRiesgo(it) }
        }

        Seccion("Estados de la alerta (E-01)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EstadoAlerta.entries.forEach { ChipEstadoAlerta(it) }
        }
        Text("Como lo ve el integrante:", color = TextoSecundario)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EstadoAlerta.entries.forEach { ChipEstadoAlerta(it, textoLlano = true) }
        }

        Seccion("Campo con error")
        CampoTexto("Teléfono móvil", telefono, { telefono = it }, error = errorTelefono, teclado = KeyboardType.Phone)

        Seccion("Sugerencia de IA y tarjeta de alerta")
        TarjetaSugerenciaIA("Acoso callejero", 0.87f, {}, {})
        TarjetaAlerta(
            "ALR-2026-004817", EstadoAlerta.ESCALADA,
            "Persona sospechosa siguiéndome", "Física · prioridad 1 · MALLA, 3 saltos"
        )

        Seccion("Mapa con respaldo")
        MapaSendero(
            centro = DatosDemo.ESCOM,
            marcadores = listOf(MarcadorMapa(DatosDemo.ESCOM, "ESCOM")),
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(16.dp))
        )

        Spacer(Modifier.size(4.dp))
        BotonSendero("Volver", onVolver, tipo = TipoBoton.CONTORNO)
    }
}

@Composable
private fun Seccion(titulo: String) {
    Text(titulo, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 10.dp))
}
