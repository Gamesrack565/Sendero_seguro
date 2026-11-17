package com.escom.sendero_seguro.ui.pantallas.red

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.escom.sendero_seguro.BuildConfig
import com.escom.sendero_seguro.ui.componentes.FilaDato
import com.escom.sendero_seguro.ui.mapa.MapaSendero
import com.escom.sendero_seguro.ui.mapa.MarcadorMapa
import com.escom.sendero_seguro.ui.mapa.Punto
import com.escom.sendero_seguro.ui.pantallas.alerta.marcar
import com.escom.sendero_seguro.ui.theme.Blanco
import com.escom.sendero_seguro.ui.theme.Rojo
import com.escom.sendero_seguro.ui.theme.RojoOscuro
import com.escom.sendero_seguro.ui.theme.RojoTinte
import com.escom.sendero_seguro.ui.theme.Tinta
import com.escom.sendero_seguro.ui.util.haceCuanto
import com.escom.sendero_seguro.ui.util.horaDe
import com.escom.sendero_seguro.ui.util.instanteMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// Bloque 3 · A-13 Aviso a la red de apoyo (CU-16 · RF-9). Lo que ve un contacto con la app al recibir el push.

/**
 * Datos del aviso. [enlace] es el enlace de ubicación que manda el servidor (`…/enlace/{token}`);
 * nombre, tipo, folio y teléfono llegan con el push (o el SMS) y el estado y la ubicación se leen del enlace.
 */
data class AvisoRedApoyo(
    val enlace: String,
    val nombre: String? = null,
    val tipo: String? = null,
    val folio: String? = null,
    val telefono: String? = null,
    val recibidoEnMs: Long = System.currentTimeMillis()
) {
    val token: String get() = enlace.trimEnd('/').substringAfterLast('/')
}

/** Respuesta de `GET /enlace/{token}` (público, sin sesión). */
private data class EstadoEnlace(
    val tipo: String?,
    val primerNombre: String?,
    val estado: String?,
    val activa: Boolean,
    val punto: Punto?,
    val precisionM: Double?,
    val capturadaEnMs: Long?
)

private val clienteEnlace by lazy {
    OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
}

/** null = sin red; [EstadoEnlace.activa] = false si la alerta ya terminó (410). */
private suspend fun leerEnlace(token: String): EstadoEnlace? = withContext(Dispatchers.IO) {
    runCatching {
        clienteEnlace.newCall(Request.Builder().url("${BuildConfig.API_BASE_URL}enlace/$token").build()).execute().use { r ->
            if (r.code == 410 || r.code == 404) return@runCatching EstadoEnlace(null, null, "Terminada", false, null, null, null)
            if (!r.isSuccessful) return@runCatching null
            val o = Json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
            fun s(obj: JsonObject?, k: String) = (obj?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun d(obj: JsonObject?, k: String) = (obj?.get(k) as? JsonPrimitive)?.content?.toDoubleOrNull()
            val ub = o["ultimaUbicacion"] as? JsonObject
            EstadoEnlace(
                tipo = s(o, "tipo"),
                primerNombre = s(o, "primerNombre"),
                estado = s(o, "estado"),
                activa = (o["activa"] as? JsonPrimitive)?.content?.toBoolean() ?: true,
                punto = d(ub, "latitud")?.let { la -> d(ub, "longitud")?.let { lo -> Punto(la, lo) } },
                precisionM = d(ub, "precisionM"),
                capturadaEnMs = instanteMs(s(ub, "capturadaEn"))
            )
        }
    }.getOrNull()
}

/** A-13. Quién pide ayuda, qué pasa, dónde, el estado de atención y accesos para seguir la ubicación o llamar. */
@Composable
fun PantallaAvisoRedApoyo(aviso: AvisoRedApoyo) {
    val context = LocalContext.current
    var enlace by remember { mutableStateOf<EstadoEnlace?>(null) }
    var sinRed by remember { mutableStateOf(false) }
    LaunchedEffect(aviso.token) {
        while (true) {
            val r = leerEnlace(aviso.token)
            sinRed = r == null
            if (r != null) enlace = r
            if (r?.activa == false) break
            delay(10_000)
        }
    }
    val nombre = aviso.nombre ?: enlace?.primerNombre ?: "Una persona de tu red"
    val e = enlace

    Column(Modifier.fillMaxSize().background(Tinta)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "ALERTA DE TU RED DE APOYO · ${horaDe(aviso.recibidoEnMs, segundos = false)}",
                style = MaterialTheme.typography.labelSmall, color = RojoTinte
            )
            Text("$nombre necesita ayuda", style = MaterialTheme.typography.displaySmall, color = Blanco)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RojoOscuro).padding(horizontal = 16.dp, vertical = 6.dp)) {
                val filas = listOfNotNull(
                    "Qué" to (aviso.tipo ?: e?.tipo?.takeIf { it != "ALERTA" } ?: "Alerta de emergencia"),
                    "Dónde" to (e?.punto?.let { "%.5f, %.5f".format(it.latitud, it.longitud) + (e.precisionM?.let { p -> " · ±${p.toInt()} m" } ?: "") } ?: "Esperando ubicación…"),
                    "Estado" to (e?.estado ?: if (sinRed) "Sin conexión" else "Consultando…"),
                    aviso.folio?.let { "Folio" to it }
                )
                filas.forEachIndexed { i, (k, v) ->
                    if (i > 0) HorizontalDivider(color = Blanco.copy(alpha = 0.15f))
                    FilaDato(k, v, colorEtiqueta = RojoTinte, colorValor = Blanco, mono = k == "Folio")
                }
            }
            Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp)).clipToBounds()) {
                val p = e?.punto
                if (p != null) {
                    MapaSendero(centro = p, modifier = Modifier.fillMaxSize(), marcadores = listOf(MarcadorMapa(p, nombre)),
                        nota = e.capturadaEnMs?.let { "En vivo · ${haceCuanto(it)}" })
                } else {
                    Box(Modifier.fillMaxSize().background(Blanco.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                        Text(if (e?.activa == false) "La alerta terminó" else "Sin ubicación todavía", color = Blanco.copy(alpha = 0.7f))
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).background(Blanco)
                    .clickable(role = Role.Button, enabled = e?.activa != false) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(aviso.enlace))) }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = Tinta)
                Spacer(Modifier.width(8.dp))
                Text("Seguir su ubicación", style = MaterialTheme.typography.labelLarge, color = Tinta)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (aviso.telefono != null) {
                    OutlinedButton(
                        onClick = { marcar(context, aviso.telefono) },
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(2.dp, Blanco),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Blanco)
                    ) { Text("Llamar a ${nombre.substringBefore(' ')}", style = MaterialTheme.typography.labelLarge) }
                }
                Box(
                    Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).background(Rojo)
                        .clickable(role = Role.Button) { marcar(context, "911") },
                    contentAlignment = Alignment.Center
                ) { Text("Llamar 911", style = MaterialTheme.typography.labelLarge, color = Blanco) }
            }
            Text(
                "El enlace deja de funcionar cuando la alerta se cierra.",
                style = MaterialTheme.typography.bodySmall, color = Blanco.copy(alpha = 0.7f),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
