package com.escom.sendero_seguro.emergencias

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ModuloEmergencias(
    private val context: Context,
    private val usuarioIdActual: String = "USR_LOCAL_01"
) {

    companion object {
        private const val TAG = "MODULO2_MOTOR_P4"
    }

    private val appContext = context.applicationContext
    private val moduloScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var jobHiloTelemetria: Job? = null
    private var ultimoDisparoCaidaMs: Long = 0L

    private val _alertaActiva = MutableStateFlow<AlertaEmergencia?>(null)
    val alertaActiva: StateFlow<AlertaEmergencia?> = _alertaActiva.asStateFlow()

    // Flujo de eventos pendientes para que el Módulo 3 (P11: Caché Local SQLite) los persista cuando no hay red
    private val _colaTelemetriaOfflineP11 =
        MutableSharedFlow<DatosTelemetria>(extraBufferCapacity = 50)
    val colaTelemetriaOfflineP11: SharedFlow<DatosTelemetria> =
        _colaTelemetriaOfflineP11.asSharedFlow()

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

    val gestorVozPanico = GestorVozPanico(
        context = appContext,
        onDetonadorVozActivado = { motivo ->
            Log.w(TAG, "Activando P4 por comando de voz/sonido: $motivo")
            _alertaActiva.value = null
            activarAlertaEmergencia(TipoDetonador.COMANDO_VOZ)
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
            Log.i(TAG, "Caminata Segura P7 concluida con éxito: Usuario llegó a su destino.")
        }
    )

    private val receptorApagadoSistema = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SHUTDOWN) {
                Log.e(TAG, "¡APAGADO DETECTADO! Emitiendo última coordenada en malla BLE...")
                _alertaActiva.value = null
                activarAlertaEmergencia(TipoDetonador.BOTON_SOS, modoSinRedMallaActivo = true)
            }
        }
    }

    init {
        gestorTelemetria.iniciarVigilanciaImpactos()
        try {
            appContext.registerReceiver(
                receptorApagadoSistema,
                IntentFilter(Intent.ACTION_SHUTDOWN)
            )
        } catch (_: Exception) {
        }
    }

    fun iniciarProteccionSegundoPlano() {
        ServicioEmergencias.iniciar(appContext)
        gestorTelemetria.iniciarVigilanciaImpactos()
        gestorMalla.asegurarEscaneoMallaActivo()
    }

    // Flujos públicos para Frontend 1, Frontend 2 y Módulo 3
    val flujoTelemetria: StateFlow<DatosTelemetria?> = gestorTelemetria.flujoTelemetria
    val estadoCaminata: StateFlow<EstadoCaminataSegura> = gestorCaminataSegura.estadoCaminata
    val flujoPaquetesMalla: SharedFlow<PaqueteMalla> = gestorMalla.flujoPaquetesMalla
    val estadoRedYMalla: StateFlow<EstadoRedYMalla> = gestorMalla.estadoRedYMalla

    private var palabrasClavePanico = listOf("auxilio", "ayuda", "emergencia", "sendero seguro")

    fun activarAlertaEmergencia(
        detonador: TipoDetonador,
        modoSinRedMallaActivo: Boolean = true
    ): AlertaEmergencia {
        _alertaActiva.value?.let {
            if (it.activa) return it
        }

        ServicioEmergencias.iniciar(appContext)

        val telemetriaInicial = gestorTelemetria.flujoTelemetria.value
            ?: gestorTelemetria.emitirInstantaneaTelemetria()

        val nuevaAlerta = AlertaEmergencia(
            detonador = detonador,
            latitudInicial = telemetriaInicial.latitud,
            longitudInicial = telemetriaInicial.longitud,
            precisionMetros = telemetriaInicial.precision,
            nivelBateria = telemetriaInicial.nivelBateria,
            timestampInicio = System.currentTimeMillis(),
            activa = true
        )

        _alertaActiva.value = nuevaAlerta
        Log.e(
            TAG,
            "=== ALERTA DE EMERGENCIA ACTIVADA (P4) === ID: ${nuevaAlerta.idAlerta} | Detonador: $detonador"
        )

        if (modoSinRedMallaActivo) {
            gestorMalla.emitirAlertaEnMalla(nuevaAlerta, usuarioIdActual)
        }

        gestorTelemetria.iniciarTelemetriaTiempoReal(intervaloMs = 3000L)
        iniciarHiloDeFondoTelemetria()

        return nuevaAlerta
    }

    /**
     * P4: Abre el micrófono real del teléfono para escuchar la voz del usuario.
     */
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

    private fun iniciarHiloDeFondoTelemetria() {
        jobHiloTelemetria?.cancel()
        jobHiloTelemetria = moduloScope.launch {
            while (isActive && _alertaActiva.value?.activa == true) {
                val datos = gestorTelemetria.emitirInstantaneaTelemetria()

                // Si no hay internet, encolar la lectura para el Módulo 3 (P11 Caché SQLite)
                if (!gestorMalla.hayConexionAInternet()) {
                    _colaTelemetriaOfflineP11.tryEmit(datos)
                }

                if (gestorCaminataSegura.estadoCaminata.value.activa) {
                    gestorCaminataSegura.evaluarUbicacionEnRuta(datos.latitud, datos.longitud)
                }

                delay(3000L)
            }
        }
    }

    /**
     * P8: Cancelación normal o simulación de cancelación bajo PIN de Coacción (Duress PIN).
     */
    fun desactivarAlertaEmergencia(simulacionCoaccion: Boolean = false) {
        if (simulacionCoaccion) {
            _alertaActiva.value = _alertaActiva.value?.copy(enModoCoaccionSilencioso = true)
            Log.w(
                TAG,
                "Modo Coacción activo (P8): Se simula cancelación en pantalla pero continúa P6 y Malla en silencio."
            )
            return
        }

        jobHiloTelemetria?.cancel()
        gestorTelemetria.detenerTelemetriaTiempoReal()
        gestorMalla.detenerSoloTransmisionBLE()
        _alertaActiva.value = null
        Log.i(TAG, "Alerta de emergencia DESACTIVADA completamente.")
    }

    /**
     * P3: Recibe la configuración de parámetros de seguridad desde el Módulo 1.
     */
    fun actualizarParametrosSeguridad(
        nuevasPalabrasVoz: List<String>,
        sensibilidadCaidaG: Float,
        intervaloCheckInSegundos: Long = 60L
    ) {
        if (nuevasPalabrasVoz.isNotEmpty()) {
            val normalizadas = nuevasPalabrasVoz.map { it.lowercase().trim() }
            palabrasClavePanico = normalizadas
            gestorVozPanico.palabrasClave = normalizadas
        }
        gestorTelemetria.umbralImpactoG = sensibilidadCaidaG
        gestorCaminataSegura.intervaloCheckInPorDefectoSeg = intervaloCheckInSegundos
    }
}