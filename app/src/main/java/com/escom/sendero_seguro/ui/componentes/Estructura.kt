package com.escom.sendero_seguro.ui.componentes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.escom.sendero_seguro.ui.theme.Archivo
import com.escom.sendero_seguro.ui.theme.Blanco
import com.escom.sendero_seguro.ui.theme.Borde
import com.escom.sendero_seguro.ui.theme.Fondo
import com.escom.sendero_seguro.ui.theme.Lila
import com.escom.sendero_seguro.ui.theme.LilaTexto
import com.escom.sendero_seguro.ui.theme.LilaTinte
import com.escom.sendero_seguro.ui.theme.Malva
import com.escom.sendero_seguro.ui.theme.RojoTexto
import com.escom.sendero_seguro.ui.theme.Superficie
import com.escom.sendero_seguro.ui.theme.Tinta
import com.escom.sendero_seguro.ui.theme.TextoSecundario

// Piezas de estructura comunes a las pantallas A-01 … A-19 (E3 · Diseño de E/S).

/** Encabezado con flecha de regreso y título (A-02, A-08, A-09 …). */
@Composable
fun Encabezado(
    titulo: String,
    onVolver: (() -> Unit)?,
    modifier: Modifier = Modifier,
    extra: (@Composable () -> Unit)? = null
) {
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onVolver != null) {
            IconButton(onClick = onVolver) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Tinta)
            }
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Text(titulo, style = MaterialTheme.typography.titleLarge, color = Tinta, modifier = Modifier.weight(1f))
        extra?.invoke()
    }
}

/**
 * Esqueleto de pantalla: encabezado, cuerpo con desplazamiento y pie fijo con las acciones.
 * El pie queda siempre visible para que el botón principal no se pierda al desplazar.
 */
@Composable
fun PantallaConPie(
    titulo: String?,
    onVolver: (() -> Unit)?,
    modifier: Modifier = Modifier,
    fondo: Color = Fondo,
    extraEncabezado: (@Composable () -> Unit)? = null,
    pie: (@Composable ColumnScope.() -> Unit)? = null,
    contenido: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxSize().background(fondo).imePadding()) {
        if (titulo != null) Encabezado(titulo, onVolver, extra = extraEncabezado)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            contenido()
            Spacer(Modifier.size(8.dp))
        }
        if (pie != null) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = pie
            )
        }
    }
}

/** Título de sección en mayúsculas (DATOS PERSONALES, SEGURIDAD …). */
@Composable
fun TituloSeccion(texto: String, modifier: Modifier = Modifier, color: Color = Malva) {
    Text(
        texto.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
        color = color,
        modifier = modifier.padding(top = 8.dp)
    )
}

/** Grupo de filas dentro de una tarjeta, separadas por una línea. */
@Composable
fun GrupoFilas(modifier: Modifier = Modifier, filas: List<@Composable () -> Unit>) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Superficie,
        border = BorderStroke(1.dp, Borde)
    ) {
        Column {
            filas.forEachIndexed { i, fila ->
                if (i > 0) HorizontalDivider(color = Borde)
                fila()
            }
        }
    }
}

/** Fila «etiqueta … valor», opcionalmente tocable (A-11). */
@Composable
fun FilaValor(
    etiqueta: String,
    valor: String,
    modifier: Modifier = Modifier,
    colorValor: Color = TextoSecundario,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(etiqueta, style = MaterialTheme.typography.bodyLarge, color = Tinta, modifier = Modifier.weight(1f))
        Text(valor, style = MaterialTheme.typography.bodyMedium, color = colorValor, textAlign = TextAlign.End)
    }
}

/** Fila con interruptor (A-11, A-12). */
@Composable
fun FilaInterruptor(
    titulo: String,
    activo: Boolean,
    onCambio: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitulo: String? = null,
    habilitado: Boolean = true
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(titulo, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = Tinta)
            if (subtitulo != null) Text(subtitulo, style = MaterialTheme.typography.bodySmall, color = TextoSecundario)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = activo,
            onCheckedChange = onCambio,
            enabled = habilitado,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Malva,
                checkedThumbColor = Blanco,
                uncheckedTrackColor = Borde,
                uncheckedThumbColor = Blanco,
                uncheckedBorderColor = Borde
            )
        )
    }
}

/** Control segmentado (3 / 5 / 10 s · Con mi nombre / Anónimo · Unirme / Coordinar). */
@Composable
fun <T> Segmentado(
    opciones: List<Pair<T, String>>,
    seleccion: T,
    onElegir: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Borde).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        opciones.forEach { (valor, etiqueta) ->
            val elegido = valor == seleccion
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (elegido) Malva else Color.Transparent)
                    .clickable(role = Role.Tab) { onElegir(valor) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    etiqueta,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (elegido) Blanco else TextoSecundario,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    }
}

