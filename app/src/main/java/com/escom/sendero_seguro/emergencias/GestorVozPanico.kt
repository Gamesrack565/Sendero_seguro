package com.escom.sendero_seguro.emergencias

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
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
    private val onDetonadorVozActivado: (motivo: String) -> Unit,
    private val onRelatoTranscritoYClasificado: (texto: String, clasificacion: ResultadoClasificacionIA1) -> Unit = { _, _ -> }
) {

    companion object {
        private const val TAG = "MODULO2_VOZ_P4_IA1"
        private const val TIEMPO_REINICIO = 650L
        private const val COOLDOWN_ENTRE_ALERTAS_VOZ_MS = 2500L
    }

    var palabrasClave: List<String> = listOf("ayuda", "auxilio", "sendero seguro", "emergencia")
        set(value) {
            field = value.map { normalizarTexto(it) }.filter { it.isNotEmpty() }
        }

    var umbralConfianzaIA1: Float = 0.70f

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var escuchando = false
    private var ultimoDisparoVozMs: Long = 0L

    private val _escuchandoMicrofono = MutableStateFlow(false)
    val escuchandoMicrofono: StateFlow<Boolean> = _escuchandoMicrofono.asStateFlow()

    private val _textoTranscritoEnVivo = MutableStateFlow("")
    val textoTranscritoEnVivo: StateFlow<String> = _textoTranscritoEnVivo.asStateFlow()

    private val _ultimaClasificacionIA1 = MutableStateFlow<ResultadoClasificacionIA1?>(null)
    val ultimaClasificacionIA1: StateFlow<ResultadoClasificacionIA1?> = _ultimaClasificacionIA1.asStateFlow()

    private val _flujoEventosVoz = MutableSharedFlow<String>(extraBufferCapacity = 20)
    val flujoEventosVoz: SharedFlow<String> = _flujoEventosVoz.asSharedFlow()

    private val reiniciarEscucha = Runnable {
        if (escuchando) {
            iniciarReconocimientoInterno()
        }
    }

    private fun reportarVoz(msg: String) {
        Log.i(TAG, msg)
        _flujoEventosVoz.tryEmit(msg)
    }

    fun transcribirVozATextoOffline() {
        iniciarEscuchaMicrofono()
    }

    fun iniciarEscuchaMicrofono() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            reportarVoz("ERROR: Servicio de voz no disponible en este teléfono")
            return
        }

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            reportarVoz("ERROR: Otorga permiso de Micrófono")
            return
        }

        escuchando = true
        recrearReconocedorLimpio()
        iniciarReconocimientoInterno()
    }

    private fun recrearReconocedorLimpio() {
        destruirReconocedor()
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        if (!escuchando) return
                        _escuchandoMicrofono.value = true
                        reportarVoz("🎙️ Micrófono activo: Escuchando (${palabrasClave.joinToString()})...")
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        _escuchandoMicrofono.value = false
                        // Si el reconocedor se traba (ERROR_RECOGNIZER_BUSY = 8 o ERROR_CLIENT = 5), recrearlo
                        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                            error == SpeechRecognizer.ERROR_CLIENT ||
                            error == SpeechRecognizer.ERROR_SERVER
                        ) {
                            recrearReconocedorLimpio()
                        }
                        if (escuchando) {
                            programarReinicio()
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val coincidencias = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        coincidencias?.forEach { texto ->
                            procesarTextoReconocido(texto, esResultadoFinal = true)
                        }
                        // SIEMPRE reiniciar escucha mientras el usuario no la apague manualmente
                        if (escuchando) {
                            programarReinicio()
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val coincidencias = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        coincidencias?.forEach { texto ->
                            procesarTextoReconocido(texto, esResultadoFinal = false)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } catch (e: Exception) {
            reportarVoz("Error creando reconocedor: ${e.message}")
        }
    }

    private fun iniciarReconocimientoInterno() {
        if (!escuchando) return
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                // Se deja en false por defecto para evitar bloqueo ERROR 12/13 en teléfonos sin paquete offline descargado
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            recrearReconocedorLimpio()
            programarReinicio()
        }
    }

    private fun procesarTextoReconocido(textoOriginal: String, esResultadoFinal: Boolean) {
        if (!escuchando || textoOriginal.isBlank()) return

        val recorte500 = textoOriginal.take(500)
        _textoTranscritoEnVivo.value = recorte500
        val textoNorm = normalizarTexto(recorte500)

        if (esResultadoFinal) {
            reportarVoz("Reconocido: \"$textoNorm\"")
        }

        // 1. Detectar palabra clave de pánico (Con Cooldown de 2.5s para permitir múltiples disparos seguidos)
        val ahora = System.currentTimeMillis()
        if (ahora - ultimoDisparoVozMs >= COOLDOWN_ENTRE_ALERTAS_VOZ_MS) {
            val palabraDetectada = palabrasClave.firstOrNull { palabra ->
                Regex("""(?<![\p{L}\p{N}])${Regex.escape(palabra)}(?![\p{L}\p{N}])""").containsMatchIn(textoNorm)
            }

            if (palabraDetectada != null) {
                ultimoDisparoVozMs = ahora
                reportarVoz("🚨 ¡PALABRA CLAVE DETECTADA!: '$palabraDetectada' -> Enviando SOS")
                onDetonadorVozActivado("PALABRA_CLAVE_$palabraDetectada")
                // NO apagamos `escuchando` ni destruimos el reconocedor para que pueda disparar una segunda o tercera vez
            }
        }

        // 2. Clasificar con IA-1 si dice una frase descriptiva (A-06)
        if (esResultadoFinal && recorte500.length >= 10) {
            val resultadoIA1 = clasificarIncidenteConIA1Local(recorte500)
            _ultimaClasificacionIA1.value = resultadoIA1
            onRelatoTranscritoYClasificado(recorte500, resultadoIA1)
        }
    }

    fun clasificarIncidenteConIA1Local(relato: String): ResultadoClasificacionIA1 {
        val inicioNs = System.nanoTime()
        val norm = normalizarTexto(relato)

        data class ReglaCatalogo(
            val id: Int,
            val nombre: String,
            val categoria: String,
            val prioridad: Int,
            val tokens: List<String>,
            val queResumen: String
        )

        val catalogo = listOf(
            ReglaCatalogo(1, "Persona sospechosa siguiéndome", "FISICO", 1,
                listOf("siguiendo", "sigue", "persigue", "sospechoso", "sospechosa", "detras", "acercando"),
                "Seguimiento por un desconocido"),
            ReglaCatalogo(2, "Asalto o robo", "FISICO", 1,
                listOf("asalto", "asaltaron", "robo", "robaron", "celular", "arma", "navaja", "pistola", "moto", "quitaron"),
                "Asalto o despojo de pertenencias"),
            ReglaCatalogo(3, "Agresión física", "FISICO", 1,
                listOf("golpe", "golpeando", "pego", "agresion", "atacando", "pelea", "empujo"),
                "Agresión física directa"),
            ReglaCatalogo(4, "Emergencia médica", "FISICO", 1,
                listOf("desmayo", "herido", "sangre", "ambulancia", "respirar", "infarto", "caida", "lesion"),
                "Urgencia médica en campus o trayecto"),
            ReglaCatalogo(5, "Intento de privación de libertad", "SOCIAL", 1,
                listOf("subir", "camioneta", "forzar", "llevar", "secuestro", "jalando", "encerrar"),
                "Intento de retención o privación de la libertad"),
            ReglaCatalogo(6, "Acoso", "SOCIAL", 2,
                listOf("acoso", "acosando", "comentarios", "tocando", "morboso", "insiste", "molestando"),
                "Acoso u hostigamiento en vía pública"),
            ReglaCatalogo(7, "Extorsión o amenaza", "DIGITAL", 3,
                listOf("amenaza", "amenazando", "extorsion", "dinero", "deposito", "mensajes"),
                "Amenaza o intento de extorsión")
        )

        var mejorMatch = catalogo.first()
        var maxCoincidencias = 0

        for (regla in catalogo) {
            val hits = regla.tokens.count { norm.contains(it) }
            if (hits > maxCoincidencias) {
                maxCoincidencias = hits
                mejorMatch = regla
            }
        }

        val confianzaCalculada = when {
            maxCoincidencias >= 3 -> 0.94f
            maxCoincidencias == 2 -> 0.91f
            maxCoincidencias == 1 -> 0.78f
            else -> 0.45f
        }

        val superaUmbral = confianzaCalculada >= umbralConfianzaIA1
        val latenciaMs = ((System.nanoTime() - inicioNs) / 1_000_000L).coerceAtLeast(12L)

        return ResultadoClasificacionIA1(
            tipoSugeridoId = if (superaUmbral) mejorMatch.id else 0,
            nombreTipoSugerido = if (superaUmbral) mejorMatch.nombre else "Genérica",
            categoria = mejorMatch.categoria,
            prioridadCatalogo = mejorMatch.prioridad,
            confianza = confianzaCalculada,
            superaUmbral = superaUmbral,
            que = if (superaUmbral) mejorMatch.queResumen else "Incidente reportado por voz",
            donde = "Ubicación GNSS actual",
            quien = "Extraído de relato de voz",
            cuando = "En este momento, continúa",
            latenciaMs = latenciaMs
        )
    }

    private fun normalizarTexto(texto: String): String {
        return Normalizer.normalize(texto.lowercase(Locale.forLanguageTag("es-MX")), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .trim()
    }

    private fun programarReinicio() {
        if (!escuchando) return
        mainHandler.removeCallbacks(reiniciarEscucha)
        mainHandler.postDelayed(reiniciarEscucha, TIEMPO_REINICIO)
    }

    fun detenerEscuchaMicrofono() {
        escuchando = false
        _escuchandoMicrofono.value = false
        mainHandler.removeCallbacks(reiniciarEscucha)
        destruirReconocedor()
        reportarVoz("⏹️ Reconocimiento de voz detenido")
    }

    private fun destruirReconocedor() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (_: Exception) {}
    }
}