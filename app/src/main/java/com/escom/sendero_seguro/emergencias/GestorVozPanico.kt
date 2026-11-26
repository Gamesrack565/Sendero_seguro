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
    private val onRelatoTranscritoYClasificado: (texto: String, clasificacion: ResultadoClasificacionIA1) -> Unit = { _, _ -> },
    /** Con internet el dictado usa el reconocedor de Google (mejor calidad); sin él, Vosk en el teléfono. */
    private val hayInternet: () -> Boolean = { true }
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

    // A-06: dictado del relato. La persona habla, el texto se acumula (máx. 500) y IA-1 sugiere el tipo.
    // En este modo NO se buscan palabras clave: decir «ayuda» en el relato no debe disparar otra alerta.
    @Volatile private var modoDictado = false
    private var textoAcumuladoDictado = ""
    private var preferirOffline = false
    private val vozOffline = ReconocedorVozOffline(context.applicationContext)

    /** Qué motor transcribe el relato en A-06: el del teléfono (sin internet) o el de Google. */
    enum class MotorDictado { TELEFONO, INTERNET }

    private val _motorDictado = MutableStateFlow<MotorDictado?>(null)
    val motorDictado: StateFlow<MotorDictado?> = _motorDictado.asStateFlow()

    /** Carga el modelo de voz sin internet antes de necesitarlo (al iniciar una cuenta regresiva). */
    fun prepararVozSinInternet() = vozOffline.precargar()

    private val _dictandoRelato = MutableStateFlow(false)
    val dictandoRelato: StateFlow<Boolean> = _dictandoRelato.asStateFlow()

    private val _errorDictado = MutableStateFlow<String?>(null)
    val errorDictado: StateFlow<String?> = _errorDictado.asStateFlow()

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

    /**
     * A-06: empieza a transcribir el relato. Sigue escuchando entre pausas hasta [detenerDictadoRelato]
     * (la vista lo limita a 60 s). Con internet usa el reconocedor de Google; sin internet, o si el de Google
     * falla por red o idioma, usa Vosk en el teléfono, así que funciona con y sin señal.
     */
    fun iniciarDictadoRelato(textoPrevio: String = "") {
        _errorDictado.value = null
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _errorDictado.value = "Sin permiso de micrófono. Puedes escribirlo."
            return
        }
        modoDictado = true
        preferirOffline = false
        textoAcumuladoDictado = textoPrevio.take(500)
        _textoTranscritoEnVivo.value = textoAcumuladoDictado
        _ultimaClasificacionIA1.value = null
        _dictandoRelato.value = true
        escuchando = true
        if (hayInternet() && SpeechRecognizer.isRecognitionAvailable(context)) {
            _motorDictado.value = MotorDictado.INTERNET
            recrearReconocedorLimpio()
            iniciarReconocimientoInterno()
        } else {
            iniciarDictadoEnTelefono()
        }
    }

    private fun iniciarDictadoEnTelefono() {
        mainHandler.removeCallbacks(reiniciarEscucha)
        destruirReconocedor()
        _motorDictado.value = MotorDictado.TELEFONO
        _escuchandoMicrofono.value = true
        reportarVoz("🎙️ Dictado en el teléfono (sin internet)")
        vozOffline.iniciar(
            onParcial = { procesarDictado(it, esResultadoFinal = false) },
            onFrase = { procesarDictado(it, esResultadoFinal = true) },
            onError = { terminarDictadoConError(it) }
        )
    }

    /** A-06: deja de escuchar y clasifica con IA-1 lo dictado. Devuelve el texto final. */
    fun detenerDictadoRelato(): String {
        val texto = _textoTranscritoEnVivo.value.trim()
        escuchando = false
        modoDictado = false
        _dictandoRelato.value = false
        _escuchandoMicrofono.value = false
        mainHandler.removeCallbacks(reiniciarEscucha)
        destruirReconocedor()
        vozOffline.detener()
        _motorDictado.value = null
        textoAcumuladoDictado = texto
        if (texto.length >= 10) _ultimaClasificacionIA1.value = clasificarIncidenteConIA1Local(texto)
        return texto
    }

    /** A-06: la persona editó el texto a mano; la sugerencia anterior ya no aplica. */
    fun reemplazarTextoRelato(texto: String) {
        textoAcumuladoDictado = texto.take(500)
        _textoTranscritoEnVivo.value = textoAcumuladoDictado
        _ultimaClasificacionIA1.value = null
    }

    /** A-06: clasifica el texto actual (botón «Analizar con IA»). */
    fun clasificarRelatoActual(): ResultadoClasificacionIA1? {
        val texto = _textoTranscritoEnVivo.value.trim()
        if (texto.length < 10) return null
        return clasificarIncidenteConIA1Local(texto).also { _ultimaClasificacionIA1.value = it }
    }

    /** A-06 al salir de la pantalla: apaga el micrófono y limpia el relato para la siguiente alerta. */
    fun limpiarDictadoRelato() {
        if (modoDictado) detenerDictadoRelato()
        textoAcumuladoDictado = ""
        _textoTranscritoEnVivo.value = ""
        _ultimaClasificacionIA1.value = null
        _errorDictado.value = null
    }

    fun iniciarEscuchaMicrofono() {
        modoDictado = false
        _dictandoRelato.value = false
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
                        if (modoDictado && manejarErrorDictado(error)) return
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
                        if (modoDictado) {
                            coincidencias?.firstOrNull()?.let { procesarDictado(it, esResultadoFinal = true) }
                        } else coincidencias?.forEach { texto ->
                            procesarTextoReconocido(texto, esResultadoFinal = true)
                        }
                        // SIEMPRE reiniciar escucha mientras el usuario no la apague manualmente
                        if (escuchando) {
                            programarReinicio()
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val coincidencias = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (modoDictado) {
                            coincidencias?.firstOrNull()?.let { procesarDictado(it, esResultadoFinal = false) }
                            return
                        }
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
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, if (modoDictado) 1 else 5)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
                // Palabras clave: false para evitar ERROR 12/13 en teléfonos sin paquete offline descargado.
                // Dictado (A-06): se intenta en el teléfono y, si falla por idioma, se reintenta sin preferirlo.
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, modoDictado && preferirOffline)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            recrearReconocedorLimpio()
            programarReinicio()
        }
    }

    private fun procesarDictado(texto: String, esResultadoFinal: Boolean) {
        if (!escuchando || texto.isBlank()) return
        val completo = "$textoAcumuladoDictado ${texto.trim()}".trim().take(500)
        _textoTranscritoEnVivo.value = completo
        if (esResultadoFinal) {
            textoAcumuladoDictado = completo
            if (completo.length >= 10) _ultimaClasificacionIA1.value = clasificarIncidenteConIA1Local(completo)
        }
    }

    /** Devuelve true si el error ya se atendió (reintento o fin del dictado). */
    private fun manejarErrorDictado(error: Int): Boolean {
        when (error) {
            // Sin red o sin paquete de idioma de Google: se sigue con Vosk en el teléfono, sin perder lo dictado.
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> {
                Log.w(TAG, "Reconocedor de Google falló ($error); el dictado sigue en el teléfono")
                iniciarDictadoEnTelefono()
                return true
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                terminarDictadoConError("Sin permiso de micrófono. Puedes escribirlo.")
                return true
            }
        }
        // Silencio o no se entendió: se sigue escuchando hasta que la persona lo detenga.
        return false
    }

    private fun terminarDictadoConError(mensaje: String) {
        detenerDictadoRelato()
        _errorDictado.value = mensaje
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

    /** IA-1 en el teléfono (sin internet): ver [ClasificadorIA1]. */
    fun clasificarIncidenteConIA1Local(relato: String): ResultadoClasificacionIA1 =
        ClasificadorIA1.clasificar(relato, umbralConfianzaIA1)

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
        modoDictado = false
        _dictandoRelato.value = false
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