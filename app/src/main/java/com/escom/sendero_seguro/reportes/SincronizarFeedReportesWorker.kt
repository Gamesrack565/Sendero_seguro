package com.escom.sendero_seguro.reportes

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
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.TimeUnit

class SincronizarFeedReportesWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? SenderoApp ?: return Result.failure()
        return try {
            val feed = app.reportes.cargarFeedPublico()
            if (feed.desdeCache) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "No se pudo actualizar el feed de reportes comunitarios.", e)
            Result.retry()
        } catch (e: HttpException) {
            Log.w(TAG, "El servidor respondió HTTP ${e.code()} al actualizar el feed comunitario.", e)
            if (e.code() == 408 || e.code() == 429 || e.code() >= 500) Result.retry() else Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "Falló la descarga del feed comunitario.", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "P9_FEED_WORKER"
        private const val WORK_INMEDIATO = "p9-feed-reportes-sync"
        private const val WORK_PERIODICO = "p9-feed-reportes-periodico"

        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun programarInmediato(context: Context) {
            val request = OneTimeWorkRequestBuilder<SincronizarFeedReportesWorker>()
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
            val request = PeriodicWorkRequestBuilder<SincronizarFeedReportesWorker>(15, TimeUnit.MINUTES)
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
