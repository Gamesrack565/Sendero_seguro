package com.escom.sendero_seguro

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.escom.sendero_seguro.BuildConfig
import com.escom.sendero_seguro.cola.ColaAlertaSyncWorker
import com.escom.sendero_seguro.emergencias.ModuloEmergencias
import com.escom.sendero_seguro.emergencias.remoto.ServidorAlertasRetrofit
import com.escom.sendero_seguro.identidad.ModuloIdentidad
import com.escom.sendero_seguro.identidad.dominio.EventoCancelacion
import com.escom.sendero_seguro.identidad.puertos.ClavesConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeParseException

class SenderoApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var tokenDesarrolloLocal: String? = null
    @Volatile private var tokenDesarrolloLocalExpiraEnMs = 0L

    /** Módulo 1 (P1, P2, P3, P8). Se crea perezosamente para no retrasar el arranque. */
    val identidad: ModuloIdentidad by lazy { ModuloIdentidad(this) }

    private val servidorAlertas: ServidorAlertasRetrofit by lazy {
        ServidorAlertasRetrofit.crear(BuildConfig.API_BASE_URL) { tokenActualServidor() }
    }

    /**
     * Módulo 2 (P4, P5.1, P6, P7). Uno solo para toda la app. Manda las alertas, y las que recibe por la malla, al
     * servidor con la sesión del Módulo 1, y usa el id de la cuenta que inició sesión.
     */
    val emergencias: ModuloEmergencias by lazy {
        ModuloEmergencias(this, servidor = servidorAlertas).also { m ->
            m.gestorMalla.tokenServidor = { tokenActualServidor() }
            scope.launch {
                identidad.config.observarTodo().collect { config ->
                    config[ClavesConfig.USUARIO_ID]?.let { m.usuarioIdActual = it }
                }
            }
        }
    }

    suspend fun prepararSesionPruebaLocal(): String? {
        if (usaServidorLocalDeDesarrollo()) {
            if (tokenDesarrolloLocal != null &&
                tokenDesarrolloLocalExpiraEnMs > System.currentTimeMillis() + 60_000L
            ) return null
            return try {
                val token = servidorAlertas.tokenDeDesarrolloLocal()
                val expiraEnMs = try {
                    Instant.parse(token.expiraEn).toEpochMilli()
                } catch (e: DateTimeParseException) {
                    throw IOException("El servidor local devolvió una expiración inválida", e)
                }
                tokenDesarrolloLocal = token.token
                tokenDesarrolloLocalExpiraEnMs = expiraEnMs
                emergencias.usuarioIdActual = token.usuarioId
                null
            } catch (e: IOException) {
                Log.w("SenderoApp", "No se pudo obtener el token local; se intentará la cascada de contingencia", e)
                null
            }
        }

        val tokenUsuario = identidad.almacen.leerToken()
        if (tokenUsuario != null && tokenUsuario.expiraEnMs > System.currentTimeMillis()) return null
        return "Inicia sesión antes de enviar una alerta al servidor configurado"
    }

    private fun tokenActualServidor(): String? {
        if (usaServidorLocalDeDesarrollo()) {
            return tokenDesarrolloLocal.takeIf { tokenDesarrolloLocalExpiraEnMs > System.currentTimeMillis() }
        }
        val guardado = identidad.almacen.leerToken()
        if (guardado != null && guardado.expiraEnMs > System.currentTimeMillis()) return guardado.token
        return null
    }

    private fun usaServidorLocalDeDesarrollo(): Boolean {
        if (!BuildConfig.DEBUG) return false
        val uri = runCatching { URI(BuildConfig.API_BASE_URL) }.getOrNull() ?: return false
        val host = uri.host ?: return false
        if (uri.scheme != "http") return false
        if (host == "localhost" || host.endsWith(".localhost")) return true

        val octetos = host.split('.').map { it.toIntOrNull() ?: return false }
        if (octetos.size != 4 || octetos.any { it !in 0..255 }) return false
        return octetos[0] == 10 ||
            octetos[0] == 127 ||
            (octetos[0] == 192 && octetos[1] == 168) ||
            (octetos[0] == 172 && octetos[1] in 16..31)
    }

    override fun onCreate() {
        super.onCreate()
        ColaAlertaSyncWorker.programarInmediato(this)

        // P8 → P4: al cancelar con PIN o huella (Módulo 1) se detiene la alerta del Módulo 2: GPS, envío y malla.
        // Un PIN que no coincide no detiene nada: la alerta sigue y el servidor lo marca como posible coacción.
        // El PIN de coacción la muestra como cancelada y la deja activa.
        scope.launch(Dispatchers.Main) {
            identidad.cancelacion.eventos.collect { e ->
                val activa = emergencias.alertaActiva.value?.idAlerta
                when {
                    e is EventoCancelacion.AlertaCancelada && activa == e.alertaId ->
                        emergencias.desactivarAlertaEmergencia(false)
                    // PIN de coacción: se ve cancelada, pero sigue con GPS, envío y malla (también en la cuenta regresiva).
                    e is EventoCancelacion.CoaccionSilenciosa && (activa == null || activa == e.alertaId) ->
                        emergencias.activarModoCoaccionSilencioso()
                }
            }
        }

        // Al abrir la app y cada vez que vuelve internet se sube lo que quedó pendiente sin señal
        // (cancelaciones, intentos fallidos y contactos).
        val red = getSystemService(ConnectivityManager::class.java)
        runCatching {
            red?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    ColaAlertaSyncWorker.programarInmediato(this@SenderoApp)
                    scope.launch {
                        runCatching { identidad.sincronizarPendientes() }
                            .onFailure { Log.w("SenderoApp", "No se pudo subir lo pendiente", it) }
                        if (prepararSesionPruebaLocal() == null) {
                            runCatching { emergencias.sincronizarAlertasEnCola() }
                                .onFailure { Log.e("SenderoApp", "No se pudo sincronizar la cola de alertas", it) }
                        }
                    }
                }
            })
        }

        scope.launch {
            if (prepararSesionPruebaLocal() == null) {
                runCatching { emergencias.sincronizarAlertasEnCola() }
                    .onFailure { Log.e("SenderoApp", "No se pudo sincronizar la cola de alertas", it) }
            }
        }
    }
}
