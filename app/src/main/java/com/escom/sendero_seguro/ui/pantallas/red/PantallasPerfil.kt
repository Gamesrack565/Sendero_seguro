package com.escom.sendero_seguro.ui.pantallas.red

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.escom.sendero_seguro.identidad.dominio.ConfigActivacion
import com.escom.sendero_seguro.identidad.dominio.PoliticaEscaneo
import com.escom.sendero_seguro.identidad.dominio.UsuarioRol
import com.escom.sendero_seguro.identidad.dominio.Validaciones
import com.escom.sendero_seguro.identidad.ui.EstadoAutenticacion
import com.escom.sendero_seguro.ui.componentes.Avatar
import com.escom.sendero_seguro.ui.componentes.BotonSendero
import com.escom.sendero_seguro.ui.componentes.CampoTexto
import com.escom.sendero_seguro.ui.componentes.FilaInterruptor
import com.escom.sendero_seguro.ui.componentes.FilaValor
import com.escom.sendero_seguro.ui.componentes.GrupoFilas
import com.escom.sendero_seguro.ui.componentes.MensajeError
import com.escom.sendero_seguro.ui.componentes.NotaInfo
import com.escom.sendero_seguro.ui.componentes.PantallaConPie
import com.escom.sendero_seguro.ui.componentes.Segmentado
import com.escom.sendero_seguro.ui.componentes.TipoBoton
import com.escom.sendero_seguro.ui.componentes.TituloSeccion
import com.escom.sendero_seguro.ui.theme.Blanco
import com.escom.sendero_seguro.ui.theme.Malva
import com.escom.sendero_seguro.ui.theme.RojoTexto
import com.escom.sendero_seguro.ui.theme.TextoSecundario
import com.escom.sendero_seguro.ui.theme.Tinta
import com.escom.sendero_seguro.ui.util.fechaHoraDe
import com.escom.sendero_seguro.ui.util.formatoTelefono
import com.escom.sendero_seguro.ui.util.megas

// Bloque 3 · Perfil: A-11 Perfil, A-12 Activación y energía (CU-03, CU-27, CU-11 · RF-1, RF-5, RF-18).

// ================================================================ A-11

/** Datos para A-11 que no vienen del perfil. */
data class ResumenPerfil(
    val contactos: Int,
    val circulo: String?,
    val coordinaCirculo: Boolean,
    val activacionSinDesbloquear: Boolean,
    val escuela: String?
)

