package com.escom.sendero_seguro.ui.navegacion

import android.app.Activity
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.escom.sendero_seguro.SenderoApp
import com.escom.sendero_seguro.emergencias.EstadoRedYMalla
import com.escom.sendero_seguro.PantallaDiagnosticoModulo2
import com.escom.sendero_seguro.emergencias.TipoDetonador
import com.escom.sendero_seguro.identidad.dominio.MiembroEstado
import com.escom.sendero_seguro.identidad.ui.ActivacionViewModel
import com.escom.sendero_seguro.identidad.ui.AutenticacionViewModel
import com.escom.sendero_seguro.identidad.ui.CancelacionViewModel
import com.escom.sendero_seguro.identidad.ui.RedApoyoViewModel
import com.escom.sendero_seguro.identidad.ui.fabricaViewModels
import com.escom.sendero_seguro.ui.modelo.CanalEnvio
import com.escom.sendero_seguro.ui.pantallas.PantallaEnConstruccion
import com.escom.sendero_seguro.ui.pantallas.PantallaGaleria
import com.escom.sendero_seguro.ui.pantallas.acceso.DialogoOlvide
import com.escom.sendero_seguro.ui.pantallas.acceso.PantallaAvisoPrivacidad
import com.escom.sendero_seguro.ui.pantallas.acceso.PantallaInicioSesion
import com.escom.sendero_seguro.ui.pantallas.acceso.PantallaRegistro
import com.escom.sendero_seguro.ui.pantallas.acceso.PasoCrearPin
import com.escom.sendero_seguro.ui.pantallas.desarrollo.PantallaHerramientas
import com.escom.sendero_seguro.ui.pantallas.inicio.EstadoInicio
import com.escom.sendero_seguro.ui.pantallas.inicio.PantallaInicio
import com.escom.sendero_seguro.ui.pantallas.mapa.PantallaMapaP12
import com.escom.sendero_seguro.ui.pantallas.reporte.PantallaReporteComunitario
import com.escom.sendero_seguro.ui.theme.Fondo
import com.escom.sendero_seguro.ui.theme.RojoTexto
import com.escom.sendero_seguro.ui.theme.Superficie
import com.escom.sendero_seguro.ui.theme.TextoSecundario
import kotlinx.coroutines.launch

private data class Pestana(val pantalla: Pantalla, val etiqueta: String, val icono: ImageVector)

// Barra inferior de A-04: Alerta · Mapa · Reportes · Perfil
private val Pestanas = listOf(
    Pestana(Pantalla.A04, "Alerta", Icons.Outlined.Shield),
    Pestana(Pantalla.A17, "Mapa", Icons.Outlined.Map),
    Pestana(Pantalla.A16, "Reportes", Icons.AutoMirrored.Outlined.Notes),
    Pestana(Pantalla.A11, "Perfil", Icons.Outlined.Person)
)

/** Modo de conectividad de A-04 (E-05) a partir del estado de la red y la malla del Módulo 2. */
internal fun EstadoRedYMalla.canal(): CanalEnvio = when {
    conectadoAInternet -> CanalEnvio.INTERNET
    escaneoBleActivo -> CanalEnvio.MALLA
    else -> CanalEnvio.EN_COLA
}

/**
 * Navegación de la app según el mapa de navegación del E3 (G-02).
 * Sin sesión arranca en A-03; con sesión guardada (aunque no haya internet) arranca en A-04.
 */
