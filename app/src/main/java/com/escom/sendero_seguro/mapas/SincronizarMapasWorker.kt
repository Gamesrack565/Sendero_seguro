package com.escom.sendero_seguro.mapas

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.escom.sendero_seguro.SenderoApp
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class SincronizarMapasWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? SenderoApp ?: return Result.failure()
        return try {
            if (!app.p12LocalHabilitado()) {
                Log.i(TAG, "Se omite P12: esta sincronización solo está habilitada para el servidor local de debug.")
                return Result.success()
            }
            if (app.prepararSesionPruebaLocal() != null) {
                Log.i(TAG, "Se omite la sincronización de mapas hasta que haya una sesión válida.")
                return Result.success()
            }
            app.mapas.sincronizar()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Falló la sincronización P12; WorkManager reintentará.", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "P12_MAPAS_WORKER"
        private const val WORK_INMEDIATO = "p12-mapas-sync"
        private const val WORK_PERIODICO = "p12-mapas-periodico"

        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun programarInmediato(context: Context) {
            val request = OneTimeWorkRequestBuilder<SincronizarMapasWorker>()
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_INMEDIATO,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun programarPeriodico(context: Context) {
            val request = PeriodicWorkRequestBuilder<SincronizarMapasWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_PERIODICO,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
