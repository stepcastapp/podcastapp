package com.stepcast.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stepcast.app.StepcastApplication
import java.util.concurrent.TimeUnit

/** Runs [GpodderSync] hourly (and on demand) while sync is configured. */
class SyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!GpodderSync.config(applicationContext).enabled) return Result.success()
        val app = applicationContext as StepcastApplication
        val error = GpodderSync.syncNow(applicationContext, app.repository)
            ?: return Result.success()
        // a rejected password won't fix itself; network trouble might
        return if (error.startsWith("Sign-in rejected") || runAttemptCount >= 3) {
            Result.failure()
        } else {
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC = "gpodder-sync"
        private const val NOW = "gpodder-sync-now"

        private val network = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Called at app start and whenever the sync settings change. */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (!GpodderSync.config(context).enabled) {
                wm.cancelUniqueWork(PERIODIC)
                return
            }
            wm.enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                    .setConstraints(network)
                    .build()
            )
        }

        fun syncNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network).build()
            )
        }
    }
}
