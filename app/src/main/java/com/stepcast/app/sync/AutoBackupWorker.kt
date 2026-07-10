package com.stepcast.app.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stepcast.app.StepcastApplication
import com.stepcast.app.data.AppSettings
import com.stepcast.app.data.StepcastBackup
import java.util.concurrent.TimeUnit

/**
 * Writes a full Stepcast backup into the user-chosen SAF folder once a week,
 * replacing the previous one — losing a phone shouldn't mean losing years of
 * subscriptions and SmartPlays.
 */
class AutoBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (AppSettings.autoBackupFolder == null) return Result.success()
        val app = applicationContext as StepcastApplication
        return when (backupNow(applicationContext, app.repository)) {
            null -> Result.success()
            else -> Result.retry()
        }
    }

    companion object {
        private const val FILE_NAME = "stepcast-auto-backup.json"
        private const val WORK_NAME = "auto-backup"

        /**
         * Writes the backup into the configured folder right now. Returns
         * null on success or a short human-readable error. Shared by the
         * weekly worker and the Settings "Back up now" row.
         */
        suspend fun backupNow(
            context: Context,
            repository: com.stepcast.app.data.PodcastRepository
        ): String? {
            val folderUri = AppSettings.autoBackupFolder
                ?: return "No backup folder configured"
            return runCatching {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
                    ?.takeIf { it.canWrite() }
                    ?: return "Backup folder is gone or permission was revoked"
                tree.findFile(FILE_NAME)?.delete()
                // name without extension: createFile appends it from the mime
                val file = tree
                    .createFile("application/json", FILE_NAME.removeSuffix(".json"))
                    ?: return "Couldn't create the backup file"
                StepcastBackup.export(context, repository, file.uri)
                null
            }.getOrElse { it.message ?: "Backup failed" }
        }

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AutoBackupWorker>(7, TimeUnit.DAYS).build()
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
