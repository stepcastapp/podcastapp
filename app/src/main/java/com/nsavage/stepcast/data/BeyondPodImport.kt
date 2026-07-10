package com.nsavage.stepcast.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Imports a BeyondPod backup (.bpbak) — a plain ZIP whose payload includes
 * `beyondpod.db.autobak`, a SQLite database holding the entire subscription
 * state. Behavior was reverse-engineered from the running app; no BeyondPod
 * code is used. What we retain:
 *
 *  - `categories` table — one text blob, `value^color|value^color|…`, in the
 *    user's display order → CategoryMeta rows
 *  - `feeds` table — url/name/imageUrl, `category` = `Primary|Secondary`
 *    values, per-feed download count and max track age → podcast stubs
 *    (a forced refresh afterwards pulls episodes)
 *  - `smartplaylist` table — ordered rules per playlist; feed scope is a
 *    feeds.feedId UUID, category scope is the category value; the
 *    `playbackType` column is the episode sort and uses the SAME constant
 *    values we do (0/1 name, 2 oldest, 3 newest, 4/5 duration, 100 shuffle)
 *  - `scheduled_tasks` rows running the "update category" operation
 *    (operationId ABC76FEC-0C02-4314-B8C4-4FEDE4C83C80) — recurrence is
 *    `recPeriod ms × recInterval` and `state` is the category → per-category
 *    refresh hours
 */
object BeyondPodImport {

    data class Summary(
        val feeds: Int,
        val categories: Int,
        val smartPlays: Int,
        val refreshRules: Int
    )

    private const val UPDATE_CATEGORY_OP = "abc76fec-0c02-4314-b8c4-4fede4c83c80"

    /** BeyondPod's built-in pseudo-categories; never imported as real ones. */
    private fun isSpecialCategory(name: String) =
        name.equals("Uncategorized", ignoreCase = true) ||
            name.equals("All feeds", ignoreCase = true)

    suspend fun import(context: Context, repository: PodcastRepository, uri: Uri): Summary =
        withContext(Dispatchers.IO) {
            val dbFile = extractDatabase(context, uri)
            try {
                readAndImport(repository, dbFile)
            } finally {
                dbFile.delete()
            }
        }

    /** Accepts either the .bpbak zip or a bare beyondpod.db SQLite file. */
    private fun extractDatabase(context: Context, uri: Uri): File {
        val workDir = File(context.cacheDir, "bpimport").apply { mkdirs() }
        val raw = File(workDir, "backup.bin")
        context.contentResolver.openInputStream(uri)?.use { input ->
            raw.outputStream().use { input.copyTo(it) }
        } ?: throw IllegalArgumentException("Couldn't open the selected file")

        val header = ByteArray(16)
        raw.inputStream().use { it.read(header) }
        if (header.size >= 6 && String(header, 0, 6) == "SQLite") {
            return raw
        }
        if (header[0] != 'P'.code.toByte() || header[1] != 'K'.code.toByte()) {
            raw.delete()
            throw IllegalArgumentException(
                "Not a BeyondPod backup (.bpbak) or BeyondPod database"
            )
        }
        val dbFile = File(workDir, "beyondpod.db")
        ZipInputStream(raw.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.contains("beyondpod.db")) {
                    dbFile.outputStream().use { zip.copyTo(it) }
                    raw.delete()
                    return dbFile
                }
                entry = zip.nextEntry
            }
        }
        raw.delete()
        throw IllegalArgumentException("This backup has no BeyondPod database inside")
    }

    private suspend fun readAndImport(repository: PodcastRepository, dbFile: File): Summary {
        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
        )
        db.use {
            // -- categories, in the user's order ---------------------------
            val categories = mutableListOf<String>()
            runCatching {
                db.rawQuery("SELECT categories FROM categories", null).use { c ->
                    while (c.moveToNext()) {
                        for (part in c.getString(0).orEmpty().split('|')) {
                            val name = part.substringBefore('^').trim()
                            if (name.isNotEmpty() && !isSpecialCategory(name) &&
                                categories.none { it.equals(name, ignoreCase = true) }
                            ) {
                                categories += name
                            }
                        }
                    }
                }
            }
            repository.importCategoriesOrdered(categories)

            // -- feeds ------------------------------------------------------
            val uuidToPodcastId = HashMap<String, Long>()
            var feeds = 0
            runCatching {
                db.rawQuery(
                    "SELECT feedId, name, url, imageUrl, category, " +
                        "maxDownload, maxTrackAge, maxTracks FROM feeds",
                    null
                ).use { c ->
                    while (c.moveToNext()) {
                        val url = c.getString(2).orEmpty()
                        if (!url.startsWith("http")) continue
                        val folder = c.getString(4).orEmpty()
                            .substringBefore('|').trim()
                            .takeIf { it.isNotEmpty() && !isSpecialCategory(it) }
                        val keep = c.getInt(5).takeIf { it in 1..50 }
                            ?: AppSettings.defaultKeepDownloads
                        val maxAge = c.getInt(6).takeIf { it in 1..365 } ?: 0
                        val id = repository.importPodcastStub(
                            feedUrl = url,
                            title = c.getString(1).orEmpty(),
                            imageUrl = c.getString(3),
                            folder = folder,
                            keepDownloads = keep,
                            maxAgeDays = maxAge
                        )
                        c.getInt(7).takeIf { it in 1..1000 }?.let { cap ->
                            repository.setListPrefs(id, cap, false, false)
                        }
                        c.getString(0)?.trim()?.takeIf { it.isNotEmpty() }?.let { uuid ->
                            uuidToPodcastId[uuid.lowercase()] = id
                        }
                        feeds++
                    }
                }
            }

            // -- SmartPlays -------------------------------------------------
            var smartPlays = 0
            runCatching {
                val byPlaylist = LinkedHashMap<Int, Pair<String?, MutableList<SmartPlayEntry>>>()
                db.rawQuery(
                    "SELECT playlistId, playlistName, feedId, categoryId, " +
                        "numEpisodes, playbackType FROM smartplaylist " +
                        "ORDER BY playlistId, sortOrder",
                    null
                ).use { c ->
                    while (c.moveToNext()) {
                        val playlistId = c.getInt(0)
                        val feedUuid = c.getString(2)?.trim().orEmpty()
                        val podcastId = if (feedUuid.isEmpty()) {
                            null
                        } else {
                            // a feed-scoped rule whose feed didn't import
                            // must be dropped, not silently widened to "all"
                            uuidToPodcastId[feedUuid.lowercase()] ?: continue
                        }
                        val folder = if (podcastId != null) {
                            null
                        } else {
                            c.getString(3)?.trim()
                                ?.takeIf { it.isNotEmpty() && !isSpecialCategory(it) }
                        }
                        val entry = SmartPlayEntry(
                            smartPlayId = 0,
                            podcastId = podcastId,
                            folder = folder,
                            maxTracks = c.getInt(4).coerceIn(0, 500),
                            episodeSort = when (val sort = c.getInt(5)) {
                                SmartPlayEntry.SORT_NAME_ASC,
                                SmartPlayEntry.SORT_NAME_DESC,
                                SmartPlayEntry.SORT_OLDEST,
                                SmartPlayEntry.SORT_NEWEST,
                                SmartPlayEntry.SORT_DURATION,
                                SmartPlayEntry.SORT_SHUFFLE -> sort
                                5 -> SmartPlayEntry.SORT_DURATION // longest≈duration
                                else -> SmartPlayEntry.SORT_OLDEST
                            }
                        )
                        val slot = byPlaylist.getOrPut(playlistId) {
                            null to mutableListOf()
                        }
                        val name = slot.first ?: c.getString(1)?.takeIf { it.isNotBlank() }
                        byPlaylist[playlistId] = name to slot.second.apply { add(entry) }
                    }
                }
                for ((_, value) in byPlaylist) {
                    val (name, entries) = value
                    if (entries.isNotEmpty()) {
                        repository.importSmartPlay(name ?: "SmartPlay", entries)
                        smartPlays++
                    }
                }
            }

            // -- per-category refresh cadence -------------------------------
            var refreshRules = 0
            runCatching {
                db.rawQuery(
                    "SELECT recPeriod, recInterval, operationId, state, active " +
                        "FROM scheduled_tasks",
                    null
                ).use { c ->
                    while (c.moveToNext()) {
                        if (c.getInt(4) == 0) continue
                        if (!c.getString(2).orEmpty().equals(UPDATE_CATEGORY_OP, true)) continue
                        val category = c.getString(3)?.trim().orEmpty()
                        if (category.isEmpty() || isSpecialCategory(category)) continue
                        val totalMs = c.getLong(0) * c.getInt(1)
                        val hours = (totalMs / 3_600_000L).toInt()
                        if (hours in 1..168) {
                            repository.importCategoriesOrdered(listOf(category))
                            repository.setCategoryRefreshHours(category, hours)
                            refreshRules++
                        }
                    }
                }
            }

            return Summary(feeds, categories.size, smartPlays, refreshRules)
        }
    }
}
