package com.escom.sendero_seguro

import android.app.Application
import com.escom.sendero_seguro.emergencias.ModuloEmergencias
import com.escom.sendero_seguro.emergencias.remoto.ServidorAlertasRetrofit
import com.escom.sendero_seguro.identidad.ModuloIdentidad
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
     * Módulo 2 (P4, P6, P7). Uno solo para toda la app. Manda las alertas al servidor con la sesión del Módulo 1
     * y usa el id de la cuenta que inició sesión.
     */
    val emergencias: ModuloEmergencias by lazy {
        val servidor = ServidorAlertasRetrofit.crear(BuildConfig.API_BASE_URL) { identidad.almacen.leerToken()?.token }
        ModuloEmergencias(this, servidor = servidor).also { m ->
            scope.launch {
                identidad.config.observarTodo().collect { config ->
                    config[ClavesConfig.USUARIO_ID]?.let { m.usuarioIdActual = it }
                }
            }
        }
    }
}
