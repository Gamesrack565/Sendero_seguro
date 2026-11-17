package com.escom.sendero_seguro.ui.pantallas.acceso

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.escom.sendero_seguro.identidad.dominio.DatosRegistro
import com.escom.sendero_seguro.identidad.dominio.TipoComunidad
import com.escom.sendero_seguro.identidad.dominio.Validaciones
import com.escom.sendero_seguro.identidad.ui.EstadoAutenticacion
import com.escom.sendero_seguro.ui.componentes.BotonSendero
import com.escom.sendero_seguro.ui.componentes.CampoTexto
import com.escom.sendero_seguro.ui.componentes.MensajeError
import com.escom.sendero_seguro.ui.componentes.NotaPie
import com.escom.sendero_seguro.ui.componentes.PantallaConPie
import com.escom.sendero_seguro.ui.componentes.PuntosPin
import com.escom.sendero_seguro.ui.componentes.Segmentado
import com.escom.sendero_seguro.ui.componentes.Tarjeta
import com.escom.sendero_seguro.ui.componentes.TecladoPin
import com.escom.sendero_seguro.ui.componentes.TipoBoton
import com.escom.sendero_seguro.ui.theme.Blanco
import com.escom.sendero_seguro.ui.theme.Borde
import com.escom.sendero_seguro.ui.theme.Enlace
import com.escom.sendero_seguro.ui.theme.Fondo
import com.escom.sendero_seguro.ui.theme.Malva
import com.escom.sendero_seguro.ui.theme.Rojo
import com.escom.sendero_seguro.ui.theme.RojoTexto
import com.escom.sendero_seguro.ui.theme.Superficie
import com.escom.sendero_seguro.ui.theme.Tinta
import com.escom.sendero_seguro.ui.theme.TextoSecundario

// Bloque 1 · Acceso: A-01 Aviso de privacidad, A-02 Registro (+ PIN), A-03 Inicio de sesión (CU-01, CU-02 · RF-1, RF-2).

// ================================================================ A-01

