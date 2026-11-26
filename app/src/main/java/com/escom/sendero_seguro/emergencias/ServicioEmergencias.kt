package com.escom.sendero_seguro.emergencias

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

class ServicioEmergencias : Service() {

    companion object {
        private const val TAG = "MODULO2_SERVICIO"
        private const val CHANNEL_ID = "CanalEmergenciasSenderoSeguro"
        private const val NOTIFICATION_ID = 2002
        private const val EXTRA_MODO_ALERTA = "modo_alerta"

        /**
         * Con [modoAlerta] el servicio toma un wake lock para la telemetría de la alerta (P6). Sin él solo mantiene
         * vivo el proceso para el atajo de 5 presiones y la detección de caída, sin wake lock permanente.
         */
        fun iniciar(context: Context, modoAlerta: Boolean = true) {
            val intent = Intent(context, ServicioEmergencias::class.java).putExtra(EXTRA_MODO_ALERTA, modoAlerta)
            // Desde segundo plano Android 12+ no deja arrancar un servicio en primer plano
            // (ForegroundServiceStartNotAllowedException). Si ya corre, solo recibe el nuevo modo.
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo iniciar el servicio de emergencias: ${e.message}")
            }
        }

        fun detener(context: Context) {
            val intent = Intent(context, ServicioEmergencias::class.java)
            context.stopService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        crearCanalNotificacion()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notificacion = construirNotificacion()

        try {
            // Dentro de ServicioEmergencias.kt -> onStartCommand:
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14 rechaza el tipo «location» si no hay permiso de ubicación concedido.
                var tiposServicio = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    tiposServicio = tiposServicio or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    tiposServicio = tiposServicio or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(NOTIFICATION_ID, notificacion, tiposServicio)
            } else {
                startForeground(NOTIFICATION_ID, notificacion)
            }
            Log.i(TAG, "Servicio de Emergencias en primer plano ACTIVO (Soporta pantalla apagada)")
        } catch (e: Exception) {
            Log.e(TAG, "Error al iniciar Foreground Service: ${e.message}")
        }

        // Un reinicio de START_STICKY llega sin intent: se queda en modo protección.
        if (intent?.getBooleanExtra(EXTRA_MODO_ALERTA, false) == true) adquirirWakeLock() else liberarWakeLock()

        // START_STICKY obliga a Android a revivir el servicio si el sistema se queda sin RAM
        return START_STICKY
    }

    private fun liberarWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
    }

    private fun adquirirWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SenderoSeguro::Modulo2WakeLock"
        ).apply {
            setReferenceCounted(false)
            // Mantiene vivo el procesador y las antenas con la pantalla apagada
            acquire(60 * 60 * 1000L) // Hasta 1 hora continua por emergencia
        }
    }

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CHANNEL_ID,
                "Motor de Emergencias y Malla BLE",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene activa la telemetría GPS, detección de caídas y malla Bluetooth con la pantalla apagada."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(canal)
        }
    }

    private fun construirNotificacion(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sendero Seguro - Protección Activa")
            .setContentText("Monitoreando sensores, GPS y malla Bluetooth en segundo plano")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        liberarWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}