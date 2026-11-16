package com.escom.sendero_seguro.emergencias

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.escom.sendero_seguro.MainActivity

data class AvisoAlertaMalla(
    val alertaId: String,
    val folio: String,
    val estado: String,
    val creadoEn: Long,
)

object AvisoMallaUsuario {
    private const val TAG = "AvisoMallaUsuario"
    private const val PREFS = "aviso_alerta_malla"
    private const val CHANNEL_ID = "alerta_retransmitida_malla"
    private const val NOTIFICATION_ID_BASE = 4100
    private const val AVISO_VISIBLE_MS = 24 * 60 * 60 * 1000L

    fun guardarYNotificar(context: Context, alertaId: String, folio: String, estado: String) {
        val appContext = context.applicationContext
        val creadoEn = System.currentTimeMillis()
        val guardado = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("alerta_id", alertaId)
            .putString("folio", folio)
            .putString("estado", estado)
            .putLong("creado_en", creadoEn)
            .commit()
        if (!guardado) Log.e(TAG, "No se pudo guardar localmente el aviso de relevo por malla.")

        mostrarNotificacion(appContext, alertaId, folio)
    }

    fun leerAvisoReciente(context: Context): AvisoAlertaMalla? {
        val preferencias = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val creadoEn = preferencias.getLong("creado_en", 0L)
        if (creadoEn == 0L) return null
        if (System.currentTimeMillis() - creadoEn > AVISO_VISIBLE_MS) {
            preferencias.edit()
                .remove("alerta_id")
                .remove("folio")
                .remove("estado")
                .remove("creado_en")
                .apply()
            return null
        }
        val alertaId = preferencias.getString("alerta_id", null) ?: return null
        val folio = preferencias.getString("folio", null) ?: return null
        val estado = preferencias.getString("estado", null) ?: return null
        return AvisoAlertaMalla(alertaId, folio, estado, creadoEn)
    }

    fun yaSeSolicitoPermisoNotificaciones(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("permiso_notificaciones_solicitado", false)

    fun marcarSolicitudPermisoNotificaciones(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("permiso_notificaciones_solicitado", true).apply()
    }

    private fun mostrarNotificacion(context: Context, alertaId: String, folio: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Alertas recibidas por red malla",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Confirma cuando otro dispositivo retransmite tu alerta al servidor."
                }
            )
        }

        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context,
            alertaId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val texto = "Otro dispositivo retransmitió tu alerta y el servidor la registró ($folio)."
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Alerta recibida por red malla")
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_BASE + alertaId.hashCode(), notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Android no permitió mostrar la notificación de relevo por malla.", e)
        }
    }
}
