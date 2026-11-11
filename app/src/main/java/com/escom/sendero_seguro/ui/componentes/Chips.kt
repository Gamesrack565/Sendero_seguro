package com.escom.sendero_seguro.ui.componentes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.escom.sendero_seguro.ui.modelo.CanalEnvio
import com.escom.sendero_seguro.ui.modelo.EstadoAlerta
import com.escom.sendero_seguro.ui.modelo.NivelRiesgo
import com.escom.sendero_seguro.ui.theme.Amarillo
import com.escom.sendero_seguro.ui.theme.AmarilloTexto
import com.escom.sendero_seguro.ui.theme.AmarilloTinte
import com.escom.sendero_seguro.ui.theme.Blanco
import com.escom.sendero_seguro.ui.theme.Borde
import com.escom.sendero_seguro.ui.theme.Fondo
import com.escom.sendero_seguro.ui.theme.Lila
import com.escom.sendero_seguro.ui.theme.LilaTexto
import com.escom.sendero_seguro.ui.theme.LilaTinte
import com.escom.sendero_seguro.ui.theme.Rojo
import com.escom.sendero_seguro.ui.theme.RojoTexto
import com.escom.sendero_seguro.ui.theme.RojoTinte
import com.escom.sendero_seguro.ui.theme.Tinta

/** Chip base: nunca solo color, siempre texto (y su ícono cuando aplica). */
@Composable
fun Chip(
    texto: String,
    fondo: Color,
    color: Color,
    modifier: Modifier = Modifier,
    icono: ImageVector? = null
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(fondo)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icono != null) Icon(icono, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(texto, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

/** Canal de envío (E-05). Con `comoModo` muestra el modo de conectividad (chip de A-04). */
@Composable
fun ChipCanal(canal: CanalEnvio, modifier: Modifier = Modifier, comoModo: Boolean = false) {
    val (fondo, color, icono) = when (canal) {
        CanalEnvio.INTERNET -> Triple(AmarilloTinte, AmarilloTexto, Icons.Outlined.Wifi)
        CanalEnvio.MALLA -> Triple(LilaTinte, LilaTexto, Icons.Outlined.Hub)
        CanalEnvio.SMS -> Triple(AmarilloTinte, AmarilloTexto, Icons.Outlined.ChatBubbleOutline)
        CanalEnvio.EN_COLA -> Triple(Borde, Tinta, Icons.Outlined.Schedule)
    }
    Chip(if (comoModo) canal.modo else canal.etiqueta, fondo, color, modifier, icono)
}

/**
 * Estado de la alerta (E-01). El integrante ve el texto llano; el operador y el
 * administrador ven el valor del enum tal como está en alerta.estado.
 */
@Composable
fun ChipEstadoAlerta(estado: EstadoAlerta, modifier: Modifier = Modifier, textoLlano: Boolean = false) {
    val (fondo, color) = when (estado) {
        EstadoAlerta.EN_CUENTA_REGRESIVA -> RojoTinte to RojoTexto
        EstadoAlerta.ENVIADA, EstadoAlerta.ASIGNADA -> LilaTinte to LilaTexto
        EstadoAlerta.ESCALADA, EstadoAlerta.ACUSADA, EstadoAlerta.EN_ATENCION -> AmarilloTinte to AmarilloTexto
        EstadoAlerta.ATENDIDA -> Amarillo to Tinta
        EstadoAlerta.CERRADA -> Tinta to Blanco
        else -> Borde to Tinta
    }
    Chip(if (textoLlano) estado.textoLlano else estado.name, fondo, color, modifier)
}

/** Nivel de riesgo: se distingue por luminosidad (claro, medio, oscuro), no solo por tono. */
@Composable
fun ChipRiesgo(nivel: NivelRiesgo, modifier: Modifier = Modifier) {
    val (fondo, color) = when (nivel) {
        NivelRiesgo.BAJO -> Lila to Tinta
        NivelRiesgo.MEDIO -> Amarillo to Tinta
        NivelRiesgo.ALTO -> Rojo to Blanco
    }
    Chip("Riesgo ${nivel.name}", fondo, color, modifier)
}

@Composable
fun ChipNeutro(texto: String, modifier: Modifier = Modifier) = Chip(texto, Fondo, Tinta, modifier)
