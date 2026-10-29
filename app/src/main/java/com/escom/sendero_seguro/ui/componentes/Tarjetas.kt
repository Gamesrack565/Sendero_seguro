package com.escom.sendero_seguro.ui.componentes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.escom.sendero_seguro.ui.modelo.EstadoAlerta
import com.escom.sendero_seguro.ui.theme.Borde
import com.escom.sendero_seguro.ui.theme.Lila
import com.escom.sendero_seguro.ui.theme.LilaTexto
import com.escom.sendero_seguro.ui.theme.LilaTinte
import com.escom.sendero_seguro.ui.theme.Malva
import com.escom.sendero_seguro.ui.theme.Superficie
import com.escom.sendero_seguro.ui.theme.TextoMono
import com.escom.sendero_seguro.ui.theme.TextoSecundario
import com.escom.sendero_seguro.ui.theme.Tinta
import kotlin.math.roundToInt

private val FormaTarjeta = RoundedCornerShape(16.dp)

/** Superficie con borde, base de todas las tarjetas. */
@Composable
fun Tarjeta(
    modifier: Modifier = Modifier,
    fondo: Color = Superficie,
    borde: Color = Borde,
    onClick: (() -> Unit)? = null,
    contenido: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = FormaTarjeta,
        color = fondo,
        border = BorderStroke(1.dp, borde)
    ) { ConRelleno { contenido() } }
}

@Composable
private fun ConRelleno(contenido: @Composable () -> Unit) {
    Column(Modifier.padding(16.dp)) { contenido() }
}

/** Acceso de la pantalla de inicio (Ruta segura, Reportar, Mi red de apoyo). */
@Composable
fun TarjetaAcceso(
    titulo: String,
    icono: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitulo: String? = null,
    destacada: Boolean = false
) {
    Tarjeta(
        modifier = modifier.heightIn(min = 64.dp),
        fondo = if (destacada) LilaTinte else Superficie,
        borde = if (destacada) Lila else Borde,
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icono, contentDescription = null, tint = Malva, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(titulo, style = MaterialTheme.typography.titleSmall, color = Tinta)
                if (subtitulo != null) Text(subtitulo, style = MaterialTheme.typography.bodyMedium, color = TextoSecundario)
            }
        }
    }
}

/**
 * Sugerencia de la IA (IA-1). La IA solo sugiere: muestra su confianza y se acepta o
 * se corrige con un toque.
 */
@Composable
fun TarjetaSugerenciaIA(
    sugerencia: String,
    confianza: Float,
    onUsar: () -> Unit,
    onElegirOtro: () -> Unit,
    modifier: Modifier = Modifier
) {
    Tarjeta(modifier = modifier, borde = Lila) {
        Chip(
            "Sugerencia IA · ${(confianza * 100).roundToInt()}%",
            LilaTinte, LilaTexto, icono = Icons.Outlined.AutoAwesome
        )
        Spacer(Modifier.size(10.dp))
        Text(sugerencia, style = MaterialTheme.typography.titleMedium, color = Tinta)
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BotonSendero("Usar", onUsar, Modifier.weight(1f), tipo = TipoBoton.MALVA)
            BotonSendero("Elegir otro", onElegirOtro, Modifier.weight(1f), tipo = TipoBoton.CONTORNO)
        }
    }
}

/** Tarjeta de alerta: folio, estado, tipo y detalle (lista del operador e historial). */
@Composable
fun TarjetaAlerta(
    folio: String,
    estado: EstadoAlerta,
    tipo: String,
    detalle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Tarjeta(modifier = modifier, onClick = onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(folio, style = TextoMono, color = TextoSecundario, modifier = Modifier.weight(1f))
            ChipEstadoAlerta(estado)
        }
        Spacer(Modifier.size(8.dp))
        Text(tipo, style = MaterialTheme.typography.titleMedium, color = Tinta)
        Text(detalle, style = MaterialTheme.typography.bodyMedium, color = TextoSecundario)
    }
}
