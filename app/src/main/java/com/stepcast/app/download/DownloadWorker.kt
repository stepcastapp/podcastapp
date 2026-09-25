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
        // Resumable: bytes land in a .part file that survives a failed or
        // system-stopped attempt. Background downloads can't go foreground on
        // Android 12+, so the OS stops them after ~10 minutes — without
        // resume a big episode on a slow link restarted from byte 0 forever.
        val part = File(file.parentFile, file.name + ".part")
        val validator = File(file.parentFile, file.name + ".part.validator")
        var startWritten = 0L
        var written = 0L
        try {
            repository.setDownloadStatus(episodeId, Episode.DOWNLOAD_RUNNING)
            val existing = if (part.exists()) part.length() else 0L
            val ifRange = validator.takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
            val request = Request.Builder()
                .url(episode.audioUrl)
                .header("User-Agent", com.stepcast.app.data.Http.USER_AGENT)
                .apply {
                    // If-Range: a CHANGED file (new ad insert, re-upload)
                    // must come back whole (200), never be spliced onto
                    // the old bytes
                    if (existing > 0 && ifRange != null) {
                        header("Range", "bytes=$existing-")
                        header("If-Range", ifRange)
                    }
                }
                .build()
            http.newCall(request).execute().use { response ->
                if (response.code == 416) {
                    // our partial is unusable (or the file shrank): start over
                    part.delete()
                    validator.delete()
                    throw IOException("range not satisfiable; restarting")
                }
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty body")
                val resuming = response.code == 206
                startWritten = if (resuming) existing else 0L
                val total = if (resuming) {
                    response.header("Content-Range")
                        ?.substringAfterLast('/')?.trim()?.toLongOrNull()
                        ?: (body.contentLength().takeIf { it > 0 }?.plus(existing) ?: -1L)
                } else {
                    body.contentLength()
                }
                // remember what identifies THIS version of the file
                val newValidator = response.header("ETag")
                    ?.takeIf { !it.startsWith("W/") } // weak ETags can't validate a range
                    ?: response.header("Last-Modified")
                if (!resuming) {
                    if (newValidator != null) validator.writeText(newValidator) else validator.delete()
                }
                // don't start a download the disk can't hold (keep 200 MB spare)
                val dir = file.parentFile
                if (total > 0 && dir != null &&
                    dir.usableSpace < (total - startWritten) + FREE_SPACE_RESERVE
                ) {
                    throw IOException("not enough free space for $total bytes")
                }
                // first byte is flowing: 1% moves the row from "Waiting"
                // to "Downloading" in the downloads screen immediately
                repository.setDownloadProgress(
                    episodeId,
                    if (total > 0) ((startWritten * 100) / total).toInt().coerceAtLeast(1) else 1
                )
                if (total <= 0 && promoted) {
                    // no Content-Length: indeterminate is the honest bar
                    runCatching {
                        updateProgressNotification(episode.title, 0, indeterminate = true)
                    }
                }
                written = startWritten
                var lastPct = -1
                body.byteStream().use { input ->
                    java.io.FileOutputStream(part, resuming).use { output ->
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
                // A dropped connection can end the stream cleanly at 60% —
                // recording that as DONE yields a file that plays and "ends"
                // early (and then teaches the DB a wrong duration). When the
                // server declared a length, hold it to it. The .part stays,
                // so the retry resumes from here.
                if (total > 0 && written < total) {
                    throw IOException("truncated download: $written of $total bytes")
                }
            }
            if (file.exists()) file.delete()
            if (!part.renameTo(file)) throw IOException("could not finalize ${file.name}")
            validator.delete()
            repository.setDownloaded(episodeId, file.absolutePath)
            Result.success()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                // a user cancel discards the partial; a SYSTEM stop (10-minute
                // limit, lost Wi-Fi) keeps it — WorkManager reschedules and
                // the next run resumes instead of starting over
                if (stopReason == androidx.work.WorkInfo.STOP_REASON_CANCELLED_BY_APP) {
                    part.delete()
                    validator.delete()
                    repository.setDownloadStatus(episodeId, Episode.DOWNLOAD_NONE)
                }
            }
            throw e
        } catch (e: Exception) {
            // progress this attempt earns more retries — a flaky link that
            // keeps moving forward should finish, not give up after three
            val madeProgress = written > startWritten
            if (runAttemptCount < 2 || (madeProgress && runAttemptCount < MAX_RESUMING_ATTEMPTS)) {
                Result.retry()
            } else {
                part.delete()
                validator.delete()
                // terminal for this enqueue — counts toward the auto-retry
                // cutoff so dead enclosures stop reappearing every refresh
                repository.recordDownloadFailure(episodeId)
                Result.failure()
            }
        }
    }

    /** Expedited work needs this on pre-12 devices (it runs as a foreground service). */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val id = inputData.getLong(KEY_EPISODE_ID, -1)
        val title = (applicationContext as StepcastApplication).repository
            .episode(id)?.title.orEmpty()
        return foregroundInfo(title, 0)
    }

    private val notificationId: Int
        get() = (inputData.getLong(KEY_EPISODE_ID, 0) % 100_000).toInt() + 20_000

    private fun buildNotification(
        title: String,
        progress: Int,
        indeterminate: Boolean = false
    ): android.app.Notification {
        val context = applicationContext
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        nm?.createNotificationChannel(
            android.app.NotificationChannel(
                CHANNEL_ID, "Downloads", android.app.NotificationManager.IMPORTANCE_LOW
            )
        )
        return androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.stepcast.app.R.drawable.ic_notification_steps)
            // the EPISODE is the title: collapsed shade rows show only the
            // content title, and two anonymous "Downloading" bars told the
            // user nothing about what was coming down
            .setContentTitle(title)
            .setContentText("Downloading…")
            // determinate from 0 — the bar used to start indeterminate and
            // only turn real at the first 5% step, which rendered as a
            // glitchy half-filled sweep in the shade
            .setProgress(100, progress.coerceIn(0, 100), indeterminate)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            // a per-second timestamp next to a progress bar is just clutter
            .setShowWhen(false)
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
            .setShowWhen(false)
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
    private fun updateProgressNotification(
        title: String,
        progress: Int,
        indeterminate: Boolean = false
    ) {
        applicationContext.getSystemService(android.app.NotificationManager::class.java)
            ?.notify(notificationId, buildNotification(title, progress, indeterminate))
    }

    companion object {
        private val gate = Semaphore(2)
        private const val FREE_SPACE_RESERVE = 200L * 1024 * 1024
        private const val MAX_RESUMING_ATTEMPTS = 10
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_GROUP = "stepcast-downloads"
        private const val SUMMARY_NOTIFICATION_ID = 19_999
        // live progress notifications; the group summary dies with the last one
        private val downloadsShowing = java.util.concurrent.atomic.AtomicInteger(0)

        const val KEY_EPISODE_ID = "episodeId"

        // shared pool/cache with the rest of the app; downloads just wait longer
        private val http: OkHttpClient get() = com.stepcast.app.data.Http.downloads

        private fun fileFor(context: Context, episode: Episode): File {
            val dir = File(context.getExternalFilesDir(null), "episodes")
            dir.mkdirs()
            // extension from the LAST PATH SEGMENT only — substringAfterLast('.')
            // on the whole URL turns an extension-less enclosure
            // (…example.com/stream) into "com/stream" and the '/' makes the
            // file unopenable, permanently failing the download
            val ext = episode.audioUrl
                .substringBefore('?').substringBefore('#')
                .substringAfterLast('/')
                .substringAfterLast('.', "")
                .take(5)
                .takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() } }
                ?: "mp3"
            return File(dir, "episode-${episode.id}.$ext")
        }

        private fun workName(episodeId: Long) = "download-$episodeId"

        /**
         * Marks the episode as downloading and enqueues the work.
         * [allowMetered] is the one-shot override for THIS enqueue — the
         * global Wi-Fi-only setting stays untouched.
         */
        fun start(
            context: Context,
            episodeId: Long,
            allowMetered: Boolean = false,
            // user taps run expedited: they may go foreground from the
            // background and aren't cut off at ten minutes. Rule-driven
            // auto-downloads pass false (expedited quota is small).
            userInitiated: Boolean = true
        ) {
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
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS
                )
                .apply {
                    if (userInitiated) {
                        setExpedited(
                            androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST
                        )
                    }
                }
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
            // a force-stop mid-download can strand the "Downloading episodes"
            // group summary forever (downloadsShowing restarts at 0, so the
            // last-worker cancel never fires); any live download after this
            // restart re-posts it
            runCatching {
                context.getSystemService(android.app.NotificationManager::class.java)
                    ?.cancel(SUMMARY_NOTIFICATION_ID)
            }
            for (id in app.repository.downloadingIds()) {
                val infos = workManager.getWorkInfosForUniqueWork(workName(id)).await()
                if (infos.none { !it.state.isFinished }) {
                    app.repository.setDownloadStatus(id, Episode.DOWNLOAD_FAILED)
                }
            }
            // sweep audio files whose episode ROW is gone (deleted feed,
            // pruned episode): nothing references them, they just eat storage
            runCatching {
                val dir = File(context.getExternalFilesDir(null), "episodes")
                for (f in dir.listFiles().orEmpty()) {
                    if (!f.isFile || !f.name.startsWith("episode-")) continue
                    val id = f.name
                        .removePrefix("episode-")
                        .substringBefore('.')
                        .toLongOrNull() ?: continue
                    if (app.repository.episode(id) == null) f.delete()
                }
            }
        }

        /** Cancels a queued/running download and resets its state. */
        fun cancel(context: Context, episodeId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(episodeId))
            // belt and braces: a worker that was still queued never runs its
            // cancellation handler, so its old partial would linger
            File(context.getExternalFilesDir(null), "episodes").listFiles()
                ?.filter { it.name.startsWith("episode-$episodeId.") && it.name.contains(".part") }
                ?.forEach { it.delete() }
            val app = context.applicationContext as StepcastApplication
            CoroutineScope(Dispatchers.IO).launch {
                app.repository.setDownloadStatus(episodeId, Episode.DOWNLOAD_NONE)
            }
        }
    }
}
