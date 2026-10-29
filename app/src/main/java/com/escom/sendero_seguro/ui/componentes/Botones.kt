package com.escom.sendero_seguro.ui.componentes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.escom.sendero_seguro.ui.theme.Blanco
import com.escom.sendero_seguro.ui.theme.Borde
import com.escom.sendero_seguro.ui.theme.Lila
import com.escom.sendero_seguro.ui.theme.LilaTexto
import com.escom.sendero_seguro.ui.theme.LilaTinte
import com.escom.sendero_seguro.ui.theme.Malva
import com.escom.sendero_seguro.ui.theme.Rojo
import com.escom.sendero_seguro.ui.theme.RojoOscuro
import com.escom.sendero_seguro.ui.theme.Superficie
import com.escom.sendero_seguro.ui.theme.Tinta

private val FormaBoton = RoundedCornerShape(12.dp)
private val AltoMinimo = 56.dp  // objetivo táctil ≥ 48 dp

enum class TipoBoton { PRINCIPAL, SECUNDARIO, IRREVERSIBLE, OSCURO, MALVA, CONTORNO }

/** Botón de la guía de estilo. PRINCIPAL rojo, SECUNDARIO lila, IRREVERSIBLE rojo oscuro. */
@Composable
fun BotonSendero(
    texto: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tipo: TipoBoton = TipoBoton.PRINCIPAL,
    icono: ImageVector? = null,
    habilitado: Boolean = true
) {
    val contenido: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icono != null) {
                Icon(icono, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(texto, style = MaterialTheme.typography.labelLarge)
        }
    }
    val base = modifier.fillMaxWidth().heightIn(min = AltoMinimo)
    val relleno = PaddingValues(horizontal = 16.dp, vertical = 12.dp)

    when (tipo) {
        TipoBoton.SECUNDARIO -> OutlinedButton(
            onClick = onClick, modifier = base, enabled = habilitado, shape = FormaBoton,
            border = BorderStroke(1.5.dp, Lila), contentPadding = relleno,
            colors = ButtonDefaults.outlinedButtonColors(containerColor = LilaTinte, contentColor = LilaTexto)
        ) { contenido() }

        TipoBoton.CONTORNO -> OutlinedButton(
            onClick = onClick, modifier = base, enabled = habilitado, shape = FormaBoton,
            border = BorderStroke(1.5.dp, Borde), contentPadding = relleno,
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Superficie, contentColor = Tinta)
        ) { contenido() }

        else -> {
            val fondo: Color = when (tipo) {
                TipoBoton.IRREVERSIBLE -> RojoOscuro
                TipoBoton.OSCURO -> Tinta
                TipoBoton.MALVA -> Malva
                else -> Rojo
            }
            Button(
                onClick = onClick, modifier = base, enabled = habilitado, shape = FormaBoton,
                contentPadding = relleno,
                colors = ButtonDefaults.buttonColors(containerColor = fondo, contentColor = Blanco)
            ) { contenido() }
        }
    }
}
