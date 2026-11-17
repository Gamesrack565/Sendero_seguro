package com.escom.sendero_seguro.ui.pantallas.operacion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.escom.sendero_seguro.emergencias.AlertaAdHocOperadorVista
import com.escom.sendero_seguro.emergencias.EstadoRedYMalla
import com.escom.sendero_seguro.ui.componentes.BotonSendero
import com.escom.sendero_seguro.ui.componentes.Chip
import com.escom.sendero_seguro.ui.componentes.FilaDato
import com.escom.sendero_seguro.ui.componentes.NotaPie
import com.escom.sendero_seguro.ui.componentes.PantallaConPie
import com.escom.sendero_seguro.ui.componentes.TipoBoton
import com.escom.sendero_seguro.ui.componentes.TituloSeccion
import com.escom.sendero_seguro.ui.mapa.MapaSendero
import com.escom.sendero_seguro.ui.mapa.MarcadorMapa
import com.escom.sendero_seguro.ui.mapa.Punto
import com.escom.sendero_seguro.ui.modelo.CanalEnvio
import com.escom.sendero_seguro.ui.theme.Amarillo
import com.escom.sendero_seguro.ui.theme.AmarilloTexto
import com.escom.sendero_seguro.ui.theme.Archivo
import com.escom.sendero_seguro.ui.theme.Blanco
import com.escom.sendero_seguro.ui.theme.Borde
import com.escom.sendero_seguro.ui.theme.Fondo
import com.escom.sendero_seguro.ui.theme.Lila
import com.escom.sendero_seguro.ui.theme.LilaTinte
import com.escom.sendero_seguro.ui.theme.Malva
import com.escom.sendero_seguro.ui.theme.Rojo
import com.escom.sendero_seguro.ui.theme.RojoTinte
import com.escom.sendero_seguro.ui.theme.Superficie
import com.escom.sendero_seguro.ui.theme.TextoMono
import com.escom.sendero_seguro.ui.theme.TextoSecundario
import com.escom.sendero_seguro.ui.theme.Tinta
import com.escom.sendero_seguro.ui.util.formatoTelefono
import com.escom.sendero_seguro.ui.util.haceCuanto
import com.escom.sendero_seguro.ui.util.megas

// Bloque 5 · Operación: A-14 Modo operador (CU-24 · RF-15) y A-19 Conectividad y pendientes
// (CU-26, CU-14 · RF-14, RF-16, RF-17).

// ================================================================ A-14

/**
 * A-14. Alarma de máxima prioridad cuando el teléfono de un operador recibe un paquete de la malla sin internet.
 * Muestra distancia y rumbo hacia el emisor y permite acusar; el acuse se sincroniza al recuperar señal.
 */
