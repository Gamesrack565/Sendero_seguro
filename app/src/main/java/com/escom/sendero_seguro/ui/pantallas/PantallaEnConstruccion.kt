package com.escom.sendero_seguro.ui.pantallas

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.escom.sendero_seguro.ui.componentes.BotonSendero
import com.escom.sendero_seguro.ui.componentes.TipoBoton
import com.escom.sendero_seguro.ui.navegacion.Pantalla
import com.escom.sendero_seguro.ui.theme.TextoMono
import com.escom.sendero_seguro.ui.theme.TextoSecundario

/**
 * Marcador de una pantalla que todavía no se construye. Permite recorrer el mapa de
 * navegación completo desde la primera fase; cada fase lo reemplaza por la pantalla real.
 */
@Composable
fun PantallaEnConstruccion(
    pantalla: Pantalla,
    siguientes: List<Pantalla>,
    onIr: (Pantalla) -> Unit,
    onVolver: (() -> Unit)?,
    modifier: Modifier = Modifier,
    extra: List<Pair<String, () -> Unit>> = emptyList()
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(pantalla.id, style = TextoMono, color = TextoSecundario)
        Text(pantalla.titulo, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.size(8.dp))
        Text(pantalla.cu, style = MaterialTheme.typography.bodyMedium, color = TextoSecundario)
        Text("Pantalla pendiente de construir.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.size(24.dp))
        siguientes.forEach { s ->
            BotonSendero("${s.id} · ${s.titulo}", { onIr(s) }, tipo = TipoBoton.SECUNDARIO)
            Spacer(Modifier.size(10.dp))
        }
        extra.forEach { (texto, accion) ->
            BotonSendero(texto, accion, tipo = TipoBoton.CONTORNO)
            Spacer(Modifier.size(10.dp))
        }
        if (onVolver != null) BotonSendero("Volver", onVolver, tipo = TipoBoton.CONTORNO)
    }
}
