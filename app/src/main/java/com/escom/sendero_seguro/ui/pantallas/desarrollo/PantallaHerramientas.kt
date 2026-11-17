package com.escom.sendero_seguro.ui.pantallas.desarrollo

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.escom.sendero_seguro.emergencias.DatosPruebaAlerta
import com.escom.sendero_seguro.emergencias.EstadoEnvioAlerta
import com.escom.sendero_seguro.ui.componentes.BotonSendero
import com.escom.sendero_seguro.ui.componentes.CampoTexto
import com.escom.sendero_seguro.ui.componentes.PantallaConPie
import com.escom.sendero_seguro.ui.componentes.TipoBoton
import com.escom.sendero_seguro.ui.componentes.Tarjeta
import com.escom.sendero_seguro.ui.componentes.TarjetaAcceso
import com.escom.sendero_seguro.ui.componentes.TituloSeccion
import com.escom.sendero_seguro.ui.theme.TextoSecundario
import com.escom.sendero_seguro.ui.theme.Tinta

/**
 * Herramientas de prueba del equipo (solo en debug, desde Perfil). Reúne lo que antes abría la pantalla de inicio:
 * la prueba local de alerta, el diagnóstico P5.1/P6/P7, las pantallas provisionales P10 y P12 y la galería.
 */
@Composable
fun PantallaHerramientas(
    estadoEnvio: EstadoEnvioAlerta?,
    mapaP12Habilitado: Boolean,
    onEnviarPrueba: (DatosPruebaAlerta) -> Unit,
    onDiagnostico: () -> Unit,
    onMapaP12: () -> Unit,
    onReporteP10: () -> Unit,
    onGaleria: () -> Unit,
    onAvisoRed: (enlace: String) -> Unit,
    onVolver: () -> Unit
) {
    var formulario by remember { mutableStateOf(false) }
    var enlace by remember { mutableStateOf("") }
    PantallaConPie(titulo = "Herramientas de prueba", onVolver = onVolver) {
        Text("Solo aparecen en la versión debug.", style = MaterialTheme.typography.bodyMedium, color = TextoSecundario)
        TituloSeccion("Alertas")
        TarjetaAcceso("Prueba local de alerta", Icons.Outlined.Send, { formulario = true }, subtitulo = "Internet → malla → SMS → cola")
        estadoEnvio?.let { e ->
            Tarjeta {
                Text("Prueba de transmisión: ${e.etapa.etiqueta()}", style = MaterialTheme.typography.titleSmall, color = Tinta)
                Text(e.mensaje, style = MaterialTheme.typography.bodySmall, color = TextoSecundario)
                e.folio?.let { Text("Folio: $it", style = MaterialTheme.typography.bodySmall, color = Tinta) }
                e.estadoServidor?.let { Text("Estado en evento_alerta: $it", style = MaterialTheme.typography.bodySmall, color = Tinta) }
                if (e.alertaId.isNotBlank()) Text("ID: ${e.alertaId}", style = MaterialTheme.typography.labelSmall, color = TextoSecundario)
            }
        }
        TarjetaAcceso("Diagnóstico P5.1 / P6 / P7", Icons.Outlined.BugReport, onDiagnostico)
        TituloSeccion("Red de apoyo")
        Text(
            "A-13 se abre con el push del servidor. Para probarla, pega el enlace de ubicación de una alerta (POST /dev/alertas lo devuelve).",
            style = MaterialTheme.typography.bodySmall, color = TextoSecundario
        )
        CampoTexto("Enlace de ubicación", enlace, { enlace = it.trim() })
        BotonSendero("Abrir A-13 con este enlace", { onAvisoRed(enlace) }, tipo = TipoBoton.SECUNDARIO, habilitado = enlace.contains("/"))
        TituloSeccion("Comunidad (prototipos)")
        TarjetaAcceso("Reporte P10 provisional", Icons.Outlined.WarningAmber, onReporteP10)
        if (mapaP12Habilitado) TarjetaAcceso("Mapa P12 provisional", Icons.Outlined.Map, onMapaP12)
        TituloSeccion("Interfaz")
        TarjetaAcceso("Galería de componentes", Icons.Outlined.Palette, onGaleria)
    }
    if (formulario) {
        FormularioPruebaAlerta(alEnviar = { formulario = false; onEnviarPrueba(it) }, alCerrar = { formulario = false })
    }
}
