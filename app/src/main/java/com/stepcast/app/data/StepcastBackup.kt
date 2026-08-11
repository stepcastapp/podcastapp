package com.stepcast.app.data

import android.content.Context
import android.net.Uri
import com.stepcast.app.ui.theme.AccentColor
import com.stepcast.app.ui.theme.ThemeMode
import com.stepcast.app.ui.theme.ThemePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stepcast's own backup format: one JSON file carrying subscriptions
 * (with per-podcast settings), categories, SmartPlays, and app settings.
 * Episodes are deliberately NOT included — a refresh refetches them.
 * SmartPlay feed scopes are stored by feedUrl so backups survive database
 * ID changes.
 */
object StepcastBackup {

    data class Summary(val feeds: Int, val categories: Int, val smartPlays: Int)

    /**
     * org.json's optString() returns the literal string "null" for JSON
     * null — which silently poisoned restored folders/urls. Never use
     * optString for nullable fields.
     */
    private fun JSONObject.stringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    suspend fun export(context: Context, repository: PodcastRepository, uri: Uri) =
        withContext(Dispatchers.IO) {
            val json = buildJson(repository)
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(json.toString(2).toByteArray())
            } ?: throw IllegalArgumentException("Couldn't open the destination file")
        }

    suspend fun import(
        context: Context,
        repository: PodcastRepository,
        uri: Uri
    ): Summary = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalArgumentException("Couldn't open the selected file")
        val json = JSONObject(text)
        if (!json.has("stepcast")) {
            throw IllegalArgumentException("Not a Stepcast backup file")
        }
        applyJson(context, repository, json)
    }

    private suspend fun buildJson(repository: PodcastRepository): JSONObject {
        val root = JSONObject()
        root.put("stepcast", 2)
        root.put("exportedAt", System.currentTimeMillis())

        val categories = JSONArray()
        for (meta in repository.categoryMetaList()) {
            // v2 dropped refreshHours/anchorMinutes: dead since the
            // promise-based schedule replaced category cadences
            categories.put(
                JSONObject()
                    .put("name", meta.name)
                    .put("sortOrder", meta.sortOrder)
            )
        }
        root.put("categories", categories)

        val podcastsById = HashMap<Long, Podcast>()
        val podcasts = JSONArray()
        for (podcast in repository.subscribedPodcastList()) {
            podcastsById[podcast.id] = podcast
            if (podcast.localFolderUri != null) continue // SAF grants don't transfer
            podcasts.put(
                JSONObject()
                    .put("feedUrl", podcast.feedUrl)
                    .put("title", podcast.title)
                    .put("imageUrl", podcast.imageUrl ?: JSONObject.NULL)
                    .put("folder", podcast.folder ?: JSONObject.NULL)
                    .put(
                        "categories",
                        JSONArray(repository.categoriesFor(podcast.id))
                    )
                    .put("introSkipSec", podcast.introSkipSec)
                    .put("outroSkipSec", podcast.outroSkipSec)
                    .put("playbackSpeed", podcast.playbackSpeed.toDouble())
                    .put("keepDownloads", podcast.keepDownloads)
                    .put("maxAgeDays", podcast.maxAgeDays)
                    .put("episodeCap", podcast.episodeCap)
                    .put("sortOldestFirst", podcast.sortOldestFirst)
                    .put("autoQueue", podcast.autoQueue)
                    // v2: the per-show schedule rule and ad jump — restoring
                    // onto a new phone used to silently revert every show
                    // to Automatic
                    .put("scheduleMode", podcast.scheduleMode)
                    .put("scheduleParam", podcast.scheduleParam)
                    .put("adJumpSec", podcast.adJumpSec)
            )
        }
        root.put("podcasts", podcasts)

        val smartPlays = JSONArray()
        for (smartPlay in repository.smartPlayList()) {
            val entries = JSONArray()
            for (entry in repository.smartPlayEntryList(smartPlay.id)) {
                entries.put(
                    JSONObject()
                        .put(
                            "feedUrl",
                            entry.podcastId?.let { podcastsById[it]?.feedUrl }
                                ?: JSONObject.NULL
                        )
                        .put("folder", entry.folder ?: JSONObject.NULL)
                        .put("maxTracks", entry.maxTracks)
                        .put("episodeSort", entry.episodeSort)
                        .put("includePlayed", entry.includePlayed)
                        .put("downloadedOnly", entry.downloadedOnly)
                )
            }
            smartPlays.put(
                JSONObject()
                    .put("name", smartPlay.name)
                    // v2: station mode + strip order survive a restore
                    .put("continuous", smartPlay.continuous)
                    .put("sortOrder", smartPlay.sortOrder)
                    .put("entries", entries)
            )
        }
        root.put("smartPlays", smartPlays)

        root.put(
            "settings",
            JSONObject()
                .put("defaultKeepDownloads", AppSettings.defaultKeepDownloads)
                .put("seekBackSeconds", AppSettings.seekBackSeconds)
                .put("seekForwardSeconds", AppSettings.seekForwardSeconds)
                .put("adChapterAutoSkip", AppSettings.adChapterAutoSkip)
                .put("newEpisodeNotifications", AppSettings.newEpisodeNotifications)
                .put("defaultPlaybackSpeed", AppSettings.defaultPlaybackSpeed.toDouble())
                .put("wifiOnlyDownloads", AppSettings.wifiOnlyDownloads)
                .put("streamWhenNotDownloaded", AppSettings.streamWhenNotDownloaded)
                .put("skipSilence", AppSettings.skipSilence)
                .put("swipeQueueToTop", AppSettings.swipeQueueToTop)
                .put("queueNextAtBottom", AppSettings.queueNextAtBottom)
                .put("widgetOpacity", AppSettings.widgetOpacity)
                .put("swipeRightAction", AppSettings.swipeRightAction)
                .put("swipeLeftAction", AppSettings.swipeLeftAction)
                .put("themeMode", ThemePrefs.mode.name)
                .put("accentColor", ThemePrefs.accent.name)
                .put(
                    "secondaryAccentColor",
                    ThemePrefs.secondaryAccent?.name ?: JSONObject.NULL
                )
                .put("customAccentArgb", ThemePrefs.customAccentArgb)
                .put("customSecondaryArgb", ThemePrefs.customSecondaryArgb)
                // v2: the schedule configuration itself
                .put("checkpointTimes", AppSettings.checkpointTimes.joinToString(","))
                .put(
                    "checkpointEnabled",
                    AppSettings.checkpointEnabled.joinToString(",") { if (it) "1" else "0" }
                )
                .put("quietHoursEnabled", AppSettings.quietHoursEnabled)
                .put("quietStartMinutes", AppSettings.quietStartMinutes)
                .put("quietEndMinutes", AppSettings.quietEndMinutes)
                .put("notifyOnlyAtCheckpoints", AppSettings.notifyOnlyAtCheckpoints)
                .put("continueCurrentShow", AppSettings.continueCurrentShow)
                .put("notificationDoneButton", AppSettings.notificationDoneButton)
                .put("categoryRefreshButtons", AppSettings.categoryRefreshButtons)
                .put("libraryCompactList", AppSettings.libraryCompactList)
        )
        return root
    }

    private suspend fun applyJson(
        context: Context,
        repository: PodcastRepository,
        json: JSONObject
    ): Summary {
        // categories, in saved order
        val categories = json.optJSONArray("categories") ?: JSONArray()
        val names = buildList {
            for (i in 0 until categories.length()) {
                categories.optJSONObject(i)?.optString("name")
                    ?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        repository.importCategoriesOrdered(names)
        for (i in 0 until categories.length()) {
            val entry = categories.optJSONObject(i) ?: continue
            val hours = entry.optInt("refreshHours", 0)
            val anchor = entry.optInt("anchorMinutes", -1)
            if (hours > 0 || anchor >= 0) {
                repository.setCategoryRefreshHours(entry.optString("name"), hours, anchor)
            }
        }

        // podcasts. A feed that ALREADY exists locally keeps its local
        // settings — the old behavior half-merged (stub kept local retention
        // but skips/speed/categories were overwritten, and setListPrefs even
        // pruned episodes of shows that weren't part of the restore).
        // Existing shows only GAIN category memberships, additively.
        val podcasts = json.optJSONArray("podcasts") ?: JSONArray()
        val urlToId = HashMap<String, Long>()
        var feeds = 0
        for (i in 0 until podcasts.length()) {
            val entry = podcasts.optJSONObject(i) ?: continue
            val url = entry.stringOrNull("feedUrl") ?: continue
            val backupCategories = buildList {
                entry.stringOrNull("folder")?.let(::add)
                entry.optJSONArray("categories")?.let { cats ->
                    for (j in 0 until cats.length()) {
                        cats.optString(j)?.takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }.distinct()
            val existingId = repository.podcastIdForFeed(url)
            if (existingId != null) {
                backupCategories.forEach { repository.addToCategory(existingId, it) }
                urlToId[url] = existingId
                feeds++
                continue
            }
            val id = repository.importPodcastStub(
                feedUrl = url,
                title = entry.stringOrNull("title").orEmpty(),
                imageUrl = entry.stringOrNull("imageUrl"),
                folder = entry.stringOrNull("folder"),
                keepDownloads = entry.optInt("keepDownloads", AppSettings.defaultKeepDownloads),
                maxAgeDays = entry.optInt("maxAgeDays", 0)
            )
            repository.setSkips(
                id, entry.optInt("introSkipSec", 0), entry.optInt("outroSkipSec", 0)
            )
            repository.setListPrefs(
                id,
                entry.optInt("episodeCap", 0),
                entry.optBoolean("sortOldestFirst", false),
                entry.optBoolean("autoQueue", false)
            )
            val speed = entry.optDouble("playbackSpeed", 0.0).toFloat()
            if (speed > 0f) repository.setPlaybackSpeed(id, speed)
            if (entry.has("scheduleMode")) {
                repository.setScheduleRule(
                    id, entry.optInt("scheduleMode", 0), entry.optInt("scheduleParam", 0)
                )
            }
            val adJump = entry.optInt("adJumpSec", 0)
            if (adJump > 0) repository.setAdJump(id, adJump)
            if (backupCategories.isNotEmpty()) {
                repository.setCategories(id, backupCategories)
            }
            urlToId[url] = id
            feeds++
        }

        // SmartPlays
        val smartPlays = json.optJSONArray("smartPlays") ?: JSONArray()
        var imported = 0
        for (i in 0 until smartPlays.length()) {
            val plan = smartPlays.optJSONObject(i) ?: continue
            val entriesJson = plan.optJSONArray("entries") ?: JSONArray()
            val entries = buildList {
                for (j in 0 until entriesJson.length()) {
                    val entry = entriesJson.optJSONObject(j) ?: continue
                    val feedUrl = entry.stringOrNull("feedUrl")
                    val podcastId = feedUrl?.let { urlToId[it] }
                    // scoped to a feed that didn't import → drop the rule
                    if (feedUrl != null && podcastId == null) continue
                    add(
                        SmartPlayEntry(
                            smartPlayId = 0,
                            podcastId = podcastId,
                            folder = entry.stringOrNull("folder"),
                            maxTracks = entry.optInt("maxTracks", 5),
                            episodeSort = entry.optInt(
                                "episodeSort", SmartPlayEntry.SORT_OLDEST
                            ),
                            includePlayed = entry.optBoolean("includePlayed", false),
                            downloadedOnly = entry.optBoolean("downloadedOnly", false)
                        )
                    )
                }
            }
            if (entries.isNotEmpty()) {
                val id = repository.importSmartPlay(
                    plan.optString("name", "SmartPlay"), entries
                )
                if (plan.optBoolean("continuous", false)) {
                    repository.setSmartPlayContinuous(id, true)
                }
                imported++
            }
        }

        // settings — every key is has()-guarded: optX(key, default) can't
        // tell "absent" from "explicit default", so restoring an OLDER
        // backup used to factory-reset every setting the file predates
        json.optJSONObject("settings")?.let { s ->
            fun intIf(name: String, apply: (Int) -> Unit) {
                if (s.has(name)) apply(s.optInt(name))
            }
            fun boolIf(name: String, apply: (Boolean) -> Unit) {
                if (s.has(name)) apply(s.optBoolean(name))
            }
            intIf("defaultKeepDownloads") { AppSettings.setDefaultKeepDownloads(context, it) }
            intIf("seekBackSeconds") { AppSettings.setSeekBackSeconds(context, it) }
            intIf("seekForwardSeconds") { AppSettings.setSeekForwardSeconds(context, it) }
            boolIf("adChapterAutoSkip") { AppSettings.setAdChapterAutoSkip(context, it) }
            boolIf("newEpisodeNotifications") {
                AppSettings.setNewEpisodeNotifications(context, it)
            }
            if (s.has("defaultPlaybackSpeed")) {
                AppSettings.setDefaultPlaybackSpeed(
                    context, s.optDouble("defaultPlaybackSpeed", 1.0).toFloat()
                )
            }
            boolIf("wifiOnlyDownloads") { AppSettings.setWifiOnlyDownloads(context, it) }
            boolIf("streamWhenNotDownloaded") {
                AppSettings.setStreamWhenNotDownloaded(context, it)
            }
            boolIf("skipSilence") { AppSettings.setSkipSilence(context, it) }
            boolIf("swipeQueueToTop") { AppSettings.setSwipeQueueToTop(context, it) }
            boolIf("queueNextAtBottom") { AppSettings.setQueueNextAtBottom(context, it) }
            intIf("widgetOpacity") { AppSettings.setWidgetOpacity(context, it) }
            if (s.has("swipeRightAction")) {
                AppSettings.setSwipeRightAction(
                    context, s.optString("swipeRightAction", AppSettings.SWIPE_PLAYED)
                )
            }
            if (s.has("swipeLeftAction")) {
                AppSettings.setSwipeLeftAction(
                    context, s.optString("swipeLeftAction", AppSettings.SWIPE_QUEUE)
                )
            }
            // v2 schedule configuration
            if (s.has("checkpointTimes")) {
                s.optString("checkpointTimes").split(",")
                    .mapNotNull { it.trim().toIntOrNull() }
                    .forEachIndexed { idx, m -> AppSettings.setCheckpointTime(context, idx, m) }
            }
            if (s.has("checkpointEnabled")) {
                s.optString("checkpointEnabled").split(",")
                    .forEachIndexed { idx, flag ->
                        AppSettings.setCheckpointEnabled(context, idx, flag.trim() == "1")
                    }
            }
            boolIf("quietHoursEnabled") { AppSettings.setQuietHoursEnabled(context, it) }
            if (s.has("quietStartMinutes") && s.has("quietEndMinutes")) {
                AppSettings.setQuietHours(
                    context, s.optInt("quietStartMinutes"), s.optInt("quietEndMinutes")
                )
            }
            boolIf("notifyOnlyAtCheckpoints") {
                AppSettings.setNotifyOnlyAtCheckpoints(context, it)
            }
            boolIf("continueCurrentShow") { AppSettings.setContinueCurrentShow(context, it) }
            boolIf("notificationDoneButton") {
                AppSettings.setNotificationDoneButton(context, it)
            }
            boolIf("categoryRefreshButtons") {
                AppSettings.setCategoryRefreshButtons(context, it)
            }
            boolIf("libraryCompactList") { AppSettings.setLibraryCompactList(context, it) }
            runCatching {
                if (s.has("themeMode")) {
                    ThemePrefs.set(context, ThemeMode.valueOf(s.optString("themeMode")))
                }
            }
            runCatching {
                // custom ARGBs first — their setters force accent=CUSTOM, so
                // the saved accent choices must be applied after
                if (s.has("customAccentArgb")) {
                    ThemePrefs.setCustomAccent(context, s.getInt("customAccentArgb"))
                }
                if (s.has("customSecondaryArgb")) {
                    ThemePrefs.setCustomSecondaryAccent(
                        context, s.getInt("customSecondaryArgb")
                    )
                }
            }
            runCatching {
                if (s.has("accentColor")) {
                    ThemePrefs.setAccent(
                        context, AccentColor.valueOf(s.optString("accentColor"))
                    )
                }
            }
            runCatching {
                if (s.has("secondaryAccentColor")) {
                    ThemePrefs.setSecondaryAccent(
                        context,
                        s.stringOrNull("secondaryAccentColor")
                            ?.let { AccentColor.valueOf(it) }
                    )
                }
            }
        }

        return Summary(feeds, names.size, imported)
    }
}
