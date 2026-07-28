package com.stepcast.app.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
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
        private const val TMP_NAME = "stepcast-auto-backup.json.tmp"
        private const val PREV_NAME = "stepcast-auto-backup.prev.json"
        private const val WORK_NAME = "auto-backup"
        private const val WORK_NAME_NOW = "auto-backup-now"

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
                // write into a temp first: a failure mid-export must not
                // destroy the existing backup (the old delete-then-create
                // left NOTHING behind when the export died)
                tree.findFile(TMP_NAME)?.delete()
                val tmp = tree.createFile("application/json", TMP_NAME)
                    ?: return "Couldn't create the backup file"
                StepcastBackup.export(context, repository, tmp.uri)
                // keep one previous generation, then swap the fresh file in
                tree.findFile(PREV_NAME)?.delete()
                tree.findFile(FILE_NAME)?.renameTo(PREV_NAME)
                tmp.renameTo(FILE_NAME)
                AppSettings.setLastAutoBackupMs(context, System.currentTimeMillis())
                null
            }.getOrElse { it.message ?: "Backup failed" }
        }

        fun schedule(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AutoBackupWorker>(7, TimeUnit.DAYS).build()
            )
            // first enable shouldn't wait a week for its first backup; guard
            // on "never backed up" because schedule() also runs at app start
            if (AppSettings.lastAutoBackupMs == 0L) {
                workManager.enqueueUniqueWork(
                    WORK_NAME_NOW,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<AutoBackupWorker>().build()
                )
            }
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_NOW)
        }
    }
}