/** A-11. Datos personales, seguridad y privacidad; incluye la autorización de entrenamiento de IA y la baja. */
@Composable
fun PantallaPerfil(
    estado: EstadoAutenticacion,
    resumen: ResumenPerfil,
    esDesarrollo: Boolean,
    onCargar: () -> Unit,
    onActualizar: (telefono: String?, matricula: String?, carrera: String?, aceptaIa: Boolean?) -> Unit,
    onCambiarPin: (actual: String, nuevo: String, confirmacion: String) -> Unit,
    onSolicitarBaja: (contrasena: String) -> Unit,
    onActivacion: () -> Unit,
    onContactos: () -> Unit,
    onCerrarSesion: () -> Unit,
    onHerramientas: () -> Unit,
    onLimpiarRetro: () -> Unit
) {
    LaunchedEffect(Unit) { onCargar() }
    val u = estado.usuario
    var editar by rememberSaveable { mutableStateOf<String?>(null) }
    var cambiarPin by rememberSaveable { mutableStateOf(false) }
    var baja by rememberSaveable { mutableStateOf(false) }
    var salir by rememberSaveable { mutableStateOf(false) }

    PantallaConPie(
        titulo = null,
        onVolver = null,
        pie = { BotonSendero("Cerrar sesión", { salir = true }, tipo = TipoBoton.CONTORNO) }
    ) {
        Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(u?.nombre ?: "?", tamano = 60, fondo = Malva, color = Blanco)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(u?.nombre ?: "Mi perfil", style = MaterialTheme.typography.titleLarge, color = Tinta)
                val rol = when (u?.rol) {
                    UsuarioRol.COORDINADOR -> "Coordinador"
                    UsuarioRol.OPERADOR -> "Operador de atención"
                    UsuarioRol.ADMINISTRADOR -> "Administrador"
                    else -> "Integrante"
                }
                Text(
                    listOfNotNull(rol, resumen.circulo?.let { if (resumen.coordinaCirculo) "Coordina el $it" else "Círculo $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = TextoSecundario
                )
            }
        }
        MensajeError(estado.retro.mensaje?.takeIf { it != "PIN actualizado" })
        estado.retro.mensaje?.takeIf { it == "PIN actualizado" }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tinta) }

        TituloSeccion("Datos personales")
        GrupoFilas(
            filas = listOf(
                { FilaValor("Teléfono móvil", u?.telefonoMovil?.let(::formatoTelefono) ?: "—", onClick = { onLimpiarRetro(); editar = "telefono" }) },
                { FilaValor("Matrícula", u?.identificadorInstitucional ?: "—", onClick = { onLimpiarRetro(); editar = "matricula" }) },
                { FilaValor("Escuela y carrera", listOfNotNull(resumen.escuela, u?.carreraAdscripcion).joinToString(" · ").ifBlank { "—" }, onClick = { onLimpiarRetro(); editar = "carrera" }) }
            )
        )
        TituloSeccion("Seguridad")
        GrupoFilas(
            filas = listOf(
                { FilaValor("PIN de cancelación", "Cambiar", colorValor = Malva, onClick = { onLimpiarRetro(); cambiarPin = true }) },
                { FilaValor("Activación sin desbloquear", if (resumen.activacionSinDesbloquear) "Activa" else "Inactiva", onClick = onActivacion) },
                { FilaValor("Contactos de confianza", "${resumen.contactos}", onClick = onContactos) }
            )
        )
        TituloSeccion("Privacidad")
        GrupoFilas(
            filas = listOf(
                { FilaValor("Aviso de privacidad", estado.aviso?.version?.let { "${if (it.startsWith("v")) it else "v$it"} · aceptado" } ?: "Aceptado") },
                {
                    FilaInterruptor(
                        "Usar mis reportes para mejorar la IA",
                        u?.aceptaEntrenamientoIa == true,
                        { onActualizar(null, null, null, it) },
                        subtitulo = "Solo reportes anonimizados",
                        habilitado = u != null
                    )
                },
                {
                    Text(
                        "Solicitar baja de mi cuenta",
                        style = MaterialTheme.typography.labelLarge,
                        color = RojoTexto,
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onLimpiarRetro(); baja = true }.padding(16.dp)
                    )
                }
            )
        )
        if (esDesarrollo) {
            TituloSeccion("Desarrollo")
            GrupoFilas(filas = listOf({ FilaValor("Herramientas de prueba", "Abrir", onClick = onHerramientas) }))
        }
    }

    editar?.let { campo ->
        var valor by rememberSaveable(campo) {
            mutableStateOf(
                when (campo) {
                    "telefono" -> u?.telefonoMovil
                    "matricula" -> u?.identificadorInstitucional
                    else -> u?.carreraAdscripcion
                }.orEmpty()
            )
        }
        val (titulo, clave) = when (campo) {
            "telefono" -> "Teléfono móvil" to Validaciones.TELEFONO
            "matricula" -> "Matrícula o n.º de empleado" to Validaciones.IDENTIFICADOR
            else -> "Carrera o adscripción" to Validaciones.CARRERA
        }
        LaunchedEffect(estado.usuario) {
            // Se cierra cuando el perfil vuelve actualizado sin errores.
            val actual = when (campo) {
                "telefono" -> estado.usuario?.telefonoMovil
                "matricula" -> estado.usuario?.identificadorInstitucional
                else -> estado.usuario?.carreraAdscripcion
            }
            if (actual == valor && estado.retro.errores.isEmpty() && !estado.retro.cargando && valor.isNotBlank()) editar = null
        }
        AlertDialog(
            onDismissRequest = { editar = null },
            title = { Text(titulo) },
            text = {
                CampoTexto(
                    titulo, valor, { valor = if (campo == "telefono") it.filter(Char::isDigit).take(10) else it },
                    error = estado.retro.errores[clave] ?: estado.retro.mensaje,
                    teclado = if (campo == "telefono") KeyboardType.Phone else KeyboardType.Text
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    when (campo) {
                        "telefono" -> onActualizar(valor, null, null, null)
                        "matricula" -> onActualizar(null, valor, null, null)
                        else -> onActualizar(null, null, valor, null)
                    }
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { editar = null }) { Text("Cancelar") } }
        )
    }

    if (cambiarPin) {
        var actual by rememberSaveable { mutableStateOf("") }
        var nuevo by rememberSaveable { mutableStateOf("") }
        var confirmacion by rememberSaveable { mutableStateOf("") }
        LaunchedEffect(estado.retro.mensaje) { if (estado.retro.mensaje == "PIN actualizado") cambiarPin = false }
        AlertDialog(
            onDismissRequest = { cambiarPin = false },
            title = { Text("Cambiar PIN de cancelación") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val e = estado.retro.errores
                    CampoTexto("PIN actual", actual, { actual = it.filter(Char::isDigit).take(4) }, oculto = true, teclado = KeyboardType.NumberPassword, error = e["pinActual"])
                    CampoTexto("PIN nuevo", nuevo, { nuevo = it.filter(Char::isDigit).take(4) }, oculto = true, teclado = KeyboardType.NumberPassword, error = e[Validaciones.PIN])
                    CampoTexto("Confírmalo", confirmacion, { confirmacion = it.filter(Char::isDigit).take(4) }, oculto = true, teclado = KeyboardType.NumberPassword, error = e[Validaciones.PIN_CONFIRMACION])
                    MensajeError(estado.retro.mensaje?.takeIf { it != "PIN actualizado" })
                }
            },
            confirmButton = { TextButton(onClick = { onCambiarPin(actual, nuevo, confirmacion) }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { cambiarPin = false }) { Text("Cancelar") } }
        )
    }

    if (baja) {
        var contrasena by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { baja = false },
            title = { Text("Solicitar baja de tu cuenta") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tu cuenta pasará a baja solicitada y se cerrará la sesión en este teléfono. Escribe tu contraseña para confirmar.")
                    CampoTexto("Contraseña", contrasena, { contrasena = it }, oculto = true, error = estado.retro.errores[Validaciones.CONTRASENA] ?: estado.retro.mensaje)
                }
            },
            confirmButton = { TextButton(onClick = { onSolicitarBaja(contrasena) }, enabled = contrasena.isNotEmpty()) { Text("Solicitar baja", color = RojoTexto) } },
            dismissButton = { TextButton(onClick = { baja = false }) { Text("Cancelar") } }
        )
    }

    if (salir) {
        AlertDialog(
            onDismissRequest = { salir = false },
            title = { Text("¿Cerrar sesión?") },
            text = { Text("Mientras no inicies sesión, el botón de pánico no podrá identificarte.") },
            confirmButton = { TextButton(onClick = { salir = false; onCerrarSesion() }) { Text("Cerrar sesión") } },
            dismissButton = { TextButton(onClick = { salir = false }) { Text("Cancelar") } }
        )
    }
}

