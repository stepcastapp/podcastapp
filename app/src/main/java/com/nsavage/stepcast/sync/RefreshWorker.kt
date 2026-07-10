package com.nsavage.stepcast.sync

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
import com.nsavage.stepcast.R
import com.nsavage.stepcast.StepcastApplication
import com.nsavage.stepcast.ui.MainActivity
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
        val metas = app.repository.categoryMetaList()
        val membershipsByPodcast = app.repository.podcastCategoryList()
            .groupBy({ it.podcastId }, { it.category })
        // category-scoped run (automation's REFRESH_CATEGORY): only that
        // category's members, matched case-insensitively
        val categoryIds = inputData.getString(KEY_CATEGORY)?.let { asked ->
            val real = metas.map { it.name }
                .firstOrNull { it.equals(asked, ignoreCase = true) } ?: asked
            app.repository.categoryMemberIds(real).toHashSet()
        }
        val due = app.repository.allPodcasts().filter { podcast ->
            if (categoryIds != null && podcast.id !in categoryIds) return@filter false
            // per-category cadence, optionally anchored to a time of day;
            // a podcast in several categories is due when ANY of them fires
            val podcastMetas = membershipsByPodcast[podcast.id].orEmpty()
                .mapNotNull { name -> metas.firstOrNull { it.name == name } }
            val now = System.currentTimeMillis()
            force || if (podcastMetas.isEmpty()) {
                RefreshSchedule.isDue(
                    lastRefreshedMs = podcast.lastRefreshed,
                    freqHours = com.nsavage.stepcast.data.AppSettings.defaultRefreshHours,
                    anchorMinutes = -1,
                    nowMs = now
                )
            } else {
                podcastMetas.any { meta ->
                    RefreshSchedule.isDue(
                        lastRefreshedMs = podcast.lastRefreshed,
                        freqHours = meta.refreshHours.takeIf { it > 0 }
                            ?: com.nsavage.stepcast.data.AppSettings.defaultRefreshHours,
                        anchorMinutes = meta.anchorMinutes,
                        nowMs = now
                    )
                }
            }
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
            com.nsavage.stepcast.data.AppSettings.newEpisodeNotifications
        ) {
            postNewEpisodesNotification(newCount, updatedPodcasts)
        }
        Result.success()
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