@Composable
fun NavegacionApp(nav: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val app = context.applicationContext as SenderoApp
    val esDesarrollo = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    val fabrica = remember { app.identidad.fabricaViewModels() }
    val auth: AutenticacionViewModel = viewModel(factory = fabrica)
    val red: RedApoyoViewModel = viewModel(factory = fabrica)
    val activacion: ActivacionViewModel = viewModel(factory = fabrica)
    val cancelacion: CancelacionViewModel = viewModel(factory = fabrica)

    val inicio by produceState<Pantalla?>(null) {
        value = if (app.identidad.autenticacion.sesionLocal() != null) Pantalla.A04 else Pantalla.A03
    }
    val destinoInicial = inicio
    if (destinoInicial == null) {
        Box(Modifier.fillMaxSize().background(Fondo))
        return
    }

    val entrada by nav.currentBackStackEntryAsState()
    val actual = Pantalla.deRuta(entrada?.destination?.route)
    val estadoAuth by auth.estado.collectAsState()
    val modulo2 = app.emergencias
    val scope = rememberCoroutineScope()

    // Cerrar la app desde A-01 («No acepto»).
    LaunchedEffect(estadoAuth.salirDeLaApp) { if (estadoAuth.salirDeLaApp) (context as? Activity)?.finish() }

    Scaffold(
        containerColor = Fondo,
        // Las pantallas de alerta no tienen barra inferior, para evitar toques accidentales.
        bottomBar = { if (actual?.conBarra == true) BarraInferior(actual) { nav.irAPestana(it) } }
    ) { relleno ->
        NavHost(nav, startDestination = destinoInicial.ruta, modifier = Modifier.fillMaxSize().padding(relleno)) {

            // ---------------------------------------------------------------- Bloque 1 · Acceso
            composable(Pantalla.A03.ruta) {
                var olvide by remember { mutableStateOf(false) }
                LaunchedEffect(estadoAuth.usuario, estadoAuth.requiereCrearPin) {
                    if (estadoAuth.usuario != null) nav.irA(if (estadoAuth.requiereCrearPin) Pantalla.PIN_NUEVO else Pantalla.A04)
                }
                PantallaInicioSesion(
                    estado = estadoAuth,
                    onIniciar = auth::iniciarSesion,
                    onCrearCuenta = { auth.limpiarRetro(); nav.navigate(Pantalla.A01.ruta) },
                    onOlvide = { olvide = true }
                )
                if (olvide) DialogoOlvide { olvide = false }
            }
            composable(Pantalla.A01.ruta) {
                LaunchedEffect(estadoAuth.avisoAceptado) {
                    if (estadoAuth.avisoAceptado) nav.navigate(Pantalla.A02.ruta) { popUpTo(Pantalla.A01.ruta) { inclusive = true } }
                }
                PantallaAvisoPrivacidad(
                    estado = estadoAuth,
                    onCargar = auth::cargarAviso,
                    onResponder = { aceptado -> auth.responderAviso(aceptado, aceptaEntrenamientoIa = false) },
                    onVolver = { nav.popBackStack() }
                )
            }
            composable(Pantalla.A02.ruta) {
                LaunchedEffect(estadoAuth.usuario) { if (estadoAuth.usuario != null) nav.irA(Pantalla.A04) }
                PantallaRegistro(
                    estado = estadoAuth,
                    onCargarInstituciones = auth::cargarInstituciones,
                    onElegirInstitucion = auth::seleccionarInstitucion,
                    onValidarDatos = auth::validarPasoDatos,
                    onRegistrar = auth::registrar,
                    onYaTengoCuenta = { auth.limpiarRetro(); nav.navigate(Pantalla.A03.ruta) { popUpTo(0) { inclusive = true } } },
                    onVolver = { nav.popBackStack() },
                    onLimpiarRetro = auth::limpiarRetro
                )
            }
            composable(Pantalla.PIN_NUEVO.ruta) {
                LaunchedEffect(estadoAuth.requiereCrearPin) { if (!estadoAuth.requiereCrearPin) nav.irA(Pantalla.A04) }
                PasoCrearPin(
                    titulo = "PIN de cancelación",
                    subtitulo = null,
                    explicacion = "Este teléfono no tiene tu PIN de cancelación. Créalo para poder cancelar una alerta; nunca sale del teléfono.",
                    cargando = estadoAuth.retro.cargando,
                    errores = estadoAuth.retro.errores,
                    mensaje = estadoAuth.retro.mensaje,
                    textoBoton = "Guardar PIN",
                    onVolver = null,
                    onConfirmar = auth::crearPin
                )
            }

            // ---------------------------------------------------------------- A-04 Inicio
            composable(Pantalla.A04.ruta) {
                val redMalla by modulo2.estadoRedYMalla.collectAsState()
                val contactos by red.contactos.collectAsState()
                val estadoRed by red.estado.collectAsState()
                val config by activacion.config.collectAsState()
                LaunchedEffect(Unit) {
                    if (estadoAuth.usuario == null) auth.cargarPerfil()
                    red.cargarCirculo()
                }
                PantallaInicio(
                    estado = EstadoInicio(
                        nombre = estadoAuth.usuario?.nombre?.substringBefore(' ').orEmpty(),
                        canal = redMalla.canal(),
                        contactos = contactos.size,
                        circulo = estadoRed.circulo?.nombreGrupo,
                        integrantesCirculo = estadoRed.miembros.count { it.estado == MiembroEstado.ACTIVA },
                        segundosCuenta = config.cuentaRegresivaSeg
                    ),
                    onPedirAyuda = {
                        modulo2.iniciarCuentaRegresivaYEnvioBase(TipoDetonador.BOTON_SOS, config.cuentaRegresivaSeg)
                        nav.navigate(Pantalla.A05.ruta)
                    },
                    onConectividad = { nav.navigate(Pantalla.A19.ruta) },
                    onRutaSegura = { nav.navigate(Pantalla.A18.ruta) },
                    onReportar = { nav.navigate(Pantalla.A15.ruta) },
                    onRedApoyo = { nav.navigate(Pantalla.A09.ruta) }
                )
            }

            // ---------------------------------------------------------------- Herramientas de prueba (debug)
            composable(Pantalla.HERRAMIENTAS.ruta) {
                val estadoEnvio by modulo2.estadoEnvioAlerta.collectAsState()
                PantallaHerramientas(
                    estadoEnvio = estadoEnvio,
                    mapaP12Habilitado = app.p12LocalHabilitado(),
                    onEnviarPrueba = { datos ->
                        scope.launch {
                            val error = app.prepararSesionPruebaLocal()
                            if (error != null) modulo2.mostrarErrorPreparandoPrueba(error) else modulo2.enviarAlertaPrueba(datos)
                        }
                    },
                    onDiagnostico = { nav.navigate(Pantalla.DIAGNOSTICO.ruta) },
                    onMapaP12 = { nav.navigate(Pantalla.MAPA_P12.ruta) },
                    onReporteP10 = { nav.navigate(Pantalla.REPORTE_P10.ruta) },
                    onGaleria = { nav.navigate(Pantalla.GALERIA.ruta) },
                    onVolver = { nav.popBackStack() }
                )
            }
            composable(Pantalla.DIAGNOSTICO.ruta) {
                Column(Modifier.fillMaxSize()) {
                    com.escom.sendero_seguro.ui.componentes.Encabezado(Pantalla.DIAGNOSTICO.titulo, { nav.popBackStack() })
                    PantallaDiagnosticoModulo2(modulo = modulo2, moduloMapas = app.mapas, modifier = Modifier.weight(1f))
                }
            }
            composable(Pantalla.MAPA_P12.ruta) {
                PantallaMapaP12(moduloMapas = app.mapas, moduloReportes = app.reportes, onVolver = { nav.popBackStack() })
            }
            composable(Pantalla.REPORTE_P10.ruta) {
                PantallaReporteComunitario(
                    moduloReportes = app.reportes,
                    prepararSesion = { app.prepararSesionPruebaLocal() },
                    onVolver = { nav.popBackStack() }
                )
            }
            composable(Pantalla.GALERIA.ruta) { PantallaGaleria(onVolver = { nav.popBackStack() }) }

            // Pantallas de los siguientes bloques.
            val hechas = setOf(
                Pantalla.A01, Pantalla.A02, Pantalla.A03, Pantalla.A04, Pantalla.PIN_NUEVO,
                Pantalla.HERRAMIENTAS, Pantalla.DIAGNOSTICO, Pantalla.MAPA_P12, Pantalla.REPORTE_P10, Pantalla.GALERIA
            )
            Pantalla.entries.filter { it !in hechas }.forEach { p ->
                composable(p.ruta) {
                    val extra = buildList {
                        if (p == Pantalla.A11 && esDesarrollo) add("Herramientas de prueba" to { nav.navigate(Pantalla.HERRAMIENTAS.ruta) })
                        if (p == Pantalla.A11) add("Cerrar sesión" to { auth.cerrarSesion(); nav.navigate(Pantalla.A03.ruta) { popUpTo(0) { inclusive = true } } })
                    }
                    PantallaEnConstruccion(
                        pantalla = p,
                        siguientes = SiguientesPantallas[p].orEmpty(),
                        onIr = { destino -> nav.irA(destino) },
                        onVolver = if (p.conBarra) null else ({ nav.popBackStack() }),
                        extra = extra
                    )
                }
            }
        }
    }
}

/** A-04 es la raíz: llegar a ella (por sesión o al cerrar una alerta) limpia lo anterior. */
internal fun NavHostController.irA(destino: Pantalla) {
    if (destino == Pantalla.A04) {
        navigate(Pantalla.A04.ruta) { popUpTo(0) { inclusive = true } }
    } else {
        navigate(destino.ruta)
    }
}

private fun NavHostController.irAPestana(destino: Pantalla) {
    navigate(destino.ruta) {
        popUpTo(Pantalla.A04.ruta) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun BarraInferior(actual: Pantalla, onElegir: (Pantalla) -> Unit) {
    NavigationBar(containerColor = Superficie) {
        Pestanas.forEach { p ->
            NavigationBarItem(
                selected = p.pantalla == actual,
                onClick = { onElegir(p.pantalla) },
                icon = { Icon(p.icono, contentDescription = null) },
                label = { Text(p.etiqueta, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = RojoTexto,
                    selectedTextColor = RojoTexto,
                    indicatorColor = Superficie,
                    unselectedIconColor = TextoSecundario,
                    unselectedTextColor = TextoSecundario
                )
            )
        }
    }
}
