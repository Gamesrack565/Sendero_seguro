package com.escom.sendero_seguro.emergencias

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ModuloEmergencias(
    context: Context,
    private val usuarioIdActual: String = "USR_LOCAL_01"
) {

    companion object {
        private const val TAG = "MODULO2_MOTOR_P4"
    }

    private val moduloScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var jobHiloTelemetria: Job? = null

    private val _alertaActiva = MutableStateFlow<AlertaEmergencia?>(null)
    val alertaActiva: StateFlow<AlertaEmergencia?> = _alertaActiva.asStateFlow()

    val gestorMalla = GestorMalla()

    val gestorTelemetria = GestorTelemetria(
        context = context.applicationContext,
        onCaidaDetectada = {
            if (_alertaActiva.value == null) {
                activarAlertaEmergencia(TipoDetonador.CAIDA_IMPACTO)
            }
        }
    )

    init {
        // P4: Mantener siempre encendido el detector de caídas/impactos en espera
        gestorTelemetria.iniciarVigilanciaImpactos()
    }

    val gestorCaminataSegura = GestorCaminataSegura(
        scope = moduloScope,
        onTiempoCheckInVencido = {
            activarAlertaEmergencia(TipoDetonador.VENCIMIENTO_CAMINATA_SEGURA)
        },
        onDesviacionRutaExcedida = { distancia ->
            Log.w(TAG, "Aviso preventivo P7: Desviación de ruta de ${distancia.toInt()}m")
        }
    )

    val flujoTelemetria: StateFlow<DatosTelemetria?> = gestorTelemetria.flujoTelemetria
    val estadoCaminata: StateFlow<EstadoCaminataSegura> = gestorCaminataSegura.estadoCaminata
    val flujoPaquetesMalla: SharedFlow<PaqueteMalla> = gestorMalla.flujoPaquetesMalla

    private var palabrasClavePanico = listOf("auxilio", "ayuda", "emergencia", "sendero seguro")

    fun activarAlertaEmergencia(
        detonador: TipoDetonador,
        modoSinRedMallaActivo: Boolean = true
    ): AlertaEmergencia {
        _alertaActiva.value?.let {
            if (it.activa) {
                Log.d(TAG, "La alerta ya se encuentra activa (${it.idAlerta})")
                return it
            }
        }

        val telemetriaInicial = gestorTelemetria.emitirInstantaneaTelemetria()

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
        Log.e(TAG, "=== ALERTA DE EMERGENCIA ACTIVADA (P4) === ID: ${nuevaAlerta.idAlerta} | Detonador: $detonador")

        gestorTelemetria.iniciarTelemetriaTiempoReal(intervaloMs = 3000L)
        iniciarHiloDeFondoTelemetria()

        if (modoSinRedMallaActivo) {
            gestorMalla.emitirAlertaEnMalla(nuevaAlerta, usuarioIdActual)
        }

        return nuevaAlerta
    }

    fun procesarTextoDeVoz(textoReconocido: String): Boolean {
        val textoNormalizado = textoReconocido.lowercase().trim()
        val coincide = palabrasClavePanico.any { palabra -> textoNormalizado.contains(palabra) }
        if (coincide) {
            Log.w(TAG, "Comando de voz de pánico detectado: '$textoReconocido'")
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

                if (gestorCaminataSegura.estadoCaminata.value.activa) {
                    gestorCaminataSegura.evaluarUbicacionEnRuta(datos.latitud, datos.longitud)
                }

                delay(3000L)
            }
        }
    }

    fun desactivarAlertaEmergencia(simulacionCoaccion: Boolean = false) {
        if (simulacionCoaccion) {
            Log.w(TAG, "Modo Coacción activo (P8): Se mantiene el hilo de telemetría P6 en segundo plano.")
            return
        }

        jobHiloTelemetria?.cancel()
        gestorTelemetria.detenerTelemetriaTiempoReal()
        _alertaActiva.value = null
        Log.i(TAG, "Alerta de emergencia DESACTIVADA completamente.")
    }

    fun actualizarParametrosSeguridad(
        nuevasPalabrasVoz: List<String>,
        sensibilidadCaidaG: Float
    ) {
        if (nuevasPalabrasVoz.isNotEmpty()) {
            palabrasClavePanico = nuevasPalabrasVoz.map { it.lowercase().trim() }
        }
        gestorTelemetria.umbralImpactoG = sensibilidadCaidaG
        Log.d(TAG, "Parámetros actualizados -> Palabras: $palabrasClavePanico | Sensibilidad: ${sensibilidadCaidaG}G")
    }
}