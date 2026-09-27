package com.stepcast.app.sync

import android.content.Context
import com.stepcast.app.data.EpisodeStateRestore
import com.stepcast.app.data.Http
import com.stepcast.app.data.PlaybackJournal
import com.stepcast.app.data.PodcastRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Subscription + listening-progress sync with a gPodder-API server:
 * **gpodder.net** (API v2) or a **Nextcloud** with the "gPodder Sync" app.
 * Both speak the same payloads — subscription deltas `{add, remove,
 * timestamp}` and episode actions `[{podcast, episode, guid, action:"play",
 * timestamp, started, position, total}]` — only the paths differ.
 *
 * Local changes are derived, not logged: subscriptions diff against the
 * set last agreed with the server; episode progress is every row whose
 * lastPlayedMs / playedAtMs moved since the last sync. Remote progress for
 * episodes this phone doesn't have yet is staged through
 * [EpisodeStateRestore] and lands when that feed refreshes.
 *
 * Credentials live in SharedPreferences, which the backup rules keep OUT
 * of Google's cloud backup.
 */
object GpodderSync {

    const val PROVIDER_OFF = 0
    const val PROVIDER_NEXTCLOUD = 1
    const val PROVIDER_GPODDER_NET = 2

    private const val PREFS = "stepcast_sync"
    private const val DEVICE_ID = "stepcast-android"

    data class Config(
        val provider: Int,
        val server: String,
        val user: String,
        val password: String
    ) {
        val enabled get() = provider != PROVIDER_OFF && user.isNotBlank() && password.isNotBlank()
    }

    fun config(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            provider = p.getInt("provider", PROVIDER_OFF),
            server = p.getString("server", "").orEmpty(),
            user = p.getString("user", "").orEmpty(),
            password = p.getString("password", "").orEmpty()
        )
    }

    fun saveConfig(context: Context, config: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("provider", config.provider)
            .putString("server", config.server.trim().removeSuffix("/"))
            .putString("user", config.user.trim())
            .putString("password", config.password)
            // a different account/server starts from scratch
            .remove("subsSince").remove("actionsSince").remove("agreedFeeds")
            .remove("lastLocalSyncMs")
            .apply()
    }

    fun lastResult(context: Context): Pair<Long, String?> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getLong("lastSyncMs", 0L) to p.getString("lastError", null)
    }

    private val json = "application/json; charset=utf-8".toMediaType()

    /** Runs one full sync. Returns null on success, else a short error. */
    suspend fun syncNow(context: Context, repository: PodcastRepository): String? =
        withContext(Dispatchers.IO) {
            val cfg = config(context)
            if (!cfg.enabled) return@withContext "Sync is not set up"
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val error = runCatching {
                if (cfg.provider == PROVIDER_GPODDER_NET) registerDevice(cfg)
                syncSubscriptions(context, cfg, repository)
                syncEpisodeActions(context, cfg, repository)
            }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
            prefs.edit()
                .putLong("lastSyncMs", System.currentTimeMillis())
                .putString("lastError", error)
                .apply()
            PlaybackJournal.logSchedule("sync", error ?: "ok")
            error
        }

    // ---- endpoints ----------------------------------------------------------

    private fun base(cfg: Config): HttpUrl = when (cfg.provider) {
        PROVIDER_GPODDER_NET -> (cfg.server.ifBlank { "https://gpodder.net" }).toHttpUrl()
        else -> cfg.server.toHttpUrl()
    }

    private fun subscriptionsUrl(cfg: Config): HttpUrl.Builder = when (cfg.provider) {
        PROVIDER_GPODDER_NET -> base(cfg).newBuilder()
            .addPathSegments("api/2/subscriptions/${cfg.user}/$DEVICE_ID.json")
        else -> base(cfg).newBuilder().addPathSegments("index.php/apps/gpoddersync/subscriptions")
    }

    private fun actionsGetUrl(cfg: Config): HttpUrl.Builder = when (cfg.provider) {
        PROVIDER_GPODDER_NET -> base(cfg).newBuilder().addPathSegments("api/2/episodes/${cfg.user}.json")
        else -> base(cfg).newBuilder().addPathSegments("index.php/apps/gpoddersync/episode_action")
    }

    private fun actionsPostUrl(cfg: Config): HttpUrl = when (cfg.provider) {
        PROVIDER_GPODDER_NET -> base(cfg).newBuilder()
            .addPathSegments("api/2/episodes/${cfg.user}.json").build()
        else -> base(cfg).newBuilder()
            .addPathSegments("index.php/apps/gpoddersync/episode_action/create").build()
    }

    private fun call(cfg: Config, request: Request.Builder): JSONObject? {
        val req = request.header("Authorization", Credentials.basic(cfg.user, cfg.password)).build()
        Http.api.newCall(req).execute().use { response ->
            if (response.code == 401) throw IOException("Sign-in rejected (check user/password)")
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from ${req.url.host}")
            val body = response.body?.string().orEmpty()
            return if (body.isBlank()) null else runCatching { JSONObject(body) }.getOrNull()
        }
    }

    private fun callArray(cfg: Config, request: Request.Builder): JSONObject? = call(cfg, request)

    /** gpodder.net wants the device to exist before subscriptions reference it. */
    private fun registerDevice(cfg: Config) {
        val url = base(cfg).newBuilder()
            .addPathSegments("api/2/devices/${cfg.user}/$DEVICE_ID.json").build()
        val body = JSONObject().put("caption", "Stepcast").put("type", "mobile")
        call(cfg, Request.Builder().url(url).post(body.toString().toRequestBody(json)))
    }

    // ---- subscriptions ------------------------------------------------------

    private suspend fun syncSubscriptions(
        context: Context,
        cfg: Config,
        repository: PodcastRepository
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val since = prefs.getLong("subsSince", 0L)
        val agreed = prefs.getStringSet("agreedFeeds", null)?.toSet()
        val local = repository.subscribedPodcastList()
            .filter { it.localFolderUri == null }
            .map { it.feedUrl }
            .toSet()

        // 1. pull what changed elsewhere
        val remote = call(
            cfg,
            Request.Builder().url(subscriptionsUrl(cfg).addQueryParameter("since", since.toString()).build())
        ) ?: JSONObject()
        val remoteAdd = remote.optJSONArray("add").strings()
        val remoteRemove = remote.optJSONArray("remove").strings()
        var timestamp = remote.optLong("timestamp", since)

        // first sync ever: merge, never delete anything on either side
        val firstSync = agreed == null
        for (url in remoteAdd) {
            if (repository.podcastIdForFeed(url) == null) {
                repository.importPodcastStub(url, "", null, null,
                    com.stepcast.app.data.AppSettings.defaultKeepDownloads, 0)
            }
        }
        if (!firstSync) {
            for (url in remoteRemove) {
                // only if THIS phone hasn't re-added it since we last agreed
                if (url in agreed!!) {
                    repository.podcastIdForFeed(url)?.let { repository.unsubscribe(it) }
                }
            }
        }

        // 2. push local changes (everything local on the first sync)
        val nowLocal = repository.subscribedPodcastList()
            .filter { it.localFolderUri == null }.map { it.feedUrl }.toSet()
        val baseline = agreed ?: emptySet()
        val add = (nowLocal - baseline - remoteAdd.toSet())
        val remove = if (firstSync) emptySet() else (baseline - nowLocal - remoteRemove.toSet())
        if (add.isNotEmpty() || remove.isNotEmpty()) {
            val body = JSONObject()
                .put("add", JSONArray(add.toList()))
                .put("remove", JSONArray(remove.toList()))
            val result = call(
                cfg,
                Request.Builder().url(subscriptionsUrl(cfg).build())
                    .post(body.toString().toRequestBody(json))
            )
            timestamp = result?.optLong("timestamp", timestamp) ?: timestamp
        }
        prefs.edit()
            .putLong("subsSince", timestamp)
            .putStringSet("agreedFeeds", nowLocal)
            .apply()
        if (remoteAdd.isNotEmpty()) RefreshWorker.refreshNow(context)
        PlaybackJournal.logSchedule(
            "sync-subs",
            "remote +${remoteAdd.size}/-${remoteRemove.size} local +${add.size}/-${remove.size}"
        )
    }

    // ---- episode actions ----------------------------------------------------

    private suspend fun syncEpisodeActions(
        context: Context,
        cfg: Config,
        repository: PodcastRepository
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val since = prefs.getLong("actionsSince", 0L)
        val lastLocal = prefs.getLong("lastLocalSyncMs", 0L)
        val startedAt = System.currentTimeMillis()
        // snapshot local changes BEFORE applying remote ones, so what we
        // pull is never echoed straight back
        val mine = repository.progressChangedSince(lastLocal)

        // 1. pull
        val remote = callArray(
            cfg,
            Request.Builder().url(actionsGetUrl(cfg).addQueryParameter("since", since.toString()).build())
        ) ?: JSONObject()
        val actions = remote.optJSONArray("actions") ?: JSONArray()
        val staged = mutableListOf<EpisodeStateRestore.Entry>()
        var applied = 0
        for (i in 0 until actions.length()) {
            val a = actions.optJSONObject(i) ?: continue
            if (!a.optString("action").equals("play", ignoreCase = true)) continue
            val feed = a.optString("podcast").takeIf { it.isNotBlank() } ?: continue
            val audio = a.optString("episode")
            val guid = a.optString("guid")
            val positionMs = a.optLong("position", 0L) * 1000
            val totalMs = a.optLong("total", 0L) * 1000
            val atMs = parseTime(a.optString("timestamp"))
            val finished = totalMs > 0 && positionMs >= totalMs - 15_000
            val podcastId = repository.podcastIdForFeed(feed)
            val episodeId = podcastId?.let { repository.resolveEpisodeId(it, guid, audio) }
            if (episodeId == null) {
                // not here yet: lands when that feed refreshes
                staged += EpisodeStateRestore.Entry(feed, guid, audio, finished, if (finished) atMs else 0, if (finished) 0 else positionMs, false)
                continue
            }
            val local = repository.episode(episodeId) ?: continue
            // newer wins: never drag back a position listened to here later
            if (atMs <= local.lastPlayedMs && atMs <= local.playedAtMs) continue
            // applied with the REMOTE time, so the next sync doesn't see
            // these as fresh local listening and push them back
            if (finished) {
                if (!local.played) repository.markPlayedFromSync(episodeId, atMs)
            } else if (!local.played && atMs > local.lastPlayedMs) {
                repository.applySyncedPosition(episodeId, positionMs, atMs)
            }
            applied++
        }
        if (staged.isNotEmpty()) EpisodeStateRestore.stage(context, staged, emptyList())
        var timestamp = remote.optLong("timestamp", since)

        // 2. push what moved here since the last sync
        if (mine.isNotEmpty()) {
            val out = JSONArray()
            for (p in mine) {
                val totalSec = p.durationMs / 1000
                out.put(
                    JSONObject()
                        .put("podcast", p.feedUrl)
                        .put("episode", p.audioUrl)
                        .put("guid", p.guid)
                        .put("action", "play")
                        .put("timestamp", formatTime(maxOf(p.lastPlayedMs, p.playedAtMs)))
                        .put("started", 0)
                        .put("position", if (p.played) totalSec else p.positionMs / 1000)
                        .put("total", totalSec)
                        .put("device", DEVICE_ID)
                )
            }
            val result = call(
                cfg,
                Request.Builder().url(actionsPostUrl(cfg)).post(out.toString().toRequestBody(json))
            )
            timestamp = result?.optLong("timestamp", timestamp) ?: timestamp
        }
        prefs.edit()
            .putLong("actionsSince", timestamp)
            .putLong("lastLocalSyncMs", startedAt)
            .apply()
        PlaybackJournal.logSchedule(
            "sync-actions", "pulled=${actions.length()} applied=$applied staged=${staged.size} pushed=${mine.size}"
        )
    }

    private fun JSONArray?.strings(): List<String> {
        this ?: return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
    }

    /** gPodder timestamps: "2026-07-27T10:00:00" (UTC, sometimes with Z). */
    private fun parseTime(s: String): Long = runCatching {
        Instant.parse(if (s.endsWith("Z")) s else "${s}Z").toEpochMilli()
    }.getOrDefault(0L)

    private fun formatTime(ms: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms)).removeSuffix("Z")
            .substringBefore('.')
}
