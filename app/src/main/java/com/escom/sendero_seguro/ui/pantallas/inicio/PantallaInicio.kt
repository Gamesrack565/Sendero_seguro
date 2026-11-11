package com.escom.sendero_seguro.ui.pantallas.inicio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
    modifier: Modifier = Modifier
) {
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
        BotonPanico(onPedirAyuda, Modifier.align(Alignment.CenterHorizontally))
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
    }
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
