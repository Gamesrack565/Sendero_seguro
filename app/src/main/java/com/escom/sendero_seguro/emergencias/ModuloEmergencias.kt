package com.escom.sendero_seguro.emergencias

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.escom.sendero_seguro.BuildConfig
import com.escom.sendero_seguro.cola.ColaAlertaSyncWorker
import com.escom.sendero_seguro.cola.GestorColaSincronizacion
import com.escom.sendero_seguro.emergencias.AvisoMallaUsuario
import com.escom.sendero_seguro.emergencias.remoto.CanalServidorAlertas
import com.escom.sendero_seguro.emergencias.remoto.CanalUbicaciones
import com.escom.sendero_seguro.emergencias.remoto.LecturaUbicacionDto
import com.escom.sendero_seguro.emergencias.remoto.ResultadoUbicaciones
import com.escom.sendero_seguro.emergencias.remoto.ResultadoServidor
import com.escom.sendero_seguro.emergencias.remoto.ServidorAlertasRetrofit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class ModuloEmergencias(
    private val context: Context,
    // Id de la cuenta (Módulo 1). MainActivity lo pone en cuanto lee la sesión.
    var usuarioIdActual: String = "USR_LOCAL_01",
    // P4: registro en el servidor (Módulo 4). Sin él, la alerta solo vive en el teléfono y en la malla.
    private val servidor: CanalServidorAlertas? = null,
    // P6: ubicaciones de la alerta activa hacia el servidor. Por omisión, el mismo servidor de P4.
    ubicaciones: CanalUbicaciones? = servidor as? CanalUbicaciones
) {

    companion object {
        private const val TAG = "MODULO2_MOTOR_P4_P6"
        private const val UBICACIONES_POR_ENVIO = 100
    }

    private val appContext = context.applicationContext
    private val colaOffline = GestorColaSincronizacion(appContext)
    private val moduloScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var jobHiloTelemetria: Job? = null
    private var jobCuentaRegresivaPreEnvio: Job? = null
    private var ultimoDisparoCaidaMs: Long = 0L
    private val canalUbicaciones = ubicaciones
    private val candadoSincronizacionCola = Mutex()

    // P4: id de la alerta que ya no hay que mandar (el servidor la confirmó o la rechazó), y el envío en curso.
    @Volatile private var alertaResuelta: String? = null
    @Volatile private var envioAlerta: Job? = null
    @Volatile private var detonadorEnCuenta: TipoDetonador = TipoDetonador.BOTON_SOS

    // Configurable desde P3 (A-12): 3, 5 o 10 segundos antes de enviar la alerta
    var segundosCuentaRegresivaConfig: Int = 5

    private val _segundosRestantesPreEnvio = MutableStateFlow<Int?>(null)
    val segundosRestantesPreEnvio: StateFlow<Int?> = _segundosRestantesPreEnvio.asStateFlow()

    private val _alertaActiva = MutableStateFlow<AlertaEmergencia?>(null)
    val alertaActiva: StateFlow<AlertaEmergencia?> = _alertaActiva.asStateFlow()

    private val _estadoEnvioAlerta = MutableStateFlow<EstadoEnvioAlerta?>(null)
    val estadoEnvioAlerta: StateFlow<EstadoEnvioAlerta?> = _estadoEnvioAlerta.asStateFlow()

    // E3 P6: Línea de tiempo de estados de la alerta (tabla evento_alerta / E-01)
    private val _historialEventosAlerta = MutableStateFlow<List<EventoHistorialAlerta>>(emptyList())
    val historialEventosAlerta: StateFlow<List<EventoHistorialAlerta>> = _historialEventosAlerta.asStateFlow()

    // E3 P6: Vista sincronizada para la emisora (A-07) y su red de apoyo (A-13)
    private val _vistaSincronizadaAtencion = MutableStateFlow<EstadoSeguimientoAtencion?>(null)
    val vistaSincronizadaAtencion: StateFlow<EstadoSeguimientoAtencion?> = _vistaSincronizadaAtencion.asStateFlow()

    // Cola para Módulo 3 (P11: Caché Local SQLite) cuando no hay internet
    private val _colaTelemetriaOfflineP11 = MutableSharedFlow<DatosTelemetria>(extraBufferCapacity = 50)
    val colaTelemetriaOfflineP11: SharedFlow<DatosTelemetria> = _colaTelemetriaOfflineP11.asSharedFlow()

    val gestorMalla = GestorMalla(appContext)

    val gestorTelemetria = GestorTelemetria(
        context = appContext,
        onCaidaDetectada = {
            val ahora = System.currentTimeMillis()
            if (_alertaActiva.value == null && (ahora - ultimoDisparoCaidaMs) > 2000L) {
                ultimoDisparoCaidaMs = ahora
                activarAlertaEmergencia(TipoDetonador.CAIDA_IMPACTO)
            }
        }
    )

    // Agregar dentro de ModuloEmergencias.kt:
    var atajoCincoToquesEncendidoActivo: Boolean = true // Configurable desde P3 (A-12)
    private val marcasToquesEncendido = mutableListOf<Long>()

    private val receptorBotonEncendido5x = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (!atajoCincoToquesEncendidoActivo) return
            if (intent?.action == Intent.ACTION_SCREEN_OFF || intent?.action == Intent.ACTION_SCREEN_ON) {
                val ahora = System.currentTimeMillis()
                marcasToquesEncendido.add(ahora)
                // Conservar solo las presiones ocurridas en los últimos 4 segundos
                marcasToquesEncendido.removeAll { (ahora - it) > 4000L }

                if (marcasToquesEncendido.size >= 5) {
                    marcasToquesEncendido.clear()
                    Log.e(TAG, "¡5 PRESIONES DE BOTÓN DE ENCENDIDO DETECTADAS! Disparando P4...")
                    iniciarCuentaRegresivaYEnvioBase(TipoDetonador.ATAJO_FISICO)
                }
            }
        }
    }

    val gestorVozPanico = GestorVozPanico(
        context = appContext,
        onDetonadorVozActivado = { motivo ->
            Log.w(TAG, "Activando P4 por comando de voz: $motivo")
            // Limpiamos el estado previo para que cada nuevo comando de voz emita una nueva alerta y paquete BLE
            _alertaActiva.value = null
            gestorMalla.reiniciarEstadoParaNuevaPrueba()
            activarAlertaEmergencia(TipoDetonador.COMANDO_VOZ)
        },
        onRelatoTranscritoYClasificado = { relato, clasificacion ->
            _alertaActiva.value?.let { alerta ->
                _alertaActiva.value = alerta.copy(
                    descripcionRelato = relato,
                    clasificacionIA1 = clasificacion,
                    tipoIncidenteId = if (clasificacion.superaUmbral) clasificacion.tipoSugeridoId else alerta.tipoIncidenteId,
                    nombreTipoIncidente = if (clasificacion.superaUmbral) clasificacion.nombreTipoSugerido else alerta.nombreTipoIncidente,
                    esGenerica = !clasificacion.superaUmbral
                )
            }
        }
    )

    val gestorCaminataSegura = GestorCaminataSegura(
        scope = moduloScope,
        onTiempoCheckInVencido = {
            activarAlertaEmergencia(TipoDetonador.VENCIMIENTO_CAMINATA_SEGURA)
        },
        onDesviacionRutaExcedida = { distancia ->
            Log.w(TAG, "Aviso preventivo P7: Desviación de ruta de ${distancia.toInt()}m")
        },
        onDestinoAlcanzadoExitosamente = {
            Log.i(TAG, "Caminata Segura P7 concluida con éxito.")
        }
    )

    private val receptorApagadoSistema = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SHUTDOWN) {
                _alertaActiva.value = null
                activarAlertaEmergencia(TipoDetonador.BOTON_SOS, modoSinRedMallaActivo = true)
            }
        }
    }

    init {
        gestorTelemetria.iniciarVigilanciaImpactos()
        try {
            appContext.registerReceiver(receptorApagadoSistema, IntentFilter(Intent.ACTION_SHUTDOWN))
        } catch (_: Exception) {}

        val filtroPantalla = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(
            appContext,
            receptorBotonEncendido5x,
            filtroPantalla,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun iniciarProteccionSegundoPlano() {
        ServicioEmergencias.iniciar(appContext)
        gestorTelemetria.iniciarVigilanciaImpactos()
        gestorMalla.asegurarEscaneoMallaActivo()
    }

    val flujoTelemetria: StateFlow<DatosTelemetria?> = gestorTelemetria.flujoTelemetria
    val estadoCaminata: StateFlow<EstadoCaminataSegura> = gestorCaminataSegura.estadoCaminata
    val flujoPaquetesMalla: SharedFlow<PaqueteMalla> = gestorMalla.flujoPaquetesMalla
    val estadoRedYMalla: StateFlow<EstadoRedYMalla> = gestorMalla.estadoRedYMalla

    private var palabrasClavePanico = listOf("auxilio", "ayuda", "emergencia", "sendero seguro")

    /**
     * E3 P4 (A-04 y A-05): Inicia la cuenta regresiva (3, 5 o 10 s), enciende el GPS para fijar ubicación
     * y al finalizar envía de inmediato la alerta como «Genérica» para no retrasar la ayuda.
     */
    fun iniciarCuentaRegresivaYEnvioBase(
        detonador: TipoDetonador = TipoDetonador.BOTON_SOS,
        segundosCuenta: Int = segundosCuentaRegresivaConfig,
        omitirCuentaEnviarYa: Boolean = false
    ) {
        jobCuentaRegresivaPreEnvio?.cancel()
        _alertaActiva.value = null // Aún NO está activa hasta que termine la cuenta regresiva
        gestorTelemetria.emitirUbicacionGNSSPeriodica()

        if (omitirCuentaEnviarYa || segundosCuenta <= 0) {
            _segundosRestantesPreEnvio.value = null
            activarAlertaEmergencia(detonador)
            return
        }

        detonadorEnCuenta = detonador
        val idTemporal = java.util.UUID.randomUUID().toString()
        _historialEventosAlerta.value = emptyList()
        actualizarHistorialEventoAlerta(idTemporal, null, EstadoAlertaE01.CREADA, "Botón presionado ($detonador)")
        actualizarHistorialEventoAlerta(idTemporal, EstadoAlertaE01.CREADA, EstadoAlertaE01.EN_CUENTA_REGRESIVA, "Esperando ${segundosCuenta}s antes de enviar")

        jobCuentaRegresivaPreEnvio = moduloScope.launch {
            for (seg in segundosCuenta downTo 1) {
                _segundosRestantesPreEnvio.value = seg
                delay(1000L)
            }
            _segundosRestantesPreEnvio.value = null
            // Aquí, una vez cumplidos los segundos, recién se envía la alerta
            gestorMalla.reiniciarEstadoParaNuevaPrueba()
            activarAlertaEmergencia(detonador)
        }
    }

    fun activarAlertaEmergencia(
        detonador: TipoDetonador,
        modoSinRedMallaActivo: Boolean = true
    ): AlertaEmergencia {
        _alertaActiva.value?.let {
            if (it.activa) return it
        }

        ServicioEmergencias.iniciar(appContext)

        val telemetriaInicial = gestorTelemetria.flujoTelemetria.value
            ?: gestorTelemetria.emitirUbicacionGNSSPeriodica()

        val hayInternet = gestorMalla.hayConexionAInternet()
        val canalElegido = if (hayInternet) CanalRecepcion.INTERNET else CanalRecepcion.MALLA

        val nuevaAlerta = AlertaEmergencia(
            detonador = detonador,
            estadoActual = EstadoAlertaE01.ENVIADA,
            latitudInicial = telemetriaInicial.latitud,
            longitudInicial = telemetriaInicial.longitud,
            precisionMetros = telemetriaInicial.precision,
            nivelBateria = telemetriaInicial.nivelBateria,
            canalRecepcion = canalElegido,
            timestampInicio = System.currentTimeMillis(),
            activa = true
        )

        _alertaActiva.value = nuevaAlerta
        actualizarHistorialEventoAlerta(
            alertaId = nuevaAlerta.idAlerta,
            estadoAnterior = EstadoAlertaE01.EN_CUENTA_REGRESIVA,
            estadoNuevo = EstadoAlertaE01.ENVIADA,
            detalle = "Enviada como Genérica vía $canalElegido"
        )
        sincronizarVistaRedApoyoYEmisora()

        iniciarCascadaEnvio(nuevaAlerta, modoSinRedMallaActivo)

        // E3 P6: Lectura GNSS periódica cada 10 segundos (RNF / A-07)
        gestorTelemetria.iniciarTelemetriaTiempoReal(intervaloMs = 10_000L)
        iniciarHiloDeFondoTelemetria(intervaloMs = 10_000L)

        return nuevaAlerta
    }

    fun enviarAlertaPrueba(datos: DatosPruebaAlerta): AlertaEmergencia {
        require(datos.tipoIncidenteId >= 0) { "El tipo de incidente no puede ser negativo" }
        require(datos.latitud in -90.0..90.0 && datos.longitud in -180.0..180.0) {
            "La ubicación está fuera de rango"
        }
        require(datos.precisionMetros.isFinite() && datos.precisionMetros >= 0f) {
            "La precisión debe ser un número válido"
        }
        require(datos.nivelBateria in 0..100) { "La batería debe estar entre 0 y 100" }

        val nuevaAlerta = AlertaEmergencia(
            detonador = TipoDetonador.BOTON_SOS,
            tipoIncidenteId = datos.tipoIncidenteId,
            nombreTipoIncidente = if (datos.tipoIncidenteId in 0..1) "Genérica" else "Tipo ${datos.tipoIncidenteId}",
            esGenerica = datos.tipoIncidenteId in 0..1,
            descripcionRelato = datos.descripcion.trim(),
            latitudInicial = datos.latitud,
            longitudInicial = datos.longitud,
            precisionMetros = datos.precisionMetros,
            nivelBateria = datos.nivelBateria,
            canalRecepcion = if (gestorMalla.hayConexionAInternet()) CanalRecepcion.INTERNET else CanalRecepcion.MALLA
        )
        _alertaActiva.value = nuevaAlerta
        _historialEventosAlerta.value = emptyList()
        actualizarHistorialEventoAlerta(
            nuevaAlerta.idAlerta, null, EstadoAlertaE01.PENDIENTE_ENVIO, "Formulario de prueba de cascada"
        )
        iniciarCascadaEnvio(nuevaAlerta, intentarMalla = true)
        return nuevaAlerta
    }

    fun mostrarErrorPreparandoPrueba(mensaje: String) {
        _estadoEnvioAlerta.value = EstadoEnvioAlerta(
            alertaId = "",
            etapa = EtapaEnvioAlerta.ERROR,
            mensaje = mensaje
        )
    }

    /**
     * Reenvía alertas y telemetría pendientes al recuperar la conexión.
     */
    suspend fun sincronizarAlertasEnCola(): Boolean = candadoSincronizacionCola.withLock {
        val canal = servidor ?: return@withLock false
        if (!gestorMalla.hayConexionAInternet()) return@withLock false

        colaOffline.descartarYRegistrarVencidos()
        for (pendiente in colaOffline.obtenerAlertasListasParaReintento()) {
            val payload = pendiente.paquete.toString(Charsets.UTF_8)
            when (val resultado = canal.reenviarPendiente(payload)) {
                is ResultadoServidor.Registrada -> {
                    colaOffline.marcarSincronizadoYEliminar(pendiente.alertaId, esAlerta = true)
                    val recibidaPorMalla = resultado.canalRecepcion == "MALLA"
                    if (recibidaPorMalla) {
                        AvisoMallaUsuario.guardarYNotificar(
                            appContext, pendiente.alertaId, resultado.folio, resultado.estado
                        )
                    }
                    _estadoEnvioAlerta.update { actual ->
                        if (actual == null || recibidaPorMalla) {
                            EstadoEnvioAlerta(
                                alertaId = pendiente.alertaId,
                                etapa = EtapaEnvioAlerta.REGISTRADA,
                                mensaje = if (recibidaPorMalla) mensajeConfirmacionMalla()
                                else "Alerta sincronizada desde la cola local",
                                folio = resultado.folio,
                                estadoServidor = resultado.estado
                            )
                        } else if (actual.alertaId == pendiente.alertaId) {
                            actual.copy(
                                etapa = EtapaEnvioAlerta.REGISTRADA,
                                mensaje = if (recibidaPorMalla) mensajeConfirmacionMalla()
                                else "Alerta sincronizada desde la cola local",
                                folio = resultado.folio,
                                estadoServidor = resultado.estado
                            )
                        } else actual
                    }
                    _alertaActiva.update { alerta ->
                        if (alerta?.idAlerta == pendiente.alertaId) alerta.copy(folio = resultado.folio) else alerta
                    }
                }
                is ResultadoServidor.Fallo -> {
                    colaOffline.registrarFalloReintento(pendiente.alertaId, esAlerta = true)
                    if (!resultado.reintentar) {
                        Log.e(TAG, "El servidor rechazó permanentemente la alerta en cola ${pendiente.alertaId}")
                    }
                }
            }
        }

        val ubicaciones = canalUbicaciones
        if (ubicaciones != null) {
            val pendientesPorAlerta = colaOffline.obtenerTelemetriasPendientes().map { pendiente ->
                val payload = JSONObject(pendiente.paquete.toString(Charsets.UTF_8))
                val alertaIdPadre = payload.getString("alertaIdPadre")
                val lectura = ServidorAlertasRetrofit.json.decodeFromString(
                    LecturaUbicacionDto.serializer(),
                    payload.getString("lectura")
                )
                Triple(pendiente, alertaIdPadre, lectura)
            }.groupBy { it.second }
            for ((alertaIdPadre, pendientes) in pendientesPorAlerta) {
                for (lote in pendientes.chunked(UBICACIONES_POR_ENVIO)) {
                    when (ubicaciones.enviar(alertaIdPadre, lote.map { it.third })) {
                        ResultadoUbicaciones.ENTREGADAS,
                        ResultadoUbicaciones.DESCARTAR ->
                            lote.forEach { colaOffline.marcarSincronizadoYEliminar(it.first.alertaId, esAlerta = true) }
                        ResultadoUbicaciones.ALERTA_TERMINADA -> {
                            pendientes.forEach {
                                colaOffline.marcarSincronizadoYEliminar(it.first.alertaId, esAlerta = true)
                            }
                            break
                        }
                        ResultadoUbicaciones.REINTENTAR -> {
                            lote.forEach { colaOffline.registrarFalloReintento(it.first.alertaId, esAlerta = true) }
                            break
                        }
                    }
                }
            }
        }
        programarSiguienteReintentoCola()
        true
    }

    suspend fun hayPendientesSincronizacion(): Boolean =
        colaOffline.hayAlertasOTelemetriasPendientes()

    private suspend fun programarSiguienteReintentoCola() {
        val esperaMs = colaOffline.tiempoHastaProximoIntentoMs(incluirTelemetria = canalUbicaciones != null)
        if (esperaMs != null) {
            runCatching {
                ColaAlertaSyncWorker.programarReintento(appContext, esperaMs)
            }.onFailure {
                Log.e(TAG, "No se pudo programar el siguiente reintento de la cola.", it)
            }
        }
    }

    suspend fun cargarEstadoColaLocal() {
        val pendiente = colaOffline.obtenerAlertasPendientes().lastOrNull()
        if (pendiente != null) {
            _estadoEnvioAlerta.update { actual ->
                if (actual == null || actual.etapa == EtapaEnvioAlerta.COLA_LOCAL) {
                    EstadoEnvioAlerta(
                        alertaId = pendiente.alertaId,
                        etapa = EtapaEnvioAlerta.COLA_LOCAL,
                        mensaje = "Alerta pendiente de sincronización local"
                    )
                } else actual
            }
            return
        }

        val aviso = AvisoMallaUsuario.leerAvisoReciente(appContext) ?: return
        _estadoEnvioAlerta.update { actual ->
            if (actual == null || actual.etapa == EtapaEnvioAlerta.COLA_LOCAL ||
                actual.etapa == EtapaEnvioAlerta.REGISTRADA
            ) {
                EstadoEnvioAlerta(
                    alertaId = aviso.alertaId,
                    etapa = EtapaEnvioAlerta.REGISTRADA,
                    mensaje = mensajeConfirmacionMalla(),
                    folio = aviso.folio,
                    estadoServidor = aviso.estado
                )
            } else actual
        }
    }

    private fun iniciarCascadaEnvio(alerta: AlertaEmergencia, intentarMalla: Boolean) {
        alertaResuelta = null
        envioAlerta?.cancel()
        envioAlerta = moduloScope.launch { ejecutarCascadaEnvio(alerta, intentarMalla) }
    }

    private suspend fun ejecutarCascadaEnvio(alerta: AlertaEmergencia, intentarMalla: Boolean) {
        if (gestorMalla.hayConexionAInternet()) {
            actualizarEstadoEnvio(alerta, EtapaEnvioAlerta.INTERNET, "Enviando al servidor")
            when (val resultado = enviarAlServidor(alerta)) {
                is ResultadoServidor.Registrada -> {
                    confirmarRegistroServidor(alerta, resultado)
                    return
                }
                is ResultadoServidor.Fallo -> {
                    if (!resultado.reintentar) {
                        actualizarEstadoEnvio(
                            alerta,
                            EtapaEnvioAlerta.RECHAZADA,
                            "El servidor rechazó la alerta (HTTP ${resultado.codigoHttp ?: "sin respuesta"})"
                        )
                        return
                    }
                    Log.w(TAG, "No se confirmó la alerta por internet; se intentará la malla")
                }
                null -> Unit
            }
        }

        if (intentarMalla) {
            actualizarEstadoEnvio(alerta, EtapaEnvioAlerta.MALLA, "Intentando retransmisión P5.1 por Bluetooth")
            if (gestorMalla.puedeTransmitirAlertasPorBluetooth()) {
                gestorMalla.emitirAlertaEnMalla(alerta, usuarioIdActual)
                actualizarEstadoEnvio(
                    alerta,
                    EtapaEnvioAlerta.MALLA,
                    "Paquete emitido por malla; P5.1 no ofrece acuse al teléfono emisor"
                )
                delay(3000L)

                // Una retransmisión puede haber llegado al servidor mientras se esperaba el relevo.
                if (gestorMalla.hayConexionAInternet()) {
                    when (val resultado = enviarAlServidor(alerta)) {
                        is ResultadoServidor.Registrada -> {
                            confirmarRegistroServidor(alerta, resultado)
                            return
                        }
                        is ResultadoServidor.Fallo -> if (!resultado.reintentar) {
                            actualizarEstadoEnvio(
                                alerta, EtapaEnvioAlerta.RECHAZADA,
                                "El servidor rechazó la alerta (HTTP ${resultado.codigoHttp ?: "sin respuesta"})"
                            )
                            return
                        }
                        null -> Unit
                    }
                }
            } else {
                actualizarEstadoEnvio(
                    alerta, EtapaEnvioAlerta.MALLA,
                    "No hay Bluetooth disponible o falta el permiso de anuncios BLE"
                )
            }
        }

        actualizarEstadoEnvio(alerta, EtapaEnvioAlerta.SMS, "Intentando SMS de contingencia")
        when (val sms = EnvioSmsContingencia(appContext, BuildConfig.SMS_GATEWAY_NUMBER).enviar(alerta)) {
            ResultadoSmsContingencia.Enviado -> {
                actualizarEstadoEnvio(
                    alerta, EtapaEnvioAlerta.SMS,
                    "Android confirmó el envío SMS; falta la recepción de la pasarela"
                )
                return
            }
            is ResultadoSmsContingencia.Fallo ->
                Log.w(TAG, "Falló el SMS de contingencia: ${sms.motivo}")
        }

        try {
            colaOffline.encolarAlertaFalloCascada(alerta)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo guardar la alerta ${alerta.idAlerta} en la cola local", e)
            actualizarEstadoEnvio(alerta, EtapaEnvioAlerta.ERROR, "Falló el guardado en la cola local: ${e.message}")
            return
        }
        programarSincronizacionCola()
        actualizarEstadoEnvio(
            alerta, EtapaEnvioAlerta.COLA_LOCAL,
            "Sin confirmación del servidor ni SMS; alerta guardada en SQLite local"
        )
    }

    private fun programarSincronizacionCola() {
        runCatching {
            ColaAlertaSyncWorker.programarInmediato(appContext)
        }.onFailure {
            Log.e(TAG, "La alerta quedó en Room, pero no se pudo programar su sincronización.", it)
        }
    }

    private suspend fun enviarAlServidor(alerta: AlertaEmergencia): ResultadoServidor? {
        val canal = servidor ?: return null
        repeat(3) { intento ->
            when (val resultado = canal.enviar(alerta)) {
                is ResultadoServidor.Registrada -> return resultado
                is ResultadoServidor.Fallo -> {
                    if (!resultado.reintentar || intento == 2) return resultado
                    delay(2000L * (intento + 1))
                }
            }
        }
        return null
    }

    private suspend fun confirmarRegistroServidor(alerta: AlertaEmergencia, resultado: ResultadoServidor.Registrada) {
        alertaResuelta = alerta.idAlerta
        val recibidaPorMalla = resultado.canalRecepcion == "MALLA"
        if (recibidaPorMalla) {
            AvisoMallaUsuario.guardarYNotificar(
                appContext, alerta.idAlerta, resultado.folio, resultado.estado
            )
        }
        _alertaActiva.update { actual ->
            if (actual?.idAlerta == alerta.idAlerta) actual.copy(folio = resultado.folio) else actual
        }
        actualizarHistorialEventoAlerta(
            alertaId = alerta.idAlerta,
            estadoAnterior = EstadoAlertaE01.PENDIENTE_ENVIO,
            estadoNuevo = EstadoAlertaE01.ENVIADA,
            detalle = "Registrada en el servidor con folio ${resultado.folio}"
        )
        actualizarEstadoEnvio(
            alerta, EtapaEnvioAlerta.REGISTRADA,
            if (recibidaPorMalla) mensajeConfirmacionMalla()
            else "Registrada en servidor; consultando evento_alerta",
            resultado.folio,
            resultado.estado
        )

        val canal = servidor ?: return
        repeat(5) {
            delay(1000L)
            val estado = canal.consultarEstado(alerta.idAlerta) ?: return@repeat
            _estadoEnvioAlerta.update { actual ->
                if (actual?.alertaId == alerta.idAlerta) actual.copy(estadoServidor = estado) else actual
            }
            if (estado !in setOf("ENVIADA", "PENDIENTE_ENVIO")) return
        }
    }

    private fun mensajeConfirmacionMalla() =
        "Otro dispositivo retransmitió tu alerta por malla y el servidor la registró."

    private fun actualizarEstadoEnvio(
        alerta: AlertaEmergencia,
        etapa: EtapaEnvioAlerta,
        mensaje: String,
        folio: String? = null,
        estadoServidor: String? = null
    ) {
        _estadoEnvioAlerta.value = EstadoEnvioAlerta(
            alertaId = alerta.idAlerta,
            etapa = etapa,
            mensaje = mensaje,
            folio = folio,
            estadoServidor = estadoServidor
        )
    }

    /**
     * E3 P6 (E-01 y A-07): Registra cada transición de estado en la línea de tiempo (evento_alerta).
     */
    fun actualizarHistorialEventoAlerta(
        alertaId: String,
        estadoAnterior: EstadoAlertaE01?,
        estadoNuevo: EstadoAlertaE01,
        detalle: String = ""
    ) {
        val nuevoEvento = EventoHistorialAlerta(
            alertaId = alertaId,
            estadoAnterior = estadoAnterior,
            estadoNuevo = estadoNuevo,
            detalle = detalle
        )
        _historialEventosAlerta.value = _historialEventosAlerta.value + nuevoEvento
        _alertaActiva.value = _alertaActiva.value?.copy(estadoActual = estadoNuevo)
    }

    /**
     * E3 P6 (A-07 y A-13): Actualiza en vivo para la emisora y su red de apoyo qué unidad
     * va en camino (ej. P-214 a 650 m) y la hora exacta del acuse.
     */
    fun sincronizarVistaRedApoyoYEmisora(
        unidadAsignadaClave: String? = null,
        corporacionNombre: String? = "Seguridad IPN",
        distanciaUnidadMetros: Double? = null,
        horaAcuseMs: Long? = null,
        nuevoEstado: EstadoAlertaE01? = null
    ) {
        val alerta = _alertaActiva.value ?: return
        if (nuevoEstado != null && nuevoEstado != alerta.estadoActual) {
            actualizarHistorialEventoAlerta(
                alertaId = alerta.idAlerta,
                estadoAnterior = alerta.estadoActual,
                estadoNuevo = nuevoEstado,
                detalle = unidadAsignadaClave?.let { "Unidad $it (${distanciaUnidadMetros?.toInt() ?: 0} m)" } ?: ""
            )
        }

        _vistaSincronizadaAtencion.value = EstadoSeguimientoAtencion(
            alertaId = alerta.idAlerta,
            folio = alerta.folio,
            estadoActual = nuevoEstado ?: alerta.estadoActual,
            unidadAsignadaClave = unidadAsignadaClave ?: _vistaSincronizadaAtencion.value?.unidadAsignadaClave,
            corporacionNombre = corporacionNombre,
            distanciaUnidadMetros = distanciaUnidadMetros ?: _vistaSincronizadaAtencion.value?.distanciaUnidadMetros,
            horaAcuseMs = horaAcuseMs ?: _vistaSincronizadaAtencion.value?.horaAcuseMs,
            contactosNotificados = 3,
            totalContactos = 3,
            circuloNotificado = "6IV1"
        )
    }

    fun escucharComandoVozMicrofono() {
        gestorVozPanico.iniciarEscuchaMicrofono()
    }

    fun procesarTextoDeVoz(textoReconocido: String): Boolean {
        val textoNormalizado = textoReconocido.lowercase().trim()
        val coincide = palabrasClavePanico.any { palabra -> textoNormalizado.contains(palabra) }
        if (coincide) {
            gestorVozPanico.detenerEscuchaMicrofono()
            activarAlertaEmergencia(TipoDetonador.COMANDO_VOZ)
            return true
        }
        return false
    }

    private fun iniciarHiloDeFondoTelemetria(intervaloMs: Long = 10_000L) {
        jobHiloTelemetria?.cancel()
        jobHiloTelemetria = moduloScope.launch {
            while (isActive && _alertaActiva.value?.activa == true) {
                val datos = gestorTelemetria.emitirUbicacionGNSSPeriodica()
                gestorMalla.ajustarEscaneoMallaPorBateria(datos.nivelBateria)
                val alerta = _alertaActiva.value
                val hayInternet = gestorMalla.hayConexionAInternet()

                if (!hayInternet) _colaTelemetriaOfflineP11.tryEmit(datos)
                if (alerta != null) {
                    val encolada = try {
                        colaOffline.encolarTelemetria(datos, alerta.idAlerta)
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "No se pudo persistir la telemetría P6 en la cola P11.", e)
                        false
                    }

                    // Si se activó sin internet, la alerta sale al servidor en cuanto vuelve la señal.
                    if (hayInternet && alertaResuelta != alerta.idAlerta && envioAlerta?.isActive != true) {
                        when (val resultado = enviarAlServidor(alerta)) {
                            is ResultadoServidor.Registrada -> confirmarRegistroServidor(alerta, resultado)
                            is ResultadoServidor.Fallo -> Log.w(
                                TAG, "No se pudo reenviar la alerta ${alerta.idAlerta} (HTTP ${resultado.codigoHttp})"
                            )
                            null -> Unit
                        }
                    }

                    if (encolada) {
                        if (hayInternet) {
                            if (!sincronizarAlertasEnCola()) programarSincronizacionCola()
                        } else {
                            programarSincronizacionCola()
                        }
                    }
                }

                if (gestorCaminataSegura.estadoCaminata.value.activa) {
                    gestorCaminataSegura.evaluarUbicacionEnRuta(datos.latitud, datos.longitud)
                }

                delay(intervaloMs)
            }
        }
    }

    fun desactivarAlertaEmergencia() {
        jobCuentaRegresivaPreEnvio?.cancel()
        _segundosRestantesPreEnvio.value = null

        _alertaActiva.value?.let {
            actualizarHistorialEventoAlerta(it.idAlerta, it.estadoActual, EstadoAlertaE01.CANCELADA, "Cancelación autenticada")
        }

        jobHiloTelemetria?.cancel()
        alertaResuelta = null
        gestorTelemetria.detenerTelemetriaTiempoReal()
        gestorMalla.detenerSoloTransmisionBLE()
        _alertaActiva.value = null
        _vistaSincronizadaAtencion.value = null
    }

    fun actualizarParametrosSeguridad(
        nuevasPalabrasVoz: List<String>,
        sensibilidadCaidaG: Float,
        intervaloCheckInSegundos: Long = 60L,
        segundosCuentaRegresiva: Int = 5
    ) {
        if (nuevasPalabrasVoz.isNotEmpty()) {
            val normalizadas = nuevasPalabrasVoz.map { it.lowercase().trim() }
            palabrasClavePanico = normalizadas
            gestorVozPanico.palabrasClave = normalizadas
        }
        gestorTelemetria.umbralImpactoG = sensibilidadCaidaG
        gestorCaminataSegura.intervaloCheckInPorDefectoSeg = intervaloCheckInSegundos
        segundosCuentaRegresivaConfig = segundosCuentaRegresiva.coerceIn(3, 10)
    }
}