package com.escom.sendero_seguro.ui.navegacion

import android.app.Activity
import android.content.pm.ApplicationInfo
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.escom.sendero_seguro.identidad.dominio.UsuarioRol
import com.escom.sendero_seguro.identidad.dominio.ResultadoCancelacion
import com.escom.sendero_seguro.reportes.TipoReporteDto
import com.escom.sendero_seguro.ui.mapa.Punto
import com.escom.sendero_seguro.ui.pantallas.alerta.PantallaAlertaActiva
import com.escom.sendero_seguro.ui.pantallas.alerta.PantallaCancelarAlerta
import com.escom.sendero_seguro.ui.pantallas.alerta.PantallaCuentaRegresiva
import com.escom.sendero_seguro.ui.pantallas.alerta.PantallaDescribirVoz
import com.escom.sendero_seguro.ui.pantallas.alerta.RedAvisada
import com.escom.sendero_seguro.ui.pantallas.alerta.aCanal
import com.escom.sendero_seguro.ui.pantallas.alerta.marcar
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
import com.escom.sendero_seguro.ui.pantallas.red.AvisoRedApoyo
import com.escom.sendero_seguro.ui.pantallas.red.DescargaMapas
import com.escom.sendero_seguro.ui.pantallas.red.PantallaActivacionEnergia
import com.escom.sendero_seguro.ui.pantallas.red.PantallaAvisoRedApoyo
import com.escom.sendero_seguro.ui.pantallas.red.PantallaCirculo
import com.escom.sendero_seguro.ui.pantallas.red.PantallaContactos
import com.escom.sendero_seguro.ui.pantallas.red.PantallaPerfil
import com.escom.sendero_seguro.ui.pantallas.red.ResumenPerfil
import com.escom.sendero_seguro.ui.pantallas.inicio.EstadoInicio
import com.escom.sendero_seguro.ui.pantallas.inicio.PantallaInicio
import com.escom.sendero_seguro.ui.pantallas.mapa.PantallaMapaP12
import com.escom.sendero_seguro.ui.pantallas.reporte.PantallaReporteComunitario
import com.escom.sendero_seguro.ui.theme.Fondo
import com.escom.sendero_seguro.ui.theme.Rojo
import com.escom.sendero_seguro.ui.theme.RojoOscuro
import com.escom.sendero_seguro.ui.theme.Tinta
import com.escom.sendero_seguro.ui.theme.RojoTexto
import com.escom.sendero_seguro.ui.theme.Superficie
import com.escom.sendero_seguro.ui.theme.TextoSecundario
import kotlinx.coroutines.delay
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
fun NavegacionApp(
    nav: NavHostController = rememberNavController(),
    /** Aviso de alerta de la red de apoyo que abrió la app (push o enlace): abre A-13. */
    avisoEntrante: AvisoRedApoyo? = null
) {
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
    // Catálogo local de tipos para la alerta (A-05, A-06); sin señal usa el guardado.
    val tiposAlerta by produceState(emptyList<TipoReporteDto>()) { value = app.reportes.cargarCatalogoAlertas() }

    var avisoRed by remember { mutableStateOf<AvisoRedApoyo?>(null) }
    LaunchedEffect(avisoEntrante) { if (avisoEntrante != null) { avisoRed = avisoEntrante; nav.navigate(Pantalla.A13.ruta) } }

    // P3 → P4: la cuenta regresiva elegida en A-12 también la usan el atajo y el widget del Módulo 2.
    val configActivacion by activacion.config.collectAsState()
    LaunchedEffect(configActivacion.cuentaRegresivaSeg) { modulo2.segundosCuentaRegresivaConfig = configActivacion.cuentaRegresivaSeg }

    // A-08 siempre empieza limpio: sin motivo ni el resultado de una cancelación anterior.
    val irACancelar = { cancelacion.reiniciar(); nav.navigate(Pantalla.A08.ruta) }

    // Cerrar la app desde A-01 («No acepto»).
    LaunchedEffect(estadoAuth.salirDeLaApp) { if (estadoAuth.salirDeLaApp) (context as? Activity)?.finish() }

    Scaffold(
        // La franja de la barra de estado toma el color de la pantalla (roja en la alerta, oscura en A-03 y A-13).
        containerColor = when (actual) {
            Pantalla.A05, Pantalla.A06 -> RojoOscuro
            Pantalla.A07 -> Rojo
            Pantalla.A03, Pantalla.A13 -> Tinta
            else -> Fondo
        },
        // Las pantallas de alerta no tienen barra inferior, para evitar toques accidentales.
        bottomBar = { if (actual?.conBarra == true) BarraInferior(actual) { nav.irAPestana(it) } }
    ) { relleno ->
        NavHost(nav, startDestination = destinoInicial.ruta, modifier = Modifier.fillMaxSize().padding(relleno)) {

            // ---------------------------------------------------------------- Bloque 1 · Acceso
            composable(Pantalla.A03.ruta) {
                var olvide by remember { mutableStateOf(false) }
                // Solo se avanza tras pulsar «Iniciar sesión»: al cerrar sesión el estado anterior tarda en limpiarse.
                var intento by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(intento, estadoAuth.usuario, estadoAuth.requiereCrearPin) {
                    if (intento && estadoAuth.usuario != null) nav.irA(if (estadoAuth.requiereCrearPin) Pantalla.PIN_NUEVO else Pantalla.A04)
                }
                PantallaInicioSesion(
                    estado = estadoAuth,
                    onIniciar = { u, c -> intento = true; auth.iniciarSesion(u, c) },
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
                var intento by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(intento, estadoAuth.usuario) { if (intento && estadoAuth.usuario != null) nav.irA(Pantalla.A04) }
                PantallaRegistro(
                    estado = estadoAuth,
                    onCargarInstituciones = auth::cargarInstituciones,
                    onElegirInstitucion = auth::seleccionarInstitucion,
                    onValidarDatos = auth::validarPasoDatos,
                    onRegistrar = { d, pin, conf -> intento = true; auth.registrar(d, pin, conf) },
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

            // ---------------------------------------------------------------- Bloque 2 · Alerta (sin barra inferior)
            composable(Pantalla.A05.ruta) {
                val segundos by modulo2.segundosRestantesPreEnvio.collectAsState()
                val alerta by modulo2.alertaActiva.collectAsState()
                val telemetria by modulo2.flujoTelemetria.collectAsState()
                var elegido by rememberSaveable { mutableStateOf<Int?>(null) }
                var aVoz by remember { mutableStateOf(false) }
                LaunchedEffect(alerta) {
                    if (alerta?.activa == true) {
                        nav.navigate((if (aVoz) Pantalla.A06 else Pantalla.A07).ruta) { popUpTo(Pantalla.A04.ruta) }
                    }
                }
                // Atrás no apaga la cuenta regresiva: cancelar requiere PIN o huella (A-08).
                BackHandler { irACancelar() }
                PantallaCuentaRegresiva(
                    segundos = segundos,
                    precisionM = telemetria?.precision,
                    tipos = tiposAlerta,
                    tipoElegido = elegido,
                    onElegirTipo = { t -> elegido = t.id; modulo2.elegirTipoIncidente(t.id, t.nombre) },
                    onVoz = {
                        aVoz = true
                        modulo2.iniciarCuentaRegresivaYEnvioBase(TipoDetonador.BOTON_SOS, omitirCuentaEnviarYa = true)
                    },
                    onEnviarYa = { modulo2.iniciarCuentaRegresivaYEnvioBase(TipoDetonador.BOTON_SOS, omitirCuentaEnviarYa = true) },
                    onCancelar = { irACancelar() }
                )
            }
            composable(Pantalla.A06.ruta) {
                val alerta by modulo2.alertaActiva.collectAsState()
                val irA07 = { nav.navigate(Pantalla.A07.ruta) { popUpTo(Pantalla.A04.ruta) } }
                BackHandler { irA07() }
                PantallaDescribirVoz(
                    canal = alerta?.canalRecepcion?.aCanal() ?: CanalEnvio.EN_COLA,
                    clasificar = { modulo2.gestorVozPanico.clasificarIncidenteConIA1Local(it) },
                    tipos = tiposAlerta,
                    onUsar = { id, nombre, texto -> modulo2.elegirTipoIncidente(id, nombre, texto.ifBlank { null }); irA07() },
                    onSoloTexto = { texto -> modulo2.agregarDescripcion(texto); irA07() },
                    onOmitir = { irA07() }
                )
            }
            composable(Pantalla.A07.ruta) {
                val alerta by modulo2.alertaActiva.collectAsState()
                val seguimiento by modulo2.vistaSincronizadaAtencion.collectAsState()
                val historial by modulo2.historialEventosAlerta.collectAsState()
                val telemetria by modulo2.flujoTelemetria.collectAsState()
                val contactos by red.contactos.collectAsState()
                val estadoRed by red.estado.collectAsState()
                val avisos by modulo2.avisosRedApoyo.collectAsState()
                BackHandler { irACancelar() }
                // Estados, unidad y avisos del servidor cada 10 s (CU-22).
                LaunchedEffect(Unit) {
                    while (true) { modulo2.actualizarSeguimientoDesdeServidor(); delay(10_000) }
                }
                val a = alerta
                if (a == null || !a.activa) {
                    LaunchedEffect(Unit) { nav.irA(Pantalla.A04) }
                } else {
                    PantallaAlertaActiva(
                        alerta = a,
                        seguimiento = seguimiento,
                        historial = historial,
                        ubicacion = telemetria?.let { Punto(it.latitud, it.longitud) },
                        precisionM = telemetria?.precision,
                        red = RedAvisada(contactos.size, estadoRed.circulo?.nombreGrupo, avisos),
                        onLlamar911 = { marcar(context, "911") },
                        onYaEstoyASalvo = { irACancelar() }
                    )
                }
            }
            composable(Pantalla.A08.ruta) {
                val estadoCancelacion by cancelacion.estado.collectAsState()
                LaunchedEffect(estadoCancelacion.resultado) {
                    when (estadoCancelacion.resultado) {
                        is ResultadoCancelacion.Cancelada -> {
                            // Con la alerta enviada SenderoApp ya la detuvo; en la cuenta regresiva se detiene aquí.
                            modulo2.desactivarAlertaEmergencia()
                            cancelacion.reiniciar()
                            nav.irA(Pantalla.A04)
                        }
                        is ResultadoCancelacion.NoCancelada -> {
                            // Mensaje neutro y de vuelta: la alerta sigue (flujo 3a de CU-21).
                            delay(2500)
                            cancelacion.reiniciar()
                            nav.popBackStack()
                        }
                        null -> Unit
                    }
                }
                PantallaCancelarAlerta(
                    estado = estadoCancelacion,
                    motivos = cancelacion.motivos,
                    onMotivo = cancelacion::elegirMotivo,
                    onConfirmarPin = { pin ->
                        val enviada = modulo2.alertaActiva.value?.activa == true
                        cancelacion.confirmarConPin(modulo2.idAlertaEnCurso() ?: "", pin, alertaYaEnviada = enviada)
                    },
                    onHuella = { ok ->
                        val enviada = modulo2.alertaActiva.value?.activa == true
                        cancelacion.confirmarConBiometria(modulo2.idAlertaEnCurso() ?: "", ok, alertaYaEnviada = enviada)
                    },
                    onVolver = { nav.popBackStack() }
                )
            }

            // ---------------------------------------------------------------- Bloque 3 · Red de apoyo y perfil
            composable(Pantalla.A09.ruta) {
                val contactos by red.contactos.collectAsState()
                val estadoRed by red.estado.collectAsState()
                PantallaContactos(
                    contactos = contactos,
                    estado = estadoRed,
                    onSincronizar = red::sincronizar,
                    onGuardar = { id, nombre, telefono, prioridad ->
                        if (id == null) red.agregarContacto(nombre, telefono, prioridad) else red.editarContacto(id, nombre, telefono)
                    },
                    onEliminar = red::eliminarContacto,
                    onReordenar = red::reordenar,
                    onCirculo = { nav.navigate(Pantalla.A10.ruta) },
                    onVolver = { nav.popBackStack() }
                )
            }
            composable(Pantalla.A10.ruta) {
                val estadoRed by red.estado.collectAsState()
                PantallaCirculo(
                    estado = estadoRed,
                    usuarioId = estadoAuth.usuario?.id,
                    puedeCoordinar = estadoAuth.usuario?.rol == UsuarioRol.COORDINADOR,
                    escuela = null,
                    onCargar = red::cargarCirculo,
                    onUnirse = red::unirse,
                    onCrear = red::crearCirculo,
                    onRegenerar = red::regenerarCodigo,
                    onDarDeBaja = red::darDeBaja,
                    onRetirarme = red::retirarme,
                    onVolver = { nav.popBackStack() }
                )
            }
            composable(Pantalla.A11.ruta) {
                val contactos by red.contactos.collectAsState()
                val estadoRed by red.estado.collectAsState()
                LaunchedEffect(Unit) { if (estadoAuth.aviso == null) auth.cargarAviso() }
                PantallaPerfil(
                    estado = estadoAuth,
                    resumen = ResumenPerfil(
                        contactos = contactos.size,
                        circulo = estadoRed.circulo?.nombreGrupo,
                        coordinaCirculo = estadoRed.circulo?.coordinadorId == estadoAuth.usuario?.id,
                        activacionSinDesbloquear = configActivacion.atajoActivo || configActivacion.widgetActivo,
                        escuela = null
                    ),
                    esDesarrollo = esDesarrollo,
                    onCargar = { auth.cargarPerfil(); red.cargarCirculo() },
                    onActualizar = auth::actualizarPerfil,
                    onCambiarPin = auth::cambiarPin,
                    onSolicitarBaja = auth::solicitarBaja,
                    onActivacion = { nav.navigate(Pantalla.A12.ruta) },
                    onContactos = { nav.navigate(Pantalla.A09.ruta) },
                    onCerrarSesion = { auth.cerrarSesion(); nav.navigate(Pantalla.A03.ruta) { popUpTo(0) { inclusive = true } } },
                    onHerramientas = { nav.navigate(Pantalla.HERRAMIENTAS.ruta) },
                    onLimpiarRetro = auth::limpiarRetro
                )
                // Baja solicitada: la sesión se cerró en el teléfono.
                LaunchedEffect(estadoAuth.sesion) {
                    if (estadoAuth.sesion == null && !estadoAuth.retro.cargando && estadoAuth.usuario == null && estadoAuth.retro.mensaje == null) {
                        if (app.identidad.autenticacion.sesionLocal() == null) nav.navigate(Pantalla.A03.ruta) { popUpTo(0) { inclusive = true } }
                    }
                }
            }
            composable(Pantalla.A12.ruta) {
                val politica by activacion.politica.collectAsState()
                val errorActivacion by activacion.error.collectAsState()
                val mapas by app.mapas.estado.collectAsState()
                PantallaActivacionEnergia(
                    config = configActivacion,
                    politica = politica,
                    error = errorActivacion,
                    descarga = DescargaMapas(mapas.descargadaCapaEn, mapas.tamanoTotalBytes),
                    requiereExclusion = remember { activacion.requiereExclusionAhorroEnergia() },
                    onAtajo = { activacion.atajoBotonEncendido(it) },
                    onWidget = { activacion.widget(it) },
                    onCuenta = { activacion.cuentaRegresiva(it) },
                    onRetransmitir = { activacion.retransmitirMalla(it) },
                    onAhorro = { activacion.escaneoSegunBateria(it) },
                    onMapasWifi = { activacion.mapasSoloWifi(it) },
                    onSolicitarExclusion = { runCatching { context.startActivity(app.identidad.gestorEnergia.intentSolicitarExclusion()) } },
                    onVolver = { nav.popBackStack() }
                )
            }
            composable(Pantalla.A13.ruta) {
                val aviso = avisoRed
                if (aviso == null) LaunchedEffect(Unit) { nav.popBackStack() } else PantallaAvisoRedApoyo(aviso)
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
                    onAvisoRed = { enlace -> avisoRed = AvisoRedApoyo(enlace); nav.navigate(Pantalla.A13.ruta) },
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
                Pantalla.A05, Pantalla.A06, Pantalla.A07, Pantalla.A08,
                Pantalla.A09, Pantalla.A10, Pantalla.A11, Pantalla.A12, Pantalla.A13,
                Pantalla.HERRAMIENTAS, Pantalla.DIAGNOSTICO, Pantalla.MAPA_P12, Pantalla.REPORTE_P10, Pantalla.GALERIA
            )
            Pantalla.entries.filter { it !in hechas }.forEach { p ->
                composable(p.ruta) {
                    val extra = emptyList<Pair<String, () -> Unit>>()
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
