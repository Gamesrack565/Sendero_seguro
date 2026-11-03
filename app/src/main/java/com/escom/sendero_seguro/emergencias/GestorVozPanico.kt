package com.escom.sendero_seguro.emergencias

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.Normalizer
import java.util.Locale

class GestorVozPanico(
    private val context: Context,
    private val onDetonadorVozActivado: (motivo: String) -> Unit
) {

    companion object {
        private const val TAG = "MODULO2_VOZ_P4"
        private const val TIEMPO_REINICIO = 700L
    }

    // P3 y P4: Lista dinámica de palabras clave configurable desde el Módulo 1
    var palabrasClave: List<String> = listOf(
        "ayuda",
        "auxilio",
        "sendero seguro"
    )
        set(value) {
            field = value.map { normalizarTexto(it) }.filter { it.isNotEmpty() }
        }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null
    private var escuchando = false
    private var alertaDisparada = false

    private val _escuchandoMicrofono = MutableStateFlow(false)
    val escuchandoMicrofono: StateFlow<Boolean> =
        _escuchandoMicrofono.asStateFlow()

    private val _flujoEventosVoz = MutableSharedFlow<String>(
        extraBufferCapacity = 20
    )
    val flujoEventosVoz: SharedFlow<String> =
        _flujoEventosVoz.asSharedFlow()

    private val reiniciarEscucha = Runnable {
        if (escuchando && !alertaDisparada) {
            iniciarReconocimiento()
        }
    }

    private fun reportarVoz(msg: String) {
        Log.i(TAG, msg)
        _flujoEventosVoz.tryEmit(msg)
    }

    // =========================================================
    // DIAGNÓSTICO DE SERVICIOS DE RECONOCIMIENTO
    // =========================================================

    private fun diagnosticarServiciosVoz() {

        val intent = Intent(RecognitionService.SERVICE_INTERFACE)

        val servicios = context.packageManager.queryIntentServices(
            intent,
            PackageManager.MATCH_ALL
        )

        reportarVoz("===== DIAGNOSTICO DE VOZ =====")

        if (servicios.isEmpty()) {
            reportarVoz("No se encontraron servicios de reconocimiento")
        }

        servicios.forEach { servicio ->

            val info = servicio.serviceInfo

            reportarVoz("Servicio encontrado: ${info.packageName}")
            reportarVoz("Nombre: ${info.name}")
        }

        reportarVoz(
            "Reconocimiento disponible: ${
                SpeechRecognizer.isRecognitionAvailable(context)
            }"
        )

        if (Build.VERSION.SDK_INT >= 31) {
            reportarVoz(
                "Reconocimiento offline disponible: ${
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                }"
            )
        }
    }

    // =========================================================
    // DIAGNÓSTICO DE IDIOMAS DISPONIBLES
    // =========================================================

    private fun diagnosticarIdiomasVoz() {

        val intent = Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS)

        val receiver = object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                val idiomas = getResultExtras(true)

                val preferido = idiomas?.getString(
                    RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE
                )

                val soportados = idiomas?.getStringArrayList(
                    RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES
                )

                reportarVoz("===== IDIOMAS DEL RECONOCEDOR =====")
                reportarVoz("Idioma preferido: $preferido")

                if (soportados.isNullOrEmpty()) {

                    reportarVoz(
                        "El servicio no proporcionó su lista de idiomas"
                    )

                } else {

                    reportarVoz(
                        "Idiomas disponibles: ${soportados.joinToString()}"
                    )

                    reportarVoz(
                        "¿Admite es-MX?: ${soportados.contains("es-MX")}"
                    )

                    reportarVoz(
                        "¿Admite es-ES?: ${soportados.contains("es-ES")}"
                    )
                }
            }
        }

        try {

            context.sendOrderedBroadcast(
                intent,
                null,
                receiver,
                mainHandler,
                android.app.Activity.RESULT_OK,
                null,
                null
            )

        } catch (e: Exception) {

            reportarVoz(
                "No se pudieron consultar los idiomas: ${e.message}"
            )
        }
    }

    // =========================================================
    // INICIAR ESCUCHA DEL MICRÓFONO
    // =========================================================

    fun iniciarEscuchaMicrofono() {

        diagnosticarServiciosVoz()
        diagnosticarIdiomasVoz()

        if (escuchando) {
            detenerEscuchaMicrofono()
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            reportarVoz(
                "ERROR: No hay servicio de reconocimiento de voz disponible"
            )
            return
        }

        if (
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            reportarVoz("ERROR: Falta permiso RECORD_AUDIO")
            return
        }

        alertaDisparada = false
        escuchando = true

        try {

            speechRecognizer =
                SpeechRecognizer.createSpeechRecognizer(context)

            speechRecognizer?.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {

                        // Evita activar el estado si ya ocurrió un error.
                        if (!escuchando || alertaDisparada) return

                        reportarVoz(
                            "MICROFONO ACTIVO: Esperando palabras clave (${palabrasClave.joinToString()})..."
                        )

                        _escuchandoMicrofono.value = true
                    }

                    override fun onBeginningOfSpeech() {
                        reportarVoz("Se detecto voz")
                    }

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        reportarVoz("Fin de captura de voz")
                    }

                    override fun onError(error: Int) {

                        val mensaje = when (error) {

                            12 -> "Idioma no compatible con el reconocedor"
                            13 -> "Modelo de idioma no disponible"
                            9 -> "Permiso de micrófono insuficiente"
                            10 -> "Demasiadas solicitudes"
                            11 -> "Servicio de reconocimiento desconectado"
                            6 -> "No se detectó voz"
                            7 -> "No se reconocieron palabras"

                            else -> "Error de reconocimiento: $error"
                        }

                        reportarVoz(mensaje)

                        _escuchandoMicrofono.value = false

                        if (error == 12 || error == 13) {

                            // No repetir solicitudes que el servicio
                            // no puede atender.
                            escuchando = false

                            mainHandler.removeCallbacks(reiniciarEscucha)

                            return
                        }

                        if (escuchando && !alertaDisparada) {
                            programarReinicio()
                        }
                    }

                    override fun onResults(results: Bundle?) {

                        val coincidencias = results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )

                        coincidencias?.forEach { texto ->
                            procesarTextoReconocido(texto)
                        }

                        if (escuchando && !alertaDisparada) {
                            programarReinicio()
                        }
                    }

                    override fun onPartialResults(
                        partialResults: Bundle?
                    ) {

                        val coincidencias = partialResults?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )

                        coincidencias?.forEach { texto ->
                            procesarTextoReconocido(texto)
                        }
                    }

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?
                    ) {}
                }
            )

            iniciarReconocimiento()

        } catch (e: Exception) {

            escuchando = false
            _escuchandoMicrofono.value = false

            reportarVoz(
                "ERROR al iniciar reconocimiento: ${e.message}"
            )
        }
    }

    // =========================================================
    // CONFIGURACIÓN DEL RECONOCIMIENTO
    // =========================================================

    private fun iniciarReconocimiento() {

        if (!escuchando || alertaDisparada) return

        try {

            val intent = Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )

                // No forzar es-MX ni es-ES.
                // Dejar que el reconocedor utilice su idioma predeterminado.

                putExtra(
                    RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                    true
                )

                putExtra(
                    RecognizerIntent.EXTRA_MAX_RESULTS,
                    5
                )

                putExtra(
                    RecognizerIntent.EXTRA_PREFER_OFFLINE,
                    false
                )
            }

            reportarVoz(
                "Solicitando reconocimiento con idioma predeterminado..."
            )

            speechRecognizer?.startListening(intent)

        } catch (e: Exception) {

            reportarVoz(
                "ERROR iniciando reconocimiento: ${e.message}"
            )
        }
    }

    // =========================================================
    // PROCESAMIENTO DE PALABRAS CLAVE
    // =========================================================

    private fun procesarTextoReconocido(textoOriginal: String) {

        if (!escuchando || alertaDisparada) return

        val texto = normalizarTexto(textoOriginal)

        reportarVoz("Texto reconocido: $texto")

        val palabraDetectada = palabrasClave.firstOrNull { palabra ->

            Regex(
                """(?<![\p{L}\p{N}])${Regex.escape(palabra)}(?![\p{L}\p{N}])"""
            ).containsMatchIn(texto)
        }

        if (palabraDetectada != null) {

            alertaDisparada = true
            escuchando = false

            _escuchandoMicrofono.value = false

            mainHandler.removeCallbacks(reiniciarEscucha)

            reportarVoz(
                "ALERTA SOS ACTIVADA: $palabraDetectada"
            )

            onDetonadorVozActivado(
                "PALABRA_CLAVE_$palabraDetectada"
            )

            destruirReconocedor()
        }
    }

    // =========================================================
    // NORMALIZACIÓN DE TEXTO
    // =========================================================

    private fun normalizarTexto(texto: String): String {

        return Normalizer.normalize(
            texto.lowercase(Locale.forLanguageTag("es-MX")),
            Normalizer.Form.NFD
        )
            .replace("\\p{Mn}+".toRegex(), "")
            .trim()
    }

    // =========================================================
    // REINICIO AUTOMÁTICO
    // =========================================================

    private fun programarReinicio() {

        if (!escuchando || alertaDisparada) return

        mainHandler.removeCallbacks(reiniciarEscucha)

        mainHandler.postDelayed(
            reiniciarEscucha,
            TIEMPO_REINICIO
        )
    }

    // =========================================================
    // DETENER RECONOCIMIENTO
    // =========================================================

    fun detenerEscuchaMicrofono() {

        escuchando = false
        alertaDisparada = false

        _escuchandoMicrofono.value = false

        mainHandler.removeCallbacks(reiniciarEscucha)

        destruirReconocedor()

        reportarVoz("Reconocimiento de voz detenido")
    }

    // =========================================================
    // LIBERAR RECURSOS
    // =========================================================

    private fun destruirReconocedor() {

        try {

            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null

        } catch (e: Exception) {

            reportarVoz(
                "Error liberando reconocedor: ${e.message}"
            )
        }
    }
}