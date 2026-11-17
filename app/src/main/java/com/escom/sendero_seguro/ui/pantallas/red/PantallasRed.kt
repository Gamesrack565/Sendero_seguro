package com.escom.sendero_seguro.ui.pantallas.red

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.escom.sendero_seguro.identidad.dominio.Circulo
import com.escom.sendero_seguro.identidad.dominio.CodigoEstado
import com.escom.sendero_seguro.identidad.dominio.Contacto
import com.escom.sendero_seguro.identidad.dominio.MiembroEstado
import com.escom.sendero_seguro.identidad.dominio.NotificacionCanal
import com.escom.sendero_seguro.identidad.dominio.Validaciones
import com.escom.sendero_seguro.identidad.ui.EstadoRedApoyo
import com.escom.sendero_seguro.ui.componentes.Avatar
import com.escom.sendero_seguro.ui.componentes.BotonSendero
import com.escom.sendero_seguro.ui.componentes.CampoTexto
import com.escom.sendero_seguro.ui.componentes.Chip
import com.escom.sendero_seguro.ui.componentes.MensajeError
import com.escom.sendero_seguro.ui.componentes.NotaPie
import com.escom.sendero_seguro.ui.componentes.PantallaConPie
import com.escom.sendero_seguro.ui.componentes.Segmentado
import com.escom.sendero_seguro.ui.componentes.Tarjeta
import com.escom.sendero_seguro.ui.componentes.TipoBoton
import com.escom.sendero_seguro.ui.theme.Amarillo
import com.escom.sendero_seguro.ui.theme.AmarilloTexto
import com.escom.sendero_seguro.ui.theme.AmarilloTinte
import com.escom.sendero_seguro.ui.theme.Archivo
import com.escom.sendero_seguro.ui.theme.Blanco
import com.escom.sendero_seguro.ui.theme.Borde
import com.escom.sendero_seguro.ui.theme.Lila
import com.escom.sendero_seguro.ui.theme.LilaTexto
import com.escom.sendero_seguro.ui.theme.LilaTinte
import com.escom.sendero_seguro.ui.theme.Malva
import com.escom.sendero_seguro.ui.theme.Mono
import com.escom.sendero_seguro.ui.theme.RojoTexto
import com.escom.sendero_seguro.ui.theme.TextoMono
import com.escom.sendero_seguro.ui.theme.TextoSecundario
import com.escom.sendero_seguro.ui.theme.Tinta
import com.escom.sendero_seguro.ui.util.fechaHoraDe
import com.escom.sendero_seguro.ui.util.formatoTelefono

// Bloque 3 · Red de apoyo: A-09 Contactos de confianza, A-10 Círculo (CU-04, CU-05, CU-06 · RF-2, RF-3).

// ================================================================ A-09

/** A-09. Lista ordenada por prioridad; indica si el contacto recibe push (tiene la app) o solo SMS. */
@Composable
fun PantallaContactos(
    contactos: List<Contacto>,
    estado: EstadoRedApoyo,
    onSincronizar: () -> Unit,
    onGuardar: (id: String?, nombre: String, telefono: String, prioridad: Int?) -> Unit,
    onEliminar: (String) -> Unit,
    onReordenar: (List<String>) -> Unit,
    onCirculo: () -> Unit,
    onVolver: () -> Unit
) {
    LaunchedEffect(Unit) { onSincronizar() }
    var editandoId by rememberSaveable { mutableStateOf<String?>(null) }
    var nombre by rememberSaveable { mutableStateOf("") }
    var telefono by rememberSaveable { mutableStateOf("") }
    var prioridad by rememberSaveable { mutableStateOf<Int?>(null) }
    var porEliminar by rememberSaveable { mutableStateOf<String?>(null) }
    val ordenados = contactos.sortedBy { it.prioridad }
    val errores = estado.retro.errores

    // Al guardar sin errores se limpia el formulario.
    var guardando by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(estado.retro) {
        if (guardando && !estado.retro.cargando) {
            guardando = false
            if (errores.isEmpty() && (estado.retro.mensaje == null || estado.retro.mensaje!!.startsWith("Guardado"))) {
                nombre = ""; telefono = ""; prioridad = null; editandoId = null
            }
        }
    }

    fun mover(i: Int, d: Int) {
        val ids = ordenados.map { it.id }.toMutableList()
        val j = i + d
        if (j !in ids.indices) return
        ids[i] = ids[j].also { ids[j] = ids[i] }
        onReordenar(ids)
    }

    PantallaConPie(
        titulo = "Contactos de confianza",
        onVolver = onVolver,
        pie = {
            BotonSendero(
                if (editandoId != null) "Guardar cambios" else "Guardar contacto",
                { guardando = true; onGuardar(editandoId, nombre.trim(), telefono.filter(Char::isDigit), prioridad) },
                habilitado = nombre.isNotBlank() && telefono.isNotBlank() && !estado.retro.cargando
            )
            BotonSendero("Mi círculo escolar", onCirculo, tipo = TipoBoton.MALVA)
        }
    ) {
        Text(
            "Reciben tu alerta al mismo tiempo que la autoridad, en este orden. Usa las flechas para cambiar la prioridad.",
            style = MaterialTheme.typography.bodyMedium, color = TextoSecundario
        )
        if (ordenados.isEmpty()) {
            NotaPie("Aún no tienes contactos. Agrega al menos uno para que sepan cuando pidas ayuda.")
        }
        ordenados.forEachIndexed { i, c ->
            Tarjeta(onClick = { editandoId = c.id; nombre = c.nombre; telefono = c.telefono; prioridad = c.prioridad }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(Malva), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = Blanco, fontFamily = Archivo, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.nombre, style = MaterialTheme.typography.titleSmall, color = Tinta)
                        Text(formatoTelefono(c.telefono), style = TextoMono, color = TextoSecundario)
                        if (c.pendienteSincronizar) Text("Pendiente de enviar", style = MaterialTheme.typography.bodySmall, color = AmarilloTexto)
                    }
                    if (c.tieneApp || c.canalPreferido == NotificacionCanal.PUSH) Chip("PUSH", LilaTinte, LilaTexto)
                    else Chip("SOLO SMS", AmarilloTinte, AmarilloTexto)
                    Column {
                        IconButton(onClick = { mover(i, -1) }, enabled = i > 0, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Subir prioridad", tint = Malva)
                        }
                        IconButton(onClick = { mover(i, 1) }, enabled = i < ordenados.lastIndex, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Bajar prioridad", tint = Malva)
                        }
                    }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LilaTinte)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (editandoId != null) "Editar contacto" else "Nuevo contacto", style = MaterialTheme.typography.titleMedium, color = Tinta, modifier = Modifier.weight(1f))
                if (editandoId != null) {
                    IconButton(onClick = { porEliminar = editandoId }) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Eliminar", tint = RojoTexto) }
                    TextButton(onClick = { editandoId = null; nombre = ""; telefono = ""; prioridad = null }) { Text("Nuevo", color = Malva) }
                }
            }
            CampoTexto("Nombre", nombre, { nombre = it.take(80) }, error = errores[Validaciones.NOMBRE])
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CampoTexto(
                    "Teléfono", telefono, { telefono = it.filter(Char::isDigit).take(10) },
                    Modifier.weight(2f), error = errores[Validaciones.TELEFONO], teclado = KeyboardType.Phone
                )
                if (editandoId == null) {
                    CampoTexto(
                        "Prioridad", prioridad?.toString() ?: "", { prioridad = it.filter(Char::isDigit).take(2).toIntOrNull() },
                        Modifier.weight(1f), error = errores[Validaciones.PRIORIDAD], teclado = KeyboardType.Number,
                        ayuda = "${ordenados.size + 1}"
                    )
                }
            }
            Text("Si no usa la app, le llegará un SMS con el enlace de tu ubicación.", style = MaterialTheme.typography.bodySmall, color = TextoSecundario)
            estado.retro.mensaje?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tinta) }
        }
    }

    porEliminar?.let { id ->
        AlertDialog(
            onDismissRequest = { porEliminar = null },
            title = { Text("¿Quitar este contacto?") },
            text = { Text("Ya no recibirá tus alertas.") },
            confirmButton = {
                TextButton(onClick = { onEliminar(id); porEliminar = null; editandoId = null; nombre = ""; telefono = "" }) { Text("Quitar", color = RojoTexto) }
            },
            dismissButton = { TextButton(onClick = { porEliminar = null }) { Text("Cancelar") } }
        )
    }
}