/** Opción única con radio (motivo de cancelación en A-08). */
@Composable
fun OpcionRadio(texto: String, elegida: Boolean, onElegir: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onElegir),
        shape = RoundedCornerShape(14.dp),
        color = if (elegida) LilaTinte else Superficie,
        border = BorderStroke(1.5.dp, if (elegida) Lila else Borde)
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = elegida, onClick = onElegir,
                colors = RadioButtonDefaults.colors(selectedColor = Malva, unselectedColor = TextoSecundario)
            )
            Spacer(Modifier.width(6.dp))
            Text(texto, style = MaterialTheme.typography.bodyLarge, color = Tinta)
        }
    }
}

/** Renglón «QUÉ · valor» de A-06, A-13 y A-14. */
@Composable
fun FilaDato(etiqueta: String, valor: String, modifier: Modifier = Modifier, colorEtiqueta: Color = LilaTexto, colorValor: Color = Tinta, mono: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            etiqueta.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = colorEtiqueta,
            modifier = Modifier.width(110.dp).padding(top = 2.dp)
        )
        Text(
            valor,
            style = if (mono) com.escom.sendero_seguro.ui.theme.TextoMono else MaterialTheme.typography.bodyLarge,
            color = colorValor,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Círculo con iniciales (A-10, A-11). */
@Composable
fun Avatar(nombre: String, modifier: Modifier = Modifier, tamano: Int = 40, fondo: Color = LilaTinte, color: Color = LilaTexto) {
    val iniciales = nombre.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
    Box(modifier.size(tamano.dp).clip(CircleShape).background(fondo), contentAlignment = Alignment.Center) {
        Text(iniciales, color = color, fontFamily = Archivo, fontWeight = FontWeight.Bold, fontSize = (tamano * 0.36).sp)
    }
}

/** Nota con ícono de información en recuadro lila (A-12). */
@Composable
fun NotaInfo(texto: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(LilaTinte).padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = Malva, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(texto, style = MaterialTheme.typography.bodyMedium, color = Tinta)
    }
}

/** Texto de error general (no de un campo). */
@Composable
fun MensajeError(texto: String?, modifier: Modifier = Modifier) {
    if (texto != null) Text(texto, style = MaterialTheme.typography.bodyMedium, color = RojoTexto, modifier = modifier)
}

/** Texto de ayuda pequeño y centrado al pie de una pantalla. */
@Composable
fun NotaPie(texto: String, modifier: Modifier = Modifier) {
    Text(
        texto,
        style = MaterialTheme.typography.bodySmall,
        color = TextoSecundario,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth()
    )
}

/** Puntos del PIN de 4 dígitos. */
@Composable
fun PuntosPin(longitud: Int, modifier: Modifier = Modifier, total: Int = 4) {
    Row(
        modifier.semantics { contentDescription = "$longitud de $total dígitos" },
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        repeat(total) { i ->
            Surface(
                Modifier.size(16.dp),
                shape = CircleShape,
                color = if (i < longitud) Tinta else Color.Transparent,
                border = BorderStroke(2.dp, Tinta)
            ) {}
        }
    }
}

/** Teclado numérico de A-08 (y del PIN en A-02 y A-11). Con [onHuella] muestra la tecla de huella. */
@Composable
fun TecladoPin(
    pin: String,
    onCambio: (String) -> Unit,
    modifier: Modifier = Modifier,
    onHuella: (() -> Unit)? = null,
    longitud: Int = 4
) {
    val filas = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("H", "0", "B"))
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        filas.forEach { fila ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                fila.forEach { tecla ->
                    val accion: (() -> Unit)? = when (tecla) {
                        "H" -> onHuella
                        "B" -> ({ if (pin.isNotEmpty()) onCambio(pin.dropLast(1)) })
                        else -> ({ if (pin.length < longitud) onCambio(pin + tecla) })
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 60.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (accion != null) Superficie else Color.Transparent)
                            .then(if (accion != null) Modifier.clickable(role = Role.Button, onClick = accion) else Modifier),
                        contentAlignment = Alignment.Center
                    ) {
                        when (tecla) {
                            "H" -> if (onHuella != null) Icon(Icons.Outlined.Fingerprint, contentDescription = "Usar huella", tint = Malva)
                            "B" -> Icon(Icons.AutoMirrored.Outlined.Backspace, contentDescription = "Borrar", tint = Tinta)
                            else -> Text(tecla, fontFamily = Archivo, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Tinta)
                        }
                    }
                }
            }
        }
    }
}
