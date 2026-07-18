package com.stepcast.app.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stepcast.app.R
import com.stepcast.app.StepcastApplication
import com.stepcast.app.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Refreshes every subscribed feed. Runs periodically in the background
 * (notifying about new episodes) and on demand from the Library screen
 * (silently — the UI updates through Room flows).
 */
class RefreshWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as StepcastApplication
        val force = inputData.getBoolean(KEY_FORCE, false)
        // category-scoped run (automation's REFRESH_CATEGORY): only that
        // category's members, matched case-insensitively
        val categoryIds = inputData.getString(KEY_CATEGORY)?.let { asked ->
            val names = app.repository.categoryMetaList().map { it.name }
            val real = names.firstOrNull { it.equals(asked, ignoreCase = true) }
                ?: asked
            app.repository.categoryMemberIds(real).toHashSet()
        }
        // Schedule paradigm: per-show rules + global checkpoints/quiet hours
        // (ScheduleEngine); Automatic-mode shows also check shortly after
        // their inferred expected release (ReleasePattern over pubDates).
        val cfg = scheduleConfig()
        val now = System.currentTimeMillis()
        val due = app.repository.allPodcasts().filter { podcast ->
            if (categoryIds != null && podcast.id !in categoryIds) return@filter false
            if (force) return@filter true
            ScheduleEngine.isDue(
                mode = podcast.scheduleMode,
                param = podcast.scheduleParam,
                lastRefreshedMs = podcast.lastRefreshed,
                expectedReleaseMs = expectedReleaseFor(app, podcast),
                nowMs = now,
                cfg = cfg
            )
        }
        // parallel fetch, BOUNDED: a big library refreshes fast, but a
        // 300-feed library must not open 300 sockets/parsers at once —
        // unbounded parallelism crash-looped large installs
        val gate = Semaphore(4)
        val results = coroutineScope {
            due.map { podcast ->
                async {
                    gate.withPermit {
                        runCatching { app.repository.refresh(podcast.id) }
                            .getOrElse {
                                app.repository.recordRefreshFailure(podcast.id)
                                0
                            } to podcast.title
                    }
                }
            }.awaitAll()
        }
        val newCount = results.sumOf { it.first }
        val updatedPodcasts = results.filter { it.first > 0 }.map { it.second }
        if (newCount > 0 && inputData.getBoolean(KEY_NOTIFY, true) &&
            com.stepcast.app.data.AppSettings.newEpisodeNotifications
        ) {
            postNewEpisodesNotification(newCount, updatedPodcasts)
        }
        // the hourly periodic tick is only the safety net — plan a precise
        // wake-up at the earliest next promise (checkpoint, expected release,
        // pinned slot) so 6:30 means 6:30, not "the tick after 6:30"
        planNextCheck(app)
        Result.success()
    }

    private suspend fun planNextCheck(app: StepcastApplication) {
        val cfg = scheduleConfig()
        val now = System.currentTimeMillis()
        val next = app.repository.allPodcasts().mapNotNull { podcast ->
            ScheduleEngine.nextCheck(
                mode = podcast.scheduleMode,
                param = podcast.scheduleParam,
                lastRefreshedMs = podcast.lastRefreshed,
                expectedReleaseMs = expectedReleaseFor(app, podcast),
                nowMs = now,
                cfg = cfg
            )?.timeMs
        }.minOrNull() ?: return
        val delayMs = (next - now).coerceIn(5 * 60_000L, 6 * 3_600_000L)
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "feed-refresh-planned", ExistingWorkPolicy.REPLACE, request
        )
    }

    private fun scheduleConfig(): ScheduleEngine.Config {
        val settings = com.stepcast.app.data.AppSettings
        return ScheduleEngine.Config(
            checkpointMinutes = settings.enabledCheckpointMinutes(),
            quietEnabled = settings.quietHoursEnabled,
            quietStartMinutes = settings.quietStartMinutes,
            quietEndMinutes = settings.quietEndMinutes
        )
    }

    private suspend fun expectedReleaseFor(
        app: StepcastApplication,
        podcast: com.stepcast.app.data.Podcast
    ): Long? {
        if (podcast.scheduleMode != ScheduleEngine.MODE_AUTO) return null
        if (podcast.localFolderUri != null) return null
        val pubs = app.repository.recentPubDates(podcast.id)
        val pattern = ReleasePattern.infer(pubs)
        return ReleasePattern.nextExpectedMs(
            pattern, pubs.firstOrNull() ?: 0L, System.currentTimeMillis()
        )
    }

    private fun postNewEpisodesNotification(count: Int, podcasts: List<String>) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "New episodes", NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (count == 1) "1 new episode" else "$count new episodes")
            .setContentText(podcasts.distinct().joinToString(", "))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val KEY_NOTIFY = "notify"
        private const val KEY_FORCE = "force"
        private const val KEY_CATEGORY = "category"
        private const val CHANNEL_ID = "new_episodes"
        private const val NOTIFICATION_ID = 100

        fun schedulePeriodic(context: Context) {
            // hourly tick; per-category cadence decides which feeds are due
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "feed-refresh", ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }

        /** Silent, immediate refresh of everything (Library refresh button). */
        fun refreshNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setInputData(workDataOf(KEY_NOTIFY to false, KEY_FORCE to true))
                .build()
            // REPLACE: a forced refresh must actually run, even if an
            // earlier one is still queued (KEEP silently dropped it)
            WorkManager.getInstance(context).enqueueUniqueWork(
                "feed-refresh-now", ExistingWorkPolicy.REPLACE, request
            )
        }

        /** Silent, immediate refresh of one category (automation surface). */
        fun refreshCategoryNow(context: Context, category: String) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setInputData(
                    workDataOf(
                        KEY_NOTIFY to false,
                        KEY_FORCE to true,
                        KEY_CATEGORY to category
                    )
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "feed-refresh-category-$category", ExistingWorkPolicy.REPLACE, request
            )
        }
    }
}
