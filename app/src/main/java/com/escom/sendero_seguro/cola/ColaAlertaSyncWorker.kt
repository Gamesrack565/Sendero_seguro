package com.escom.sendero_seguro.cola

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.escom.sendero_seguro.SenderoApp
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class ColaAlertaSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? SenderoApp
            ?: return Result.failure()

        return try {
            if (app.prepararSesionPruebaLocal() != null) {
                Log.w(TAG, "No se sincroniza la cola: se requiere una sesión válida.")
                return if (app.reportes.hayReportesPendientes() ||
                    app.emergencias.hayPendientesSincronizacion()
                ) Result.retry() else Result.success()
            }

            if (!app.emergencias.sincronizarAlertasEnCola()) return Result.retry()
            if (!app.reportes.sincronizarColaOffline()) return Result.retry()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Falló el intento de sincronización de la cola local.", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "P11_COLA_WORKER"
        private const val WORK_INMEDIATO = "cola-alertas-sync"
        private const val WORK_REINTENTO = "cola-alertas-reintento"

        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        private fun request(initialDelayMs: Long = 0L) =
            OneTimeWorkRequestBuilder<ColaAlertaSyncWorker>()
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInitialDelay(initialDelayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .build()

        fun programarInmediato(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_INMEDIATO,
                ExistingWorkPolicy.KEEP,
                request()
            )
        }

        fun programarReintento(context: Context, esperaMs: Long) {
            if (esperaMs <= 0L) {
                programarInmediato(context)
                return
            }
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_REINTENTO,
                ExistingWorkPolicy.REPLACE,
                request(esperaMs)
            )
        }
    }
}
