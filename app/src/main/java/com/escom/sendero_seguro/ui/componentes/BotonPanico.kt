package com.escom.sendero_seguro.ui.componentes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.escom.sendero_seguro.ui.theme.Archivo
import com.escom.sendero_seguro.ui.theme.Blanco
import com.escom.sendero_seguro.ui.theme.Rojo
import com.escom.sendero_seguro.ui.theme.RojoTinte

/** Botón de pánico (CU-11). Mide 200 dp; un toque inicia la cuenta regresiva. */
@Composable
fun BotonPanico(onClick: () -> Unit, modifier: Modifier = Modifier, diametro: Dp = 200.dp) {
    Box(
        modifier = modifier
            .size(diametro + 28.dp)
            .clip(CircleShape)
            .background(RojoTinte),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(diametro)
                .clip(CircleShape)
                .background(Rojo)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = "Pedir ayuda" },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "PEDIR\nAYUDA",
                color = Blanco,
                fontFamily = Archivo,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 34.sp,
                lineHeight = 36.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}