// ================================================================ A-10

/**
 * A-10. Pestaña «Unirme con código» para integrantes y «Coordinar» para quien coordina: código de invitación
 * con vigencia, regeneración y administración de integrantes.
 */
@Composable
fun PantallaCirculo(
    estado: EstadoRedApoyo,
    usuarioId: String?,
    /** Solo el perfil Coordinador ve la pestaña «Coordinar» (A-10). */
    puedeCoordinar: Boolean,
    escuela: String?,
    onCargar: () -> Unit,
    onUnirse: (String) -> Unit,
    onCrear: (String) -> Unit,
    onRegenerar: () -> Unit,
    onDarDeBaja: (String) -> Unit,
    onRetirarme: () -> Unit,
    onVolver: () -> Unit
) {
    LaunchedEffect(Unit) { onCargar() }
    val context = LocalContext.current
    val circulo = estado.circulo
    val coordino = circulo != null && circulo.coordinadorId == usuarioId
    var pestana by rememberSaveable { mutableStateOf(if (coordino) 1 else 0) }
    LaunchedEffect(coordino) { if (coordino) pestana = 1 }
    var codigo by rememberSaveable { mutableStateOf("") }
    var nombreGrupo by rememberSaveable { mutableStateOf("") }
    var bajaDe by rememberSaveable { mutableStateOf<String?>(null) }
    var salir by rememberSaveable { mutableStateOf(false) }
    val errores = estado.retro.errores

    PantallaConPie(
        titulo = "Mi círculo",
        onVolver = onVolver,
        pie = { NotaPie("Si un integrante activa una alerta, todos los miembros del círculo reciben aviso con su ubicación.") }
    ) {
        if (puedeCoordinar) Segmentado(listOf(0 to "Unirme con código", 1 to "Coordinar"), pestana, { pestana = it })
        MensajeError(estado.retro.mensaje)

        if (circulo != null && (pestana == 1 || !coordino)) {
            TarjetaGrupo(
                circulo = circulo,
                escuela = escuela,
                integrantes = estado.miembros.count { it.estado == MiembroEstado.ACTIVA },
                coordino = coordino,
                onCompartir = {
                    val texto = "Únete a mi círculo «${circulo.nombreGrupo}» en Sendero Seguro con el código ${circulo.codigoInvitacion}"
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto), "Compartir código"))
                },
                onRegenerar = onRegenerar
            )
            Text("Integrantes", style = MaterialTheme.typography.titleSmall, color = Tinta, modifier = Modifier.padding(top = 4.dp))
            estado.miembros.forEachIndexed { i, m ->
                if (i > 0) HorizontalDivider(color = Borde)
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(m.nombre, tamano = 36)
                    Spacer(Modifier.width(12.dp))
                    Text(m.nombre, style = MaterialTheme.typography.bodyLarge, color = Tinta, modifier = Modifier.weight(1f))
                    if (coordino && m.estado == MiembroEstado.ACTIVA && m.usuarioId != usuarioId) {
                        TextButton(onClick = { bajaDe = m.usuarioId }) { Text("Dar de baja", color = RojoTexto) }
                    } else {
                        when (m.estado) {
                            MiembroEstado.ACTIVA -> Chip("ACTIVA", AmarilloTinte, AmarilloTexto)
                            MiembroEstado.RETIRADA -> Chip("RETIRADA", Borde, Tinta)
                            MiembroEstado.DADA_DE_BAJA -> Chip("DADA DE BAJA", Borde, Tinta)
                        }
                    }
                }
            }
            if (!coordino) BotonSendero("Salir del círculo", { salir = true }, tipo = TipoBoton.CONTORNO)
        } else if (pestana == 0) {
            Text(
                "Pide a quien coordina tu grupo el código de invitación (formato AAA-999).",
                style = MaterialTheme.typography.bodyMedium, color = TextoSecundario
            )
            CampoTexto(
                "Código de invitación", codigo,
                { v -> codigo = v.uppercase().filter { it.isLetterOrDigit() || it == '-' }.take(7) },
                error = errores[Validaciones.CODIGO]
            )
            BotonSendero("Unirme", { onUnirse(codigo) }, habilitado = codigo.length == 7 && !estado.retro.cargando)
        } else {
            Text(
                "Crea el círculo de tu grupo escolar y comparte el código con tus compañeros.",
                style = MaterialTheme.typography.bodyMedium, color = TextoSecundario
            )
            CampoTexto("Nombre del grupo", nombreGrupo, { nombreGrupo = it.take(40) }, error = errores[Validaciones.NOMBRE_GRUPO])
            BotonSendero("Crear círculo", { onCrear(nombreGrupo.trim()) }, habilitado = nombreGrupo.trim().length >= 2 && !estado.retro.cargando)
        }
    }

    bajaDe?.let { id ->
        val nombre = estado.miembros.firstOrNull { it.usuarioId == id }?.nombre.orEmpty()
        AlertDialog(
            onDismissRequest = { bajaDe = null },
            title = { Text("¿Dar de baja a $nombre?") },
            text = { Text("Dejará de recibir las alertas del círculo.") },
            confirmButton = { TextButton(onClick = { onDarDeBaja(id); bajaDe = null }) { Text("Dar de baja", color = RojoTexto) } },
            dismissButton = { TextButton(onClick = { bajaDe = null }) { Text("Cancelar") } }
        )
    }
    if (salir) {
        AlertDialog(
            onDismissRequest = { salir = false },
            title = { Text("¿Salir del círculo?") },
            text = { Text("Dejarás de recibir sus alertas y ellos las tuyas.") },
            confirmButton = { TextButton(onClick = { onRetirarme(); salir = false }) { Text("Salir", color = RojoTexto) } },
            dismissButton = { TextButton(onClick = { salir = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun TarjetaGrupo(
    circulo: Circulo,
    escuela: String?,
    integrantes: Int,
    coordino: Boolean,
    onCompartir: () -> Unit,
    onRegenerar: () -> Unit
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Tinta).padding(18.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Grupo ${circulo.nombreGrupo}", style = MaterialTheme.typography.titleLarge, color = Blanco)
            Spacer(Modifier.width(8.dp))
            Text(listOfNotNull(escuela, "$integrantes integrante${if (integrantes == 1) "" else "s"}").joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Blanco.copy(alpha = 0.8f))
        }
        if (coordino && circulo.codigoInvitacion != null) {
            Text("Código de invitación", style = MaterialTheme.typography.bodySmall, color = Blanco.copy(alpha = 0.8f), modifier = Modifier.padding(top = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(circulo.codigoInvitacion, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = 3.sp, color = Blanco, maxLines = 1)
                Spacer(Modifier.width(10.dp))
                if (circulo.estadoCodigo == CodigoEstado.VIGENTE) Chip("VIGENTE", Amarillo, Tinta) else Chip("VENCIDO", Borde, Tinta)
            }
            circulo.codigoVenceEnMs?.let {
                Text("Vence el ${fechaHoraDe(it)}", style = MaterialTheme.typography.bodySmall, color = Blanco.copy(alpha = 0.8f))
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Blanco).clickable(role = Role.Button, onClick = onCompartir).padding(14.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Compartir", style = MaterialTheme.typography.labelLarge, color = Tinta) }
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Tinta)
                        .then(Modifier.padding(0.dp))
                        .clickable(role = Role.Button, onClick = onRegenerar)
                        .background(Blanco.copy(alpha = 0.08f))
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Generar nuevo", style = MaterialTheme.typography.labelLarge, color = Blanco) }
            }
        }
    }
}