// ================================================================ A-12

/** Última descarga de mapas para A-12 (cache_mapa.descargado_en). */
data class DescargaMapas(val enMs: Long?, val bytes: Long)

/** A-12. Accesos sin desbloquear, duración de la cuenta regresiva y consumo de energía de la malla y las descargas. */
@Composable
fun PantallaActivacionEnergia(
    config: ConfigActivacion,
    politica: PoliticaEscaneo?,
    error: String?,
    descarga: DescargaMapas,
    requiereExclusion: Boolean,
    onAtajo: (Boolean) -> Unit,
    onWidget: (Boolean) -> Unit,
    onCuenta: (Int) -> Unit,
    onRetransmitir: (Boolean) -> Unit,
    onAhorro: (Boolean) -> Unit,
    onMapasWifi: (Boolean) -> Unit,
    onSolicitarExclusion: () -> Unit,
    onVolver: () -> Unit
) {
    PantallaConPie(titulo = "Activación y energía", onVolver = onVolver) {
        TituloSeccion("Activar sin desbloquear")
        GrupoFilas(
            filas = listOf(
                { FilaInterruptor("Botón de encendido 5 veces", config.atajoActivo, onAtajo, subtitulo = "Funciona con la pantalla bloqueada") },
                { FilaInterruptor("Widget en pantalla de inicio", config.widgetActivo, onWidget, subtitulo = "Botón rojo de un toque") }
            )
        )
        TituloSeccion("Cuenta regresiva antes de enviar")
        Segmentado(listOf(3 to "3 s", 5 to "5 s", 10 to "10 s"), config.cuentaRegresivaSeg, onCuenta)
        MensajeError(error)
        TituloSeccion("Red malla y batería")
        GrupoFilas(
            filas = listOf(
                {
                    FilaInterruptor(
                        "Retransmitir alertas de otros", config.retransmitirMalla, onRetransmitir,
                        subtitulo = "Tu teléfono ayuda a que una alerta sin internet llegue a la autoridad"
                    )
                },
                { FilaInterruptor("Escaneo según batería", config.ahorroBateria, onAhorro, subtitulo = politica?.descripcion ?: "Calculando…") },
                {
                    FilaInterruptor(
                        "Descargar mapas solo con Wi-Fi", config.mapasSoloWifi, onMapasWifi,
                        subtitulo = descarga.enMs?.let { "Última descarga: ${fechaHoraDe(it)} · ${megas(descarga.bytes)}" } ?: "Aún no hay mapas descargados"
                    )
                }
            )
        )
        NotaInfo("Si activas el ahorro de energía de Android, la app pedirá excluirse para no perder alertas de la malla.")
        if (requiereExclusion) {
            BotonSendero("Permitir que funcione en segundo plano", onSolicitarExclusion, tipo = TipoBoton.SECUNDARIO)
        }
    }
}
