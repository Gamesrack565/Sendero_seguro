package com.escom.sendero_seguro.emergencias

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.escom.sendero_seguro.emergencias.remoto.CanalServidorAlertas
import com.escom.sendero_seguro.emergencias.remoto.ResultadoServidor
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

class ModuloEmergencias(
    private val context: Context,
    // Id de la cuenta (Módulo 1). MainActivity lo pone en cuanto lee la sesión.
    var usuarioIdActual: String = "USR_LOCAL_01",
    // P4: registro en el servidor (Módulo 4). Sin él, la alerta solo vive en el teléfono y en la malla.
    private val servidor: CanalServidorAlertas? = null
) {

    companion object {
        private const val TAG = "MODULO2_MOTOR_P4_P6"
    }

    private val appContext = context.applicationContext
    private val moduloScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var jobHiloTelemetria: Job? = null
    private var jobCuentaRegresivaPreEnvio: Job? = null
    private var ultimoDisparoCaidaMs: Long = 0L

    // Configurable desde P3 (A-12): 3, 5 o 10 segundos antes de enviar la alerta
    var segundosCuentaRegresivaConfig: Int = 5

    private val _segundosRestantesPreEnvio = MutableStateFlow<Int?>(null)
    val segundosRestantesPreEnvio: StateFlow<Int?> = _segundosRestantesPreEnvio.asStateFlow()

    private val _alertaActiva = MutableStateFlow<AlertaEmergencia?>(null)
    val alertaActiva: StateFlow<AlertaEmergencia?> = _alertaActiva.asStateFlow()

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
        appContext.registerReceiver(receptorBotonEncendido5x, filtroPantalla)
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

        if (hayInternet) enviarAlServidor(nuevaAlerta)

        if (modoSinRedMallaActivo) {
            gestorMalla.emitirAlertaEnMalla(nuevaAlerta, usuarioIdActual)
        }

        // E3 P6: Lectura GNSS periódica cada 10 segundos (RNF / A-07)
        gestorTelemetria.iniciarTelemetriaTiempoReal(intervaloMs = 10_000L)
        iniciarHiloDeFondoTelemetria(intervaloMs = 10_000L)

        return nuevaAlerta
    }

    /**
     * P4: manda la alerta al servidor sin detener la activación. Reintenta 3 veces si no hubo red o el servidor
     * falló; el servidor no la duplica. Si responde, la alerta toma el folio del servidor.
     */
    private fun enviarAlServidor(alerta: AlertaEmergencia) {
        val canal = servidor ?: return
        moduloScope.launch {
            repeat(3) { intento ->
                when (val r = canal.enviar(alerta)) {
                    is ResultadoServidor.Registrada -> {
                        _alertaActiva.update { a -> if (a?.idAlerta == alerta.idAlerta) a.copy(folio = r.folio) else a }
                        actualizarHistorialEventoAlerta(
                            alertaId = alerta.idAlerta,
                            estadoAnterior = EstadoAlertaE01.ENVIADA,
                            estadoNuevo = EstadoAlertaE01.ENVIADA,
                            detalle = "Registrada en el servidor con folio ${r.folio}"
                        )
                        return@launch
                    }
                    is ResultadoServidor.Fallo -> {
                        if (!r.reintentar || intento == 2) {
                            Log.w(TAG, "El servidor no registró la alerta (HTTP ${r.codigoHttp})")
                            return@launch
                        }
                        delay(2000L * (intento + 1))
                    }
                }
            }
        }
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

                if (!gestorMalla.hayConexionAInternet()) {
                    _colaTelemetriaOfflineP11.tryEmit(datos)
                }

                if (gestorCaminataSegura.estadoCaminata.value.activa) {
                    gestorCaminataSegura.evaluarUbicacionEnRuta(datos.latitud, datos.longitud)
                }

                delay(intervaloMs)
            }
        }
    }

    fun desactivarAlertaEmergencia(simulacionCoaccion: Boolean = false) {
        jobCuentaRegresivaPreEnvio?.cancel()
        _segundosRestantesPreEnvio.value = null

        if (simulacionCoaccion) {
            _alertaActiva.value = _alertaActiva.value?.copy(enModoCoaccionSilencioso = true)
            return
        }

        _alertaActiva.value?.let {
            actualizarHistorialEventoAlerta(it.idAlerta, it.estadoActual, EstadoAlertaE01.CANCELADA, "Cancelación autenticada")
        }

        jobHiloTelemetria?.cancel()
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