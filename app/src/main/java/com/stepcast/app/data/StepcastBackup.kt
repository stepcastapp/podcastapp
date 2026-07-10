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
        root.put("stepcast", 1)
        root.put("exportedAt", System.currentTimeMillis())

        val categories = JSONArray()
        for (meta in repository.categoryMetaList()) {
            categories.put(
                JSONObject()
                    .put("name", meta.name)
                    .put("sortOrder", meta.sortOrder)
                    .put("refreshHours", meta.refreshHours)
                    .put("anchorMinutes", meta.anchorMinutes)
            )
        }
        root.put("categories", categories)

        val podcastsById = HashMap<Long, Podcast>()
        val podcasts = JSONArray()
        for (podcast in repository.allPodcasts()) {
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
                JSONObject().put("name", smartPlay.name).put("entries", entries)
            )
        }
        root.put("smartPlays", smartPlays)

        root.put(
            "settings",
            JSONObject()
                .put("defaultRefreshHours", AppSettings.defaultRefreshHours)
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

        // podcasts
        val podcasts = json.optJSONArray("podcasts") ?: JSONArray()
        val urlToId = HashMap<String, Long>()
        var feeds = 0
        for (i in 0 until podcasts.length()) {
            val entry = podcasts.optJSONObject(i) ?: continue
            val url = entry.stringOrNull("feedUrl") ?: continue
            val id = repository.importPodcastStub(
                feedUrl = url,
                title = entry.optString("title"),
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
            // multi-category backups carry the full list; legacy files
            // only had "folder", which importPodcastStub already applied
            entry.optJSONArray("categories")?.let { cats ->
                val names = buildList {
                    for (j in 0 until cats.length()) {
                        cats.optString(j)?.takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
                if (names.isNotEmpty()) repository.setCategories(id, names)
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
                repository.importSmartPlay(plan.optString("name", "SmartPlay"), entries)
                imported++
            }
        }

        // settings
        json.optJSONObject("settings")?.let { s ->
            AppSettings.setDefaultRefreshHours(context, s.optInt("defaultRefreshHours", 3))
            AppSettings.setDefaultKeepDownloads(context, s.optInt("defaultKeepDownloads", 2))
            AppSettings.setSeekBackSeconds(context, s.optInt("seekBackSeconds", 10))
            AppSettings.setSeekForwardSeconds(context, s.optInt("seekForwardSeconds", 30))
            AppSettings.setAdChapterAutoSkip(context, s.optBoolean("adChapterAutoSkip", true))
            AppSettings.setNewEpisodeNotifications(
                context, s.optBoolean("newEpisodeNotifications", true)
            )
            AppSettings.setDefaultPlaybackSpeed(
                context, s.optDouble("defaultPlaybackSpeed", 1.0).toFloat()
            )
            AppSettings.setWifiOnlyDownloads(context, s.optBoolean("wifiOnlyDownloads", false))
            AppSettings.setStreamWhenNotDownloaded(
                context, s.optBoolean("streamWhenNotDownloaded", true)
            )
            AppSettings.setSkipSilence(context, s.optBoolean("skipSilence", false))
            AppSettings.setSwipeQueueToTop(context, s.optBoolean("swipeQueueToTop", false))
            AppSettings.setQueueNextAtBottom(context, s.optBoolean("queueNextAtBottom", false))
            AppSettings.setWidgetOpacity(context, s.optInt("widgetOpacity", 100))
            AppSettings.setSwipeRightAction(
                context, s.optString("swipeRightAction", AppSettings.SWIPE_PLAYED)
            )
            AppSettings.setSwipeLeftAction(
                context, s.optString("swipeLeftAction", AppSettings.SWIPE_QUEUE)
            )
            runCatching {
                ThemePrefs.set(context, ThemeMode.valueOf(s.optString("themeMode", "SYSTEM")))
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
                ThemePrefs.setAccent(
                    context, AccentColor.valueOf(s.optString("accentColor", "CYAN"))
                )
            }
            runCatching {
                ThemePrefs.setSecondaryAccent(
                    context,
                    s.stringOrNull("secondaryAccentColor")
                        ?.let { AccentColor.valueOf(it) }
                )
            }
        }

        return Summary(feeds, names.size, imported)
    }
}
