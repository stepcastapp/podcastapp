package com.stepcast.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * All-time listening stats. The playback service accumulates wall-clock
 * seconds and content seconds (media position actually consumed — which
 * runs faster than the clock under speed > 1x and silence trimming), and
 * flushes here periodically. "Time saved" = content − wall.
 */
object ListenStats {

    var wallMs by mutableStateOf(0L)
        private set
    var contentMs by mutableStateOf(0L)
        private set
    var episodesFinished by mutableStateOf(0)
        private set
    var sinceMs by mutableStateOf(0L)
        private set

    val savedMs: Long get() = (contentMs - wallMs).coerceAtLeast(0)

    fun init(context: Context) {
        val p = runBlocking { context.applicationContext.statsStore.data.first() }
        wallMs = p[longPreferencesKey(KEY_WALL)] ?: 0
        contentMs = p[longPreferencesKey(KEY_CONTENT)] ?: 0
        episodesFinished = p[intPreferencesKey(KEY_FINISHED)] ?: 0
        sinceMs = p[longPreferencesKey(KEY_SINCE)] ?: 0
    }

    /** Adds accumulated playback time and persists. */
    fun addListening(context: Context, wallDeltaMs: Long, contentDeltaMs: Long) {
        if (wallDeltaMs <= 0 && contentDeltaMs <= 0) return
        wallMs += wallDeltaMs.coerceAtLeast(0)
        contentMs += contentDeltaMs.coerceAtLeast(0)
        if (sinceMs == 0L) sinceMs = System.currentTimeMillis()
        val wall = wallMs
        val content = contentMs
        val since = sinceMs
        val appContext = context.applicationContext
        prefsWriteScope.launch {
            appContext.statsStore.edit {
                it[longPreferencesKey(KEY_WALL)] = wall
                it[longPreferencesKey(KEY_CONTENT)] = content
                it[longPreferencesKey(KEY_SINCE)] = since
            }
        }
    }

    fun addFinishedEpisode(context: Context) {
        episodesFinished += 1
        val finished = episodesFinished
        val appContext = context.applicationContext
        prefsWriteScope.launch {
            appContext.statsStore.edit {
                it[intPreferencesKey(KEY_FINISHED)] = finished
            }
        }
    }

    /**
     * Backup restore: each total rises to the backup's value, never adds —
     * restoring the same file twice must not double the numbers. The
     * "since" date moves EARLIER when the backup's history started first.
     */
    fun restoreTotals(
        context: Context,
        wallMs: Long,
        contentMs: Long,
        episodesFinished: Int,
        sinceMs: Long
    ) {
        this.wallMs = maxOf(this.wallMs, wallMs)
        this.contentMs = maxOf(this.contentMs, contentMs)
        this.episodesFinished = maxOf(this.episodesFinished, episodesFinished)
        if (sinceMs > 0 && (this.sinceMs == 0L || sinceMs < this.sinceMs)) this.sinceMs = sinceMs
        val wall = this.wallMs
        val content = this.contentMs
        val finished = this.episodesFinished
        val since = this.sinceMs
        val appContext = context.applicationContext
        prefsWriteScope.launch {
            appContext.statsStore.edit {
                it[longPreferencesKey(KEY_WALL)] = wall
                it[longPreferencesKey(KEY_CONTENT)] = content
                it[intPreferencesKey(KEY_FINISHED)] = finished
                it[longPreferencesKey(KEY_SINCE)] = since
            }
        }
    }

    fun reset(context: Context) {
        wallMs = 0
        contentMs = 0
        episodesFinished = 0
        sinceMs = System.currentTimeMillis()
        val since = sinceMs
        val appContext = context.applicationContext
        prefsWriteScope.launch {
            appContext.statsStore.edit {
                it[longPreferencesKey(KEY_WALL)] = 0L
                it[longPreferencesKey(KEY_CONTENT)] = 0L
                it[intPreferencesKey(KEY_FINISHED)] = 0
                it[longPreferencesKey(KEY_SINCE)] = since
            }
        }
    }

    fun formatDuration(ms: Long): String {
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h >= 100 -> "${h}h"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m"
        }
    }

    private const val KEY_WALL = "wallMs"
    private const val KEY_CONTENT = "contentMs"
    private const val KEY_FINISHED = "episodesFinished"
    private const val KEY_SINCE = "sinceMs"
}
