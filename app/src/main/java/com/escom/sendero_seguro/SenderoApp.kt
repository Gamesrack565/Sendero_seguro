package com.escom.sendero_seguro

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.escom.sendero_seguro.emergencias.ModuloEmergencias
import com.escom.sendero_seguro.emergencias.remoto.ServidorAlertasRetrofit
import com.escom.sendero_seguro.identidad.ModuloIdentidad
import com.escom.sendero_seguro.identidad.dominio.EventoCancelacion
import com.escom.sendero_seguro.identidad.puertos.ClavesConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SenderoApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Módulo 1 (P1, P2, P3, P8). Se crea perezosamente para no retrasar el arranque. */
    val identidad: ModuloIdentidad by lazy { ModuloIdentidad(this) }

    /**
     * Módulo 2 (P4, P5.1, P6, P7). Uno solo para toda la app. Manda las alertas, y las que recibe por la malla, al
     * servidor con la sesión del Módulo 1, y usa el id de la cuenta que inició sesión.
     */
    val emergencias: ModuloEmergencias by lazy {
        val servidor = ServidorAlertasRetrofit.crear(BuildConfig.API_BASE_URL) { identidad.almacen.leerToken()?.token }
        ModuloEmergencias(this, servidor = servidor).also { m ->
            m.gestorMalla.tokenServidor = { identidad.almacen.leerToken()?.token }
            scope.launch {
                identidad.config.observarTodo().collect { config ->
                    config[ClavesConfig.USUARIO_ID]?.let { m.usuarioIdActual = it }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

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
                    scope.launch {
                        runCatching { identidad.sincronizarPendientes() }
                            .onFailure { Log.w("SenderoApp", "No se pudo subir lo pendiente", it) }
                    }
                }
            })
        }
    }
}