@Composable
fun PantallaModoOperador(
    vista: AlertaAdHocOperadorVista,
    unidad: String?,
    hayInternet: Boolean,
    onAcusar: () -> Unit,
    onVolver: () -> Unit
) {
    val p = vista.paquete
    var verMapa by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Fondo)) {
        Row(Modifier.fillMaxWidth().background(Tinta).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(listOfNotNull("Modo operador", unidad?.let { "Unidad $it" }).joinToString(" · "), style = MaterialTheme.typography.titleMedium, color = Blanco, modifier = Modifier.weight(1f))
            Chip(if (hayInternet) "CON INTERNET" else "SIN INTERNET", Superficie, Tinta)
        }
        Column(Modifier.fillMaxWidth().background(Rojo).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Hub, contentDescription = null, tint = RojoTinte, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("ALERTA AD-HOC POR RED MALLA", style = MaterialTheme.typography.labelSmall, color = RojoTinte)
            }
            Text(p.tipoIncidenteNombre, style = MaterialTheme.typography.headlineSmall, color = Blanco, modifier = Modifier.padding(top = 6.dp))
            Text(
                "Activada ${haceCuanto(p.timestamp)} · llegó en ${p.saltosRecorridos} salto${if (p.saltosRecorridos == 1) "" else "s"}",
                style = MaterialTheme.typography.bodyMedium, color = Blanco.copy(alpha = 0.85f)
            )
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (verMapa) {
                val emisor = Punto(p.latitud, p.longitud)
                Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(16.dp)).clipToBounds()) {
                    MapaSendero(centro = emisor, modifier = Modifier.fillMaxSize(), marcadores = listOf(MarcadorMapa(emisor, p.nombreEmisor)), hayRed = hayInternet, nota = "Mapa en caché")
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Brujula(vista.rumboGrados.toFloat())
                    Spacer(Modifier.width(18.dp))
                    Column {
                        Text("${vista.distanciaMetros.toInt()} m", fontFamily = Archivo, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp, color = Tinta)
                        Text("${vista.rumboCardinal} · ±${p.precisionMetros.toInt()} m", style = MaterialTheme.typography.bodyLarge, color = TextoSecundario)
                        Text("%.5f, %.5f".format(p.latitud, p.longitud), style = TextoMono, color = TextoSecundario)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Superficie).border(1.dp, Borde, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 6.dp)) {
                val filas = listOf(
                    "Emisor" to p.nombreEmisor,
                    "Matrícula" to p.matriculaEmisor,
                    "Teléfono" to formatoTelefono(p.telefonoEmisor),
                    "Descripción" to p.descripcionCorta
                )
                filas.forEachIndexed { i, (k, v) ->
                    if (i > 0) HorizontalDivider(color = Borde)
                    FilaDato(k, v, colorEtiqueta = TextoSecundario, mono = k == "Matrícula" || k == "Teléfono")
                }
            }
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NotaPie(
                if (vista.acusadaLocalmente) "Acuse enviado por la malla. Se sincroniza con el servidor al recuperar señal."
                else "Tu teléfono sigue retransmitiendo el paquete para que llegue al servidor. El acuse se sincroniza al recuperar señal."
            )
            Button(
                onClick = onAcusar,
                enabled = !vista.acusadaLocalmente,
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Amarillo, contentColor = Tinta, disabledContainerColor = Borde)
            ) { Text(if (vista.acusadaLocalmente) "ACUSE ENVIADO" else "ACUSAR Y ACUDIR", fontFamily = Archivo, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp) }
            BotonSendero(if (verMapa) "Ver distancia y rumbo" else "Ver en mapa sin conexión", { verMapa = !verMapa }, tipo = TipoBoton.CONTORNO)
            if (vista.acusadaLocalmente) BotonSendero("Cerrar", onVolver, tipo = TipoBoton.CONTORNO)
        }
    }
}

/** Brújula con flecha hacia el emisor (rumbo en grados desde el norte). */
@Composable
private fun Brujula(rumbo: Float) {
    Box(Modifier.size(130.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { drawCircle(Tinta, style = Stroke(width = 6f)) }
        Text("N", style = MaterialTheme.typography.labelSmall, color = Tinta, modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        Canvas(Modifier.size(70.dp).rotate(rumbo)) {
            val w = size.width; val h = size.height
            val flecha = Path().apply {
                moveTo(w / 2, 0f); lineTo(w * 0.85f, h); lineTo(w / 2, h * 0.72f); lineTo(w * 0.15f, h); close()
            }
            drawPath(flecha, Rojo)
            drawCircle(Rojo, radius = 2f, center = Offset(w / 2, h / 2))
        }
    }
}

// ================================================================ A-19

/** Un pendiente de sincronizar (cola_alerta o cola_reporte). */
data class Pendiente(
    val tipo: String,          // ALERTA, UBICACION, CANCELACION, ACUSE, REPORTE…
    val titulo: String,
    val detalle: String,
    val expiraEnMs: Long
)

/**
 * A-19. Explica el modo de conectividad actual, el orden en que se intentará enviar una alerta y lo que falta
 * por sincronizar con su vencimiento.
 */
@Composable
fun PantallaConectividad(
    red: EstadoRedYMalla,
    canal: CanalEnvio,
    smsDisponible: Boolean,
    pendientes: List<Pendiente>,
    reintentando: Boolean,
    onReintentar: () -> Unit,
    onVolver: () -> Unit
) {
    PantallaConPie(
        titulo = "Conectividad",
        onVolver = onVolver,
        pie = { BotonSendero(if (reintentando) "Reintentando…" else "Reintentar ahora", onReintentar, tipo = TipoBoton.SECUNDARIO, habilitado = !reintentando) }
    ) {
        val (titulo, texto, icono) = when (canal) {
            CanalEnvio.INTERNET -> Triple("En línea", "Tus alertas salen por internet directo al servidor.", Icons.Outlined.Wifi)
            CanalEnvio.MALLA -> Triple("Modo malla", "Sin internet. Tus alertas viajan por los teléfonos con Sendero Seguro que estén a tu alcance.", Icons.Outlined.Hub)
            CanalEnvio.SMS -> Triple("Modo SMS", "Sin internet. Tus alertas salen como SMS codificado.", Icons.Outlined.WarningAmber)
            CanalEnvio.EN_COLA -> Triple("Sin conexión", "Tus alertas se guardan y se reintentan solas en cuanto haya señal.", Icons.Outlined.Schedule)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Malva).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icono, contentDescription = null, tint = Blanco)
                Spacer(Modifier.width(10.dp))
                Text(titulo, style = MaterialTheme.typography.headlineSmall, color = Blanco)
            }
            Text(texto, style = MaterialTheme.typography.bodyMedium, color = Blanco.copy(alpha = 0.9f), modifier = Modifier.padding(top = 6.dp))
        }

        TituloSeccion("Orden de envío de una alerta")
        val pasos = listOf(
            Triple(CanalEnvio.INTERNET, "Internet", if (red.conectadoAInternet) "Disponible" else "No disponible"),
            Triple(CanalEnvio.MALLA, "Red malla (Bluetooth)", if (red.escaneoBleActivo) "Activa" else "Inactiva"),
            Triple(CanalEnvio.SMS, "SMS codificado", if (smsDisponible) "Disponible" else "Sin pasarela"),
            Triple(CanalEnvio.EN_COLA, "Guardar y reintentar", "Siempre")
        )
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Superficie).border(1.dp, Borde, RoundedCornerShape(16.dp))) {
            pasos.forEachIndexed { i, (c, nombre, estado) ->
                if (i > 0) HorizontalDivider(color = Borde)
                val actual = c == canal
                Row(
                    Modifier.fillMaxWidth().background(if (actual) LilaTinte else Superficie).padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(if (actual) Malva else Borde), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = if (actual) Blanco else Tinta)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(nombre, style = if (actual) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge, color = Tinta, modifier = Modifier.weight(1f))
                    Text(estado, style = MaterialTheme.typography.labelMedium, color = if (estado == "No disponible" || estado == "Inactiva" || estado == "Sin pasarela") TextoSecundario else AmarilloTexto)
                }
            }
        }

        TituloSeccion("Pendientes de sincronizar")
        if (pendientes.isEmpty()) {
            NotaPie("No hay nada pendiente: todo llegó al servidor.")
        } else {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Superficie).border(1.dp, Borde, RoundedCornerShape(16.dp))) {
                pendientes.sortedBy { it.expiraEnMs }.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(color = Borde)
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(iconoDe(p.tipo), contentDescription = null, tint = Malva)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.titulo, style = MaterialTheme.typography.bodyLarge, color = Tinta)
                            Text(p.detalle, style = TextoMono.copy(fontSize = 12.sp), color = TextoSecundario)
                        }
                        Text(venceEn(p.expiraEnMs), style = MaterialTheme.typography.bodySmall, color = TextoSecundario)
                    }
                }
            }
        }
        if (red.cantidadPaquetesRetransmitidos > 0) {
            NotaPie("Hoy retransmitiste ${red.cantidadPaquetesRetransmitidos} paquete${if (red.cantidadPaquetesRetransmitidos == 1) "" else "s"} de otras personas. Se envían solos al recuperar internet.")
        }
        NotaPie("Escaneo de la malla: ${red.modoEscaneoBateria}")
    }
}

private fun iconoDe(tipo: String): ImageVector = when (tipo) {
    "REPORTE" -> Icons.Outlined.WarningAmber
    "ACUSE", "ALERTA", "CANCELACION" -> Icons.Outlined.Shield
    else -> Icons.Outlined.Schedule
}

private fun venceEn(ms: Long): String {
    val falta = ms - System.currentTimeMillis()
    return when {
        falta <= 0 -> "vencido"
        falta < 3_600_000 -> "expira ${falta / 60_000} min"
        falta < 86_400_000 -> "expira ${falta / 3_600_000} h"
        else -> "expira ${falta / 86_400_000} d"
    }
}

/** Texto del tamaño de un reporte pendiente («1 foto · 2.1 MB»). */
fun detalleReporte(fotos: Int, bytes: Long, reintentos: Int): String =
    listOfNotNull(
        if (fotos == 0) "sin fotos" else "$fotos foto${if (fotos == 1) "" else "s"}",
        bytes.takeIf { it > 0 }?.let { megas(it) },
        reintentos.takeIf { it > 0 }?.let { "$it reintento${if (it == 1) "" else "s"}" }
    ).joinToString(" · ")
