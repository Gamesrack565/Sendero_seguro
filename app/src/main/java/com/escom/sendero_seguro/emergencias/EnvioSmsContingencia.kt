package com.escom.sendero_seguro.emergencias

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.escom.sendero_seguro.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

sealed interface ResultadoSmsContingencia {
    data object Enviado : ResultadoSmsContingencia
    data class Fallo(val motivo: String) : ResultadoSmsContingencia
}

class EnvioSmsContingencia(
    private val context: Context,
    private val numeroPasarela: String
) {
    suspend fun enviar(alerta: AlertaEmergencia): ResultadoSmsContingencia {
        val destino = numeroPasarela.trim()
        if (!BuildConfig.DEBUG || !destino.matches(Regex("\\+?[0-9]{10,15}"))) {
            return ResultadoSmsContingencia.Fallo("No hay número de pasarela SMS local configurado")
        }
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ResultadoSmsContingencia.Fallo("Falta el permiso para enviar SMS")
        }

        val accion = "${context.packageName}.SMS_ENVIADO.${UUID.randomUUID()}"
        val respuesta = CompletableDeferred<ResultadoSmsContingencia>()
        val receptor = object : BroadcastReceiver() {
            override fun onReceive(contexto: Context?, intent: Intent?) {
                if (!respuesta.isCompleted) {
                    respuesta.complete(
                        if (resultCode == Activity.RESULT_OK) ResultadoSmsContingencia.Enviado
                        else ResultadoSmsContingencia.Fallo("Android rechazó el envío SMS (código $resultCode)")
                    )
                }
            }
        }
        val filtro = IntentFilter(accion)
        var registrado = false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receptor, filtro, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receptor, filtro)
            }
            registrado = true
            val pending = PendingIntent.getBroadcast(
                context,
                accion.hashCode(),
                Intent(accion).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            context.getSystemService(SmsManager::class.java)
                .sendTextMessage(destino, null, FormatoSmsAlerta.codificar(alerta), pending, null)
            withTimeoutOrNull(TIEMPO_ESPERA_MS) { respuesta.await() }
                ?: ResultadoSmsContingencia.Fallo("No hubo confirmación del envío SMS en 30 segundos")
        } catch (e: Exception) {
            ResultadoSmsContingencia.Fallo(e.message ?: "Android no pudo iniciar el envío SMS")
        } finally {
            if (registrado) context.unregisterReceiver(receptor)
        }
    }

    private companion object {
        const val TIEMPO_ESPERA_MS = 30_000L
    }
}
