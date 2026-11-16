package com.escom.sendero_seguro.reportes

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AvisoReporteSincronizado(
    val reporteId: String,
    val resultado: String,
    val creadoEn: Long,
)

object AvisoReporteUsuario {
    private const val TAG = "AvisoReporteUsuario"
    private const val PREFERENCIAS = "aviso_reporte_p11"
    private const val CANAL_ID = "reportes_comunitarios"
    private const val ID_NOTIFICACION_BASE = 6200
    private const val AVISO_VISIBLE_MS = 24 * 60 * 60 * 1000L

    private val _ultimoAviso = MutableStateFlow<AvisoReporteSincronizado?>(null)
    val ultimoAviso: StateFlow<AvisoReporteSincronizado?> = _ultimoAviso.asStateFlow()

    fun guardarYNotificar(context: Context, reporteId: String, resultado: String) {
        val appContext = context.applicationContext
        val aviso = AvisoReporteSincronizado(reporteId, resultado, System.currentTimeMillis())
        val guardado = appContext.getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE).edit()
            .putString("reporte_id", aviso.reporteId)
            .putString("resultado", aviso.resultado)
            .putLong("creado_en", aviso.creadoEn)
            .commit()
        if (!guardado) Log.e(TAG, "No se pudo guardar el aviso del reporte sincronizado.")

        _ultimoAviso.value = aviso
        mostrarNotificacion(appContext, aviso)
    }

    fun leerAvisoReciente(context: Context): AvisoReporteSincronizado? {
        val preferencias = context.applicationContext.getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE)
        val creadoEn = preferencias.getLong("creado_en", 0L)
        if (creadoEn == 0L) return null
        if (System.currentTimeMillis() - creadoEn > AVISO_VISIBLE_MS) {
            preferencias.edit()
                .remove("reporte_id")
                .remove("resultado")
                .remove("creado_en")
                .apply()
            return null
        }
        val reporteId = preferencias.getString("reporte_id", null) ?: return null
        val resultado = preferencias.getString("resultado", null) ?: return null
        return AvisoReporteSincronizado(reporteId, resultado, creadoEn)
    }

    private fun mostrarNotificacion(context: Context, aviso: AvisoReporteSincronizado) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CANAL_ID,
                    "Reportes comunitarios",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Confirma cuando un reporte comunitario pendiente llega al servidor."
                }
            )
        }

        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context,
            aviso.reporteId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val texto = when (aviso.resultado) {
            "ACEPTADO" -> "Tu reporte fue enviado y quedó pendiente de moderación."
            else -> "El servidor ya había recibido este reporte."
        }
        val notification = NotificationCompat.Builder(context, CANAL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Reporte comunitario sincronizado")
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(context)
                .notify(ID_NOTIFICACION_BASE + aviso.reporteId.hashCode(), notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Android no permitió mostrar el aviso del reporte sincronizado.", e)
        }
    }
}
