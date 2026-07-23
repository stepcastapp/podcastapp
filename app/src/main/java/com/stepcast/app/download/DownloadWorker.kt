package com.stepcast.app.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stepcast.app.StepcastApplication
import com.stepcast.app.data.AppSettings
import com.stepcast.app.data.Episode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Streams an episode's audio into app-private storage. */
class DownloadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // a mass-import can enqueue hundreds of downloads; stream at most
        // two at once — the rest suspend without holding memory or sockets
        gate.withPermit { downloadInternal() }
    }

    private suspend fun downloadInternal(): Result = withContext(Dispatchers.IO) {
        val episodeId = inputData.getLong(KEY_EPISODE_ID, -1)
        if (episodeId <= 0) return@withContext Result.failure()
        val app = applicationContext as StepcastApplication
        val repository = app.repository
        val episode = repository.episode(episodeId) ?: return@withContext Result.failure()

        // visible, OS-protected download: a progress notification via
        // WorkManager's foreground service (may be denied when started
        // from the background on 12+ — then we just download quietly).
        // Promote ONCE — every setForeground call dispatches another start
        // command to WorkManager's shared SystemForegroundService, and with
        // parallel downloads finishing (which stops that service) a late
        // command can land during teardown, where startForeground never
        // runs and the OS kills the app with
        // ForegroundServiceDidNotStartInTimeException. Progress after the
        // promotion goes through plain notify() on the same id.
        val promoted =
            runCatching { setForeground(foregroundInfo(episode.title, 0)) }.isSuccess
        // up to two downloads stream at once (the gate), which used to mean
        // two separate progress notifications — group them under one summary
        // so the shade shows a single "Downloading" stack
        if (promoted) {
            downloadsShowing.incrementAndGet()
            runCatching { postGroupSummary() }
        }

        val file = fileFor(applicationContext, episode)
        try {
            downloadBody(episodeId, episode, file, promoted)
        } finally {
            if (promoted && downloadsShowing.decrementAndGet() <= 0) {
                runCatching {
                    applicationContext
                        .getSystemService(android.app.NotificationManager::class.java)
                        ?.cancel(SUMMARY_NOTIFICATION_ID)
                }
            }
        }
    }

    private suspend fun downloadBody(
        episodeId: Long,
        episode: Episode,
        file: File,
        promoted: Boolean
    ): Result = withContext(Dispatchers.IO) {
        val repository = (applicationContext as StepcastApplication).repository
        try {
            repository.setDownloadStatus(episodeId, Episode.DOWNLOAD_RUNNING)
            val request = Request.Builder()
                .url(episode.audioUrl)
                .header("User-Agent", "Stepcast/0.1")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty body")
                val total = body.contentLength()
                var written = 0L
                var lastPct = -1
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                val pct = ((written * 100) / total).toInt()
                                if (pct >= lastPct + 5) {
                                    lastPct = pct
                                    repository.setDownloadProgress(episodeId, pct)
                                    if (promoted) {
                                        runCatching {
                                            updateProgressNotification(episode.title, pct)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            repository.setDownloaded(episodeId, file.absolutePath)
            Result.success()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                file.delete()
                repository.setDownloadStatus(episodeId, Episode.DOWNLOAD_NONE)
            }
            throw e
        } catch (e: Exception) {
            file.delete()
            if (runAttemptCount < 2) {
                Result.retry()
            } else {
                // terminal for this enqueue — counts toward the auto-retry
                // cutoff so dead enclosures stop reappearing every refresh
                repository.recordDownloadFailure(episodeId)
                Result.failure()
            }
        }
    }

    private val notificationId: Int
        get() = (inputData.getLong(KEY_EPISODE_ID, 0) % 100_000).toInt() + 20_000

    private fun buildNotification(title: String, progress: Int): android.app.Notification {
        val context = applicationContext
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        nm?.createNotificationChannel(
            android.app.NotificationChannel(
                CHANNEL_ID, "Downloads", android.app.NotificationManager.IMPORTANCE_LOW
            )
        )
        return androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.stepcast.app.R.drawable.ic_notification_steps)
            .setContentTitle("Downloading")
            .setContentText(title)
            .setProgress(100, progress, progress <= 0)
            .setOngoing(true)
            .setSilent(true)
            .setGroup(NOTIFICATION_GROUP)
            .build()
    }

    /** One collapsed "Downloading" stack even with two parallel streams. */
    private fun postGroupSummary() {
        val context = applicationContext
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
            ?: return
        val summary = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.stepcast.app.R.drawable.ic_notification_steps)
            .setContentTitle("Downloading episodes")
            .setOngoing(true)
            .setSilent(true)
            .setGroup(NOTIFICATION_GROUP)
            .setGroupSummary(true)
            .build()
        nm.notify(SUMMARY_NOTIFICATION_ID, summary)
    }

    private fun foregroundInfo(title: String, progress: Int): ForegroundInfo {
        val notification = buildNotification(title, progress)
        return if (android.os.Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(
                notificationId,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    /** Post-promotion progress: plain notify on the id setForeground used. */
    private fun updateProgressNotification(title: String, progress: Int) {
        applicationContext.getSystemService(android.app.NotificationManager::class.java)
            ?.notify(notificationId, buildNotification(title, progress))
    }

    companion object {
        private val gate = Semaphore(2)
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_GROUP = "stepcast-downloads"
        private const val SUMMARY_NOTIFICATION_ID = 19_999
        // live progress notifications; the group summary dies with the last one
        private val downloadsShowing = java.util.concurrent.atomic.AtomicInteger(0)

        const val KEY_EPISODE_ID = "episodeId"

        private val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        private fun fileFor(context: Context, episode: Episode): File {
            val dir = File(context.getExternalFilesDir(null), "episodes")
            dir.mkdirs()
            val ext = episode.audioUrl
                .substringBefore('?')
                .substringAfterLast('.', "mp3")
                .take(5)
                .ifEmpty { "mp3" }
            return File(dir, "episode-${episode.id}.$ext")
        }

        private fun workName(episodeId: Long) = "download-$episodeId"

        /**
         * Marks the episode as downloading and enqueues the work.
         * [allowMetered] is the one-shot override for THIS enqueue — the
         * global Wi-Fi-only setting stays untouched.
         */
        fun start(context: Context, episodeId: Long, allowMetered: Boolean = false) {
            val app = context.applicationContext as StepcastApplication
            CoroutineScope(Dispatchers.IO).launch {
                app.repository.setDownloadStatus(episodeId, Episode.DOWNLOAD_RUNNING)
            }
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(KEY_EPISODE_ID to episodeId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (AppSettings.wifiOnlyDownloads && !allowMetered) {
                                NetworkType.UNMETERED
                            } else {
                                NetworkType.CONNECTED
                            }
                        )
                        .build()
                )
                .build()
            // REPLACE, not KEEP: retrying against a stale/stuck work record
            // (e.g. after a force-stop) must actually enqueue a fresh run
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(episodeId), ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * After a force-stop, episodes can be left saying "downloading"
         * with no matching WorkManager job — stuck forever and blocking
         * retries. Called at app start: anything RUNNING in the DB without
         * live work becomes FAILED, so it surfaces in the downloads dialog
         * with a Retry button.
         */
        suspend fun reconcileOrphans(context: Context) {
            val app = context.applicationContext as StepcastApplication
            val workManager = WorkManager.getInstance(context)
            for (id in app.repository.downloadingIds()) {
                val infos = workManager.getWorkInfosForUniqueWork(workName(id)).await()
                if (infos.none { !it.state.isFinished }) {
                    app.repository.setDownloadStatus(id, Episode.DOWNLOAD_FAILED)
                }
            }
        }

        /** Cancels a queued/running download and resets its state. */
        fun cancel(context: Context, episodeId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(episodeId))
            val app = context.applicationContext as StepcastApplication
            CoroutineScope(Dispatchers.IO).launch {
                app.repository.setDownloadStatus(episodeId, Episode.DOWNLOAD_NONE)
            }
        }
    }
}
