package com.escom.sendero_seguro.identidad.plataforma

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.escom.sendero_seguro.identidad.dominio.EstadoBateria
import com.escom.sendero_seguro.identidad.puertos.FuenteBateria
import com.escom.sendero_seguro.identidad.puertos.GestorEnergia
import com.escom.sendero_seguro.identidad.puertos.InfoHardware
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class InfoHardwareAndroid(private val context: Context) : InfoHardware {
    override fun modelo(): String = "${Build.MANUFACTURER} ${Build.MODEL}".take(80)
    override fun androidApi(): Int = Build.VERSION.SDK_INT
    override fun soportaWifiAware(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
}

/** Nivel de batería y si está cargando, a partir del broadcast ACTION_BATTERY_CHANGED. */
class FuenteBateriaAndroid(private val context: Context) : FuenteBateria {
    override fun observar(): Flow<EstadoBateria> = callbackFlow {
        val receptor = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                trySend(leer(intent))
            }
        }
        val filtro = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        // Broadcast «sticky»: el registro devuelve de inmediato el último estado.
        ContextCompat.registerReceiver(context, receptor, filtro, ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.let { trySend(leer(it)) }
        awaitClose { context.unregisterReceiver(receptor) }
    }.distinctUntilChanged()

    private fun leer(intent: Intent): EstadoBateria {
        val nivel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val escala = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val estado = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val porcentaje = if (nivel >= 0 && escala > 0) nivel * 100 / escala else 100
        val cargando = estado == BatteryManager.BATTERY_STATUS_CHARGING || estado == BatteryManager.BATTERY_STATUS_FULL
        return EstadoBateria(porcentaje, cargando)
    }
}

class GestorEnergiaAndroid(private val context: Context) : GestorEnergia {
    override fun estaExentoDeOptimizacion(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Intent para pedir la exclusión de la optimización de batería (A-12). La UI lo lanza con
     * startActivity. Requiere el permiso REQUEST_IGNORE_BATTERY_OPTIMIZATIONS en el manifiesto.
     */
    @SuppressLint("BatteryLife") // justificado: una app de alertas de emergencia (RNF5)
    fun intentSolicitarExclusion(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
}

/**
 * Huella o rostro como alternativa al PIN (A-05 y A-08). BiometricPrompt necesita una FragmentActivity,
 * por eso MainActivity hereda de FragmentActivity.
 */
object AutenticadorBiometrico {
    private const val AUTENTICADORES = BIOMETRIC_STRONG or BIOMETRIC_WEAK

    fun disponible(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTENTICADORES) == BiometricManager.BIOMETRIC_SUCCESS

    /** Devuelve true si la persona se autenticó; false si falló o canceló. */
    suspend fun autenticar(actividad: FragmentActivity, titulo: String = "Confirma que eres tú"): Boolean =
        suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(
                actividad,
                ContextCompat.getMainExecutor(actividad),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (cont.isActive) cont.resume(false)
                    }
                    // onAuthenticationFailed: el sistema deja reintentar; no se resuelve aquí.
                }
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(titulo)
                .setNegativeButtonText("Usar PIN")
                .setAllowedAuthenticators(AUTENTICADORES)
                .build()
            prompt.authenticate(info)
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
}
