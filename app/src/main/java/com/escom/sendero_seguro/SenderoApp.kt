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
import com.escom.sendero_seguro.mapas.ModuloMapas
import com.escom.sendero_seguro.mapas.SincronizarMapasWorker
import com.escom.sendero_seguro.mapas.remoto.ServidorMapasRetrofit
import com.escom.sendero_seguro.reportes.ModuloReportes
import com.escom.sendero_seguro.reportes.ServidorReportesRetrofit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeParseException

class SenderoApp : Application() {
    private companion object {
        const val USUARIO_PRUEBA_LOCAL_ID = "00000000-0000-4000-8000-000000000001"
        const val DISPOSITIVO_PRUEBA_LOCAL_ID = "00000000-0000-4000-8000-0000000000d1"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var tokenDesarrolloLocal: String? = null
    @Volatile private var tokenDesarrolloLocalExpiraEnMs = 0L
    @Volatile private var tokenDesarrolloLocalDispositivoId: String? = null
    @Volatile private var tokenDesarrolloLocalUsuarioId: String? = null

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

    val mapas: ModuloMapas by lazy {
        ModuloMapas(
            this,
            ServidorMapasRetrofit.crear(BuildConfig.API_BASE_URL) { tokenActualServidor() },
        )
    }

    val reportes: ModuloReportes by lazy {
        ModuloReportes(
            this,
            ServidorReportesRetrofit.crear(BuildConfig.API_BASE_URL) { tokenActualServidor() },
            obtenerDispositivoId = { dispositivoIdActualServidor() },
            obtenerUsuarioId = { usuarioIdActualServidor() },
        )
    }

    suspend fun prepararSesionPruebaLocal(): String? {
        if (tokenSesion() != null) return null
        if (usaServidorLocalDeDesarrollo()) {
            tokenDesarrolloLocalUsuarioId = tokenDesarrolloLocalUsuarioId ?: USUARIO_PRUEBA_LOCAL_ID
            tokenDesarrolloLocalDispositivoId = tokenDesarrolloLocalDispositivoId ?: DISPOSITIVO_PRUEBA_LOCAL_ID
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
                tokenDesarrolloLocalDispositivoId = token.dispositivoId
                tokenDesarrolloLocalUsuarioId = token.usuarioId
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

    /** Token de la sesión iniciada en A-03/A-02 si sigue vigente. */
    private fun tokenSesion(): String? =
        identidad.almacen.leerToken()?.takeIf { it.expiraEnMs > System.currentTimeMillis() }?.token

    // Con sesión iniciada todos los módulos usan la cuenta real, también contra el servidor local. El usuario de
    // prueba del servidor local solo se usa cuando no hay sesión (herramientas de prueba en debug).
    private fun tokenActualServidor(): String? {
        tokenSesion()?.let { return it }
        if (usaServidorLocalDeDesarrollo()) {
            return tokenDesarrolloLocal.takeIf { tokenDesarrolloLocalExpiraEnMs > System.currentTimeMillis() }
        }
        return null
    }

    private suspend fun dispositivoIdActualServidor(): String? =
        if (tokenSesion() == null && usaServidorLocalDeDesarrollo()) {
            tokenDesarrolloLocalDispositivoId
        } else {
            identidad.config.leer(ClavesConfig.DISPOSITIVO_ID)
        }

    private suspend fun usuarioIdActualServidor(): String? =
        if (tokenSesion() == null && usaServidorLocalDeDesarrollo()) {
            tokenDesarrolloLocalUsuarioId
        } else {
            identidad.config.leer(ClavesConfig.USUARIO_ID)
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

    fun p12LocalHabilitado(): Boolean = usaServidorLocalDeDesarrollo()

    override fun onCreate() {
        super.onCreate()
        ColaAlertaSyncWorker.programarInmediato(this)
        if (p12LocalHabilitado()) {
            SincronizarMapasWorker.programarInmediato(this)
            SincronizarMapasWorker.programarPeriodico(this)
        }

        // P8 → P4: al cancelar con PIN o huella (Módulo 1) se detiene la alerta del Módulo 2: GPS, envío y malla.
        // Un PIN que no coincide no detiene nada: la alerta sigue y el servidor lo marca como posible coacción
        // (flujo 3a de CU-21). La pantalla solo muestra un mensaje neutro.
        scope.launch(Dispatchers.Main) {
            identidad.cancelacion.eventos.collect { e ->
                val activa = emergencias.alertaActiva.value?.idAlerta
                if (e is EventoCancelacion.AlertaCancelada && activa == e.alertaId) {
                    emergencias.desactivarAlertaEmergencia()
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
                    if (p12LocalHabilitado()) {
                        SincronizarMapasWorker.programarInmediato(this@SenderoApp)
                    }
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