/** A-01. Sin aceptación no se guarda nada (flujo 2a de CU-01). */
@Composable
fun PantallaAvisoPrivacidad(
    estado: EstadoAutenticacion,
    onCargar: () -> Unit,
    onResponder: (aceptado: Boolean) -> Unit,
    onVolver: (() -> Unit)? = null
) {
    LaunchedEffect(Unit) { if (estado.aviso == null) onCargar() }
    var acepto by rememberSaveable { mutableStateOf(false) }
    var verCompleto by rememberSaveable { mutableStateOf(false) }
    val version = estado.aviso?.version ?: "v3.1"

    PantallaConPie(
        titulo = null,
        onVolver = onVolver,
        pie = {
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { acepto = !acepto },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = acepto, onCheckedChange = { acepto = it },
                    colors = CheckboxDefaults.colors(checkedColor = Malva, uncheckedColor = TextoSecundario)
                )
                Text(
                    "He leído y acepto el aviso de privacidad y el tratamiento de mis datos (LFPDPPP).",
                    style = MaterialTheme.typography.bodyMedium, color = Tinta
                )
            }
            MensajeError(estado.retro.mensaje)
            BotonSendero(
                "Aceptar y continuar", { onResponder(true) },
                habilitado = acepto && estado.aviso != null && !estado.retro.cargando
            )
            TextButton(onClick = { onResponder(false) }, modifier = Modifier.fillMaxWidth()) {
                Text("No acepto", style = MaterialTheme.typography.labelLarge, color = TextoSecundario)
            }
        }
    ) {
        Spacer(Modifier.size(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Shield, contentDescription = null, tint = Malva, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text("Sendero Seguro", style = MaterialTheme.typography.titleLarge, color = Tinta)
        }
        Text("Antes de empezar, tu privacidad", style = MaterialTheme.typography.headlineSmall, color = Tinta)
        Text(
            "Para ayudarte en una emergencia necesitamos tratar algunos datos. Esto es lo que usamos y para qué:",
            style = MaterialTheme.typography.bodyLarge, color = Tinta
        )
        DatoTratado(Icons.Outlined.LocationOn, "Ubicación", "Solo mientras hay una alerta activa o pides una ruta.")
        DatoTratado(Icons.Outlined.Person, "Identificación", "Nombre, móvil y matrícula, para que la autoridad sepa quién pide ayuda.")
        DatoTratado(
            Icons.Outlined.AutoAwesome, "Reportes y asistente IA",
            "Tus reportes se publican con ubicación aproximada. La IA los analiza sin tu identidad."
        )
        Text(
            "Leer el aviso de privacidad completo ($version)",
            style = MaterialTheme.typography.labelMedium.copy(textDecoration = TextDecoration.Underline),
            color = Enlace,
            modifier = Modifier.clickable(role = Role.Button) { verCompleto = true }.padding(vertical = 8.dp)
        )
    }

    if (verCompleto) {
        AlertDialog(
            onDismissRequest = { verCompleto = false },
            confirmButton = { TextButton(onClick = { verCompleto = false }) { Text("Cerrar") } },
            title = { Text("Aviso de privacidad $version") },
            text = {
                Text(
                    "Responsable: Sendero Seguro (IPN). Datos: nombre, teléfono móvil, matrícula o número de empleado, " +
                        "escuela, carrera, ubicación durante una alerta o al pedir una ruta, y los reportes que levantes. " +
                        "Finalidades: canalizar tus alertas a la unidad de atención, avisar a tu red de apoyo y publicar " +
                        "reportes comunitarios con ubicación aproximada. Tus reportes solo se usan para mejorar la IA si lo " +
                        "autorizas en tu perfil. Puedes solicitar la baja de tu cuenta desde Perfil.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        )
    }
}

@Composable
private fun DatoTratado(icono: ImageVector, titulo: String, texto: String) {
    Tarjeta {
        Row {
            Icon(icono, contentDescription = null, tint = Malva, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(titulo, style = MaterialTheme.typography.titleSmall, color = Tinta)
                Text(texto, style = MaterialTheme.typography.bodyMedium, color = TextoSecundario)
            }
        }
    }
}

// ================================================================ A-02

/** A-02. Paso 1: datos que pidió el cliente. Paso 2: PIN de cancelación (se guarda solo su hash en el Keystore). */
@Composable
fun PantallaRegistro(
    estado: EstadoAutenticacion,
    onCargarInstituciones: () -> Unit,
    onElegirInstitucion: (Int) -> Unit,
    onValidarDatos: (DatosRegistro) -> Boolean,
    onRegistrar: (DatosRegistro, String, String) -> Unit,
    onYaTengoCuenta: () -> Unit,
    onVolver: () -> Unit,
    onLimpiarRetro: () -> Unit
) {
    LaunchedEffect(Unit) { if (estado.instituciones.isEmpty()) onCargarInstituciones() }
    var paso by rememberSaveable { mutableStateOf(1) }
    var nombre by rememberSaveable { mutableStateOf("") }
    var telefono by rememberSaveable { mutableStateOf("") }
    var identificador by rememberSaveable { mutableStateOf("") }
    var tipo by rememberSaveable { mutableStateOf(TipoComunidad.ALUMNO) }
    var institucionId by rememberSaveable { mutableStateOf<Int?>(null) }
    var escuelaId by rememberSaveable { mutableStateOf<Int?>(null) }
    var carrera by rememberSaveable { mutableStateOf("") }
    var contrasena by rememberSaveable { mutableStateOf("") }

    val datos = DatosRegistro(nombre.trim(), telefono.filter(Char::isDigit), identificador.trim(), tipo, institucionId, escuelaId, carrera.trim(), contrasena)
    val errores = estado.retro.errores

    if (paso == 2) {
        PasoCrearPin(
            titulo = "Crear cuenta",
            subtitulo = "Paso 2 de 2",
            explicacion = "Con este PIN cancelas una alerta. Elige 4 dígitos que recuerdes; nunca sale de tu teléfono.",
            cargando = estado.retro.cargando,
            errores = errores,
            mensaje = estado.retro.mensaje,
            textoBoton = "Crear cuenta",
            onVolver = { onLimpiarRetro(); paso = 1 },
            onConfirmar = { pin, confirmacion -> onRegistrar(datos, pin, confirmacion) }
        )
        return
    }

    PantallaConPie(
        titulo = "Crear cuenta",
        onVolver = onVolver,
        extraEncabezado = { Text("Paso 1 de 2", style = MaterialTheme.typography.bodyMedium, color = TextoSecundario) },
        pie = {
            MensajeError(estado.retro.mensaje)
            BotonSendero("Continuar: crear PIN de cancelación", { if (onValidarDatos(datos)) paso = 2 })
            TextButton(onClick = onYaTengoCuenta, modifier = Modifier.fillMaxWidth()) {
                Text("Ya tengo cuenta: iniciar sesión", style = MaterialTheme.typography.labelLarge, color = Malva)
            }
        }
    ) {
        CampoTexto("Nombre completo", nombre, { nombre = it }, error = errores[Validaciones.NOMBRE])
        CampoTexto(
            "Teléfono móvil", telefono, { telefono = it.filter(Char::isDigit).take(10) },
            error = errores[Validaciones.TELEFONO], teclado = KeyboardType.Phone
        )
        CampoTexto("Matrícula o n.º de empleado", identificador, { identificador = it.take(15) }, error = errores[Validaciones.IDENTIFICADOR])
        Segmentado(
            listOf(TipoComunidad.ALUMNO to "Alumno", TipoComunidad.DOCENTE to "Docente", TipoComunidad.PERSONAL to "Personal"),
            tipo, { tipo = it }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Lista(
                "Institución",
                estado.instituciones.map { it.id to (it.siglas ?: it.nombre) },
                institucionId,
                { institucionId = it; escuelaId = null; onElegirInstitucion(it) },
                errores[Validaciones.INSTITUCION],
                Modifier.weight(1f)
            )
            Lista(
                "Escuela",
                estado.escuelas.map { it.id to it.nombre },
                escuelaId,
                { escuelaId = it },
                errores[Validaciones.ESCUELA],
                Modifier.weight(1f)
            )
        }
        CampoTexto("Carrera o adscripción", carrera, { carrera = it }, error = errores[Validaciones.CARRERA])
        CampoTexto(
            "Contraseña", contrasena, { contrasena = it }, oculto = true,
            error = errores[Validaciones.CONTRASENA], ayuda = "Mínimo 8 caracteres, con una letra y un número."
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Lista(
    etiqueta: String,
    opciones: List<Pair<Int, String>>,
    seleccion: Int?,
    onElegir: (Int) -> Unit,
    error: String?,
    modifier: Modifier = Modifier
) {
    var abierta by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        Text(etiqueta, style = MaterialTheme.typography.labelMedium, color = Tinta)
        ExposedDropdownMenuBox(expanded = abierta, onExpandedChange = { abierta = it }, modifier = Modifier.padding(top = 6.dp)) {
            OutlinedTextField(
                value = opciones.firstOrNull { it.first == seleccion }?.second ?: "",
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                isError = error != null,
                textStyle = MaterialTheme.typography.bodyLarge,
                shape = RoundedCornerShape(12.dp),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(abierta) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Superficie, unfocusedContainerColor = Superficie,
                    unfocusedBorderColor = Borde, focusedBorderColor = Malva, errorBorderColor = RojoTexto
                ),
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(expanded = abierta, onDismissRequest = { abierta = false }) {
                if (opciones.isEmpty()) {
                    DropdownMenuItem(text = { Text("Sin opciones") }, onClick = { abierta = false }, enabled = false)
                }
                opciones.forEach { (id, texto) ->
                    DropdownMenuItem(text = { Text(texto) }, onClick = { onElegir(id); abierta = false })
                }
            }
        }
        if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = RojoTexto, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * Paso del PIN de cancelación: se escribe dos veces con el teclado numérico.
 * Lo usan A-02 (paso 2) y el inicio de sesión en un teléfono sin PIN.
 */
@Composable
fun PasoCrearPin(
    titulo: String,
    subtitulo: String?,
    explicacion: String,
    cargando: Boolean,
    errores: Map<String, String>,
    mensaje: String?,
    textoBoton: String,
    onVolver: (() -> Unit)?,
    onConfirmar: (String, String) -> Unit
) {
    var pin by rememberSaveable { mutableStateOf("") }
    var confirmacion by rememberSaveable { mutableStateOf("") }
    val confirmando = pin.length == 4
    PantallaConPie(
        titulo = titulo,
        onVolver = onVolver,
        extraEncabezado = subtitulo?.let { { Text(it, style = MaterialTheme.typography.bodyMedium, color = TextoSecundario) } },
        pie = {
            MensajeError(mensaje)
            BotonSendero(textoBoton, { onConfirmar(pin, confirmacion) }, habilitado = pin.length == 4 && confirmacion.length == 4 && !cargando)
        }
    ) {
        Text(explicacion, style = MaterialTheme.typography.bodyLarge, color = Tinta)
        Text(
            if (!confirmando) "Escribe tu PIN de cancelación" else "Confírmalo",
            style = MaterialTheme.typography.titleMedium, color = Tinta,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PuntosPin(if (!confirmando) pin.length else confirmacion.length)
        }
        val error = errores[Validaciones.PIN] ?: errores[Validaciones.PIN_CONFIRMACION]
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = RojoTexto, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        TecladoPin(
            pin = if (!confirmando) pin else confirmacion,
            onCambio = { nuevo ->
                if (!confirmando) pin = nuevo
                else if (nuevo.isEmpty() && confirmacion.isEmpty()) pin = pin.dropLast(1)
                else confirmacion = nuevo
            }
        )
    }
}

// ================================================================ A-03

/** A-03. Acceso con teléfono o matrícula; a los 5 intentos fallidos la cuenta queda BLOQUEADA_TEMPORAL. */
@Composable
fun PantallaInicioSesion(
    estado: EstadoAutenticacion,
    onIniciar: (usuario: String, contrasena: String) -> Unit,
    onCrearCuenta: () -> Unit,
    onOlvide: () -> Unit
) {
    var usuario by rememberSaveable { mutableStateOf("") }
    var contrasena by rememberSaveable { mutableStateOf("") }
    val errores = estado.retro.errores

    Column(Modifier.fillMaxSize().background(Fondo).imePadding()) {
        Column(
            Modifier.fillMaxWidth().background(Tinta).statusBarsPadding().padding(horizontal = 28.dp, vertical = 28.dp)
        ) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(Rojo), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Shield, contentDescription = null, tint = Blanco, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.size(18.dp))
            Text("Sendero Seguro", style = MaterialTheme.typography.displaySmall, color = Blanco)
            Text("Cuidarnos entre todos, con o sin señal.", style = MaterialTheme.typography.bodyLarge, color = Blanco.copy(alpha = 0.8f))
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CampoTexto("Teléfono móvil o matrícula", usuario, { usuario = it.trim() }, error = errores[Validaciones.USUARIO])
            CampoTexto(
                "Contraseña", contrasena, { contrasena = it }, oculto = true,
                error = errores[Validaciones.CONTRASENA] ?: estado.retro.mensaje
            )
            Text(
                "¿Olvidaste tu contraseña?",
                style = MaterialTheme.typography.labelMedium.copy(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Bold),
                color = Enlace,
                modifier = Modifier.clickable(role = Role.Button, onClick = onOlvide).padding(vertical = 6.dp)
            )
        }
        Column(Modifier.padding(horizontal = 28.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BotonSendero(
                if (estado.retro.cargando) "Entrando…" else "Iniciar sesión",
                { onIniciar(usuario, contrasena) },
                habilitado = usuario.isNotBlank() && contrasena.isNotEmpty() && !estado.retro.cargando
            )
            BotonSendero("Crear cuenta", onCrearCuenta, tipo = TipoBoton.SECUNDARIO)
            NotaPie("Tu sesión queda guardada en el teléfono: el botón de pánico funciona aunque no tengas internet.")
        }
    }
}

/** Mensaje de «¿Olvidaste tu contraseña?»: la recuperación la hace el Administrador. */
@Composable
fun DialogoOlvide(onCerrar: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCerrar,
        confirmButton = { TextButton(onClick = onCerrar) { Text("Entendido") } },
        title = { Text("Recuperar contraseña") },
        text = {
            Text(
                "Acude con el Administrador de Sendero Seguro de tu escuela con tu credencial. Él restablece tu acceso desde la consola.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    )
}
