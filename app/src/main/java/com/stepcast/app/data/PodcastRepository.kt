package com.stepcast.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.stepcast.app.download.DownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class PodcastRepository(
    private val db: StepcastDatabase,
    private val appContext: Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    val podcasts get() = db.podcastDao().observeAll()

    fun episodesFor(podcastId: Long) = db.episodeDao().observeForPodcast(podcastId)

    fun observePodcast(podcastId: Long) = db.podcastDao().observe(podcastId)

    suspend fun podcast(podcastId: Long) = db.podcastDao().get(podcastId)

    suspend fun episode(id: Long) = db.episodeDao().get(id)

    suspend fun episodesNewestFirst(podcastId: Long, limit: Int = 100): List<Episode> =
        db.episodeDao().listForPodcast(podcastId)
            .sortedByDescending { it.pubDateMs }
            .take(limit)

    /** Subscribes to a feed URL (or refreshes if already subscribed). Returns the podcast id. */
    suspend fun subscribe(
        feedUrl: String,
        prefetched: ParsedFeed? = null,
        // Bulk imports (OPML) pass true so the whole back-catalog isn't
        // auto-downloaded at once; only later-arriving episodes will be.
        suppressBacklogAutoDownload: Boolean = false
    ): Long = withContext(Dispatchers.IO) {
        // normalized match so an equivalent URL refreshes instead of duplicating
        val existingId = podcastIdForFeed(feedUrl)
        if (existingId != null) {
            refresh(existingId)
            return@withContext existingId
        }
        val feed = prefetched ?: fetchFeed(feedUrl)
        val id = db.podcastDao().insert(
            Podcast(
                feedUrl = feedUrl,
                title = feed.title,
                description = feed.description,
                imageUrl = feed.imageUrl,
                author = feed.author,
                keepDownloads = AppSettings.defaultKeepDownloads,
                lastRefreshed = System.currentTimeMillis()
            )
        )
        insertEpisodes(id, feed)
        if (suppressBacklogAutoDownload) {
            db.episodeDao().setAutoDownloadEligibleForPodcast(id, false)
        } else {
            autoManageDownloads(id)
        }
        id
    }

    /** Re-fetches the feed (or rescans a virtual feed's folder); returns how many new episodes appeared. */
    suspend fun refresh(podcastId: Long): Int = withContext(Dispatchers.IO) {
        val podcast = db.podcastDao().get(podcastId) ?: return@withContext 0
        if (podcast.localFolderUri != null) {
            return@withContext scanLocalFolder(podcast)
        }
        // A never-refreshed feed is a freshly-imported stub (BeyondPod import
        // creates stubs with lastRefreshed = 0). Treat its whole back-catalog
        // as import backlog: suppress auto-download so the import doesn't
        // mass-download history — later refreshes still auto-download new ones.
        val isInitialImport = podcast.lastRefreshed == 0L
        val feed = fetchFeed(podcast.feedUrl)
        val newIds = insertEpisodesReturningIds(podcastId, feed)
        db.podcastDao().update(
            podcast.copy(
                title = feed.title,
                description = feed.description,
                imageUrl = feed.imageUrl ?: podcast.imageUrl,
                author = feed.author.ifEmpty { podcast.author },
                lastRefreshed = System.currentTimeMillis(),
                consecutiveFailures = 0
            )
        )
        if (podcast.autoQueue) newIds.forEach { addToQueueLast(it) }
        if (podcast.episodeCap > 0) {
            db.episodeDao().pruneBeyondCap(podcastId, podcast.episodeCap)
        }
        if (isInitialImport) {
            db.episodeDao().setAutoDownloadEligibleForPodcast(podcastId, false)
        } else {
            autoManageDownloads(podcastId)
        }
        newIds.size
    }

    /**
     * Feed download housekeeping. When auto-download is on (keepDownloads >
     * 0): fetch the newest N unplayed episodes and drop files for played
     * ones. Independently, the max-age rule deletes downloads whose episode
     * is older than the cutoff. Manual downloads of unplayed episodes are
     * never pruned by the count rule.
     */
    private suspend fun autoManageDownloads(podcastId: Long) {
        val podcast = db.podcastDao().get(podcastId) ?: return
        if (podcast.localFolderUri != null) return
        val episodes = db.episodeDao().listForPodcast(podcastId)
            .sortedByDescending { it.pubDateMs }
        val cutoffMs = if (podcast.maxAgeDays > 0) {
            System.currentTimeMillis() - podcast.maxAgeDays * 86_400_000L
        } else {
            0L
        }
        if (podcast.keepDownloads > 0) {
            episodes.asSequence()
                .filter { !it.played }
                // import backlog stays out of auto-download entirely (before
                // take(), so it never consumes a "newest N" slot)
                .filter { it.autoDownloadEligible }
                .filter { cutoffMs == 0L || it.pubDateMs >= cutoffMs }
                .take(podcast.keepDownloads)
                .filter { it.downloadStatus == Episode.DOWNLOAD_NONE }
                // dead enclosures must not reappear on every refresh —
                // after enough terminal failures only a manual retry works
                .filter { it.downloadAttempts < Episode.MAX_AUTO_DOWNLOAD_ATTEMPTS }
                .forEach { DownloadWorker.start(appContext, it.id) }
            episodes.filter { it.isDownloaded && it.played }
                .forEach { deleteDownload(it.id) }
        }
        if (cutoffMs > 0) {
            episodes.filter { it.isDownloaded && it.pubDateMs in 1 until cutoffMs }
                .forEach { deleteDownload(it.id) }
        }
    }

    /** Episode-list prefs: cap, oldest-first ordering, auto-queue. */
    suspend fun setListPrefs(
        podcastId: Long,
        episodeCap: Int,
        sortOldestFirst: Boolean,
        autoQueue: Boolean
    ) {
        db.podcastDao().updateListPrefs(
            podcastId, episodeCap.coerceIn(0, 5000), sortOldestFirst, autoQueue
        )
        if (episodeCap > 0) db.episodeDao().pruneBeyondCap(podcastId, episodeCap)
    }

    suspend fun recordRefreshFailure(podcastId: Long) =
        db.podcastDao().incrementFailures(podcastId)

    fun episodesForPaged(podcastId: Long, oldestFirst: Boolean, limit: Int) =
        db.episodeDao().observeForPodcastPaged(podcastId, if (oldestFirst) 1 else 0, limit)

    /** True total/unplayed counts, independent of the paged list. */
    fun episodeCounts(podcastId: Long) = db.episodeDao().observeCounts(podcastId)

    /** Bulk cleanup: everything older than [days] becomes played. */
    suspend fun markPlayedOlderThan(podcastId: Long, days: Int) {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        db.episodeDao().markPlayedOlderThan(podcastId, cutoff, System.currentTimeMillis())
        db.queueDao().removePlayed()
    }

    suspend fun markPlayedOlderThanInCategory(category: String, days: Int) {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        db.episodeDao().markPlayedOlderThanInFolder(
            category, cutoff, System.currentTimeMillis()
        )
        db.queueDao().removePlayed()
    }

    /** Per-podcast listening time accumulation (see ListenStats). */
    suspend fun addPodcastListening(podcastId: Long, wallMs: Long, contentMs: Long) {
        if (db.listenStatDao().bump(podcastId, wallMs, contentMs) == 0) {
            db.listenStatDao().insert(ListenStat(podcastId, wallMs, contentMs))
        }
    }

    suspend fun topListenStats(limit: Int = 8): List<Pair<Podcast, ListenStat>> =
        db.listenStatDao().top(limit).mapNotNull { stat ->
            db.podcastDao().get(stat.podcastId)?.let { it to stat }
        }

    suspend fun clearListenStats() = db.listenStatDao().clear()

    suspend fun setRetention(podcastId: Long, keepDownloads: Int, maxAgeDays: Int) {
        db.podcastDao().updateRetention(
            podcastId,
            keepDownloads.coerceIn(0, 50),
            maxAgeDays.coerceIn(0, 3650)
        )
        autoManageDownloads(podcastId)
    }

    suspend fun allPodcasts(): List<Podcast> = db.podcastDao().listAll()

    /** Refreshes every podcast in one category; returns new-episode count. */
    suspend fun refreshCategory(category: String): Int = coroutineScope {
        val gate = Semaphore(6)
        val memberIds = db.podcastCategoryDao().memberIds(category).toHashSet()
        db.podcastDao().listAll()
            .filter { it.id in memberIds }
            .map { podcast ->
                async {
                    gate.withPermit {
                        runCatching { refresh(podcast.id) }.getOrElse {
                            recordRefreshFailure(podcast.id)
                            0
                        }
                    }
                }
            }
            .awaitAll()
            .sum()
    }

    /** Subscribes to many feeds concurrently (bounded); returns successes. */
    suspend fun subscribeAll(feedUrls: List<String>): Int = coroutineScope {
        val gate = Semaphore(6)
        feedUrls
            .map { url ->
                async {
                    gate.withPermit {
                        runCatching {
                            subscribe(url, suppressBacklogAutoDownload = true)
                        }.isSuccess
                    }
                }
            }
            .awaitAll()
            .count { it }
    }

    suspend fun unsubscribe(podcastId: Long) = withContext(Dispatchers.IO) {
        db.episodeDao().listForPodcast(podcastId).forEach { episode ->
            episode.localFilePath?.let { runCatching { File(it).delete() } }
        }
        db.queueDao().removeForPodcast(podcastId)
        db.episodeDao().deleteForPodcast(podcastId)
        db.podcastCategoryDao().removeAllFor(podcastId)
        db.podcastDao().delete(podcastId)
    }

    suspend fun setAdJump(podcastId: Long, sec: Int) {
        db.podcastDao().updateAdJump(podcastId, sec.coerceIn(0, 600))
    }

    /** The current show's ad-jump length for a playing episode; 0 = none. */
    suspend fun adJumpSecFor(episodeId: Long): Int {
        val episode = db.episodeDao().get(episodeId) ?: return 0
        return db.podcastDao().get(episode.podcastId)?.adJumpSec ?: 0
    }

    suspend fun setSkips(podcastId: Long, introSec: Int, outroSec: Int) {
        db.podcastDao().updateSkips(podcastId, introSec.coerceAtLeast(0), outroSec.coerceAtLeast(0))
    }

    suspend fun setPlaybackSpeed(podcastId: Long, speed: Float) {
        db.podcastDao().updatePlaybackSpeed(podcastId, speed.coerceIn(0f, 4f))
    }

    /** Per-podcast playback speed for the episode's feed; 0 = no override. */
    suspend fun speedFor(episodeId: Long): Float {
        val episode = db.episodeDao().get(episodeId) ?: return 0f
        return db.podcastDao().get(episode.podcastId)?.playbackSpeed ?: 0f
    }

    // ---- virtual feeds (local folder as a feed) ---------------------------

    /**
     * Subscribes to a local folder as a virtual feed. The caller must have
     * taken a persistable read permission on the tree URI. Returns the
     * podcast id (existing one if this folder is already subscribed).
     */
    suspend fun addLocalFolder(treeUri: Uri): Long = withContext(Dispatchers.IO) {
        val key = treeUri.toString()
        db.podcastDao().getByFeedUrl(key)?.let { existing ->
            scanLocalFolder(existing)
            return@withContext existing.id
        }
        val root = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: throw IOException("Cannot open folder")
        val id = db.podcastDao().insert(
            Podcast(
                feedUrl = key,
                title = root.name ?: "Local folder",
                author = "Local folder",
                localFolderUri = key,
                lastRefreshed = System.currentTimeMillis()
            )
        )
        db.podcastDao().get(id)?.let { scanLocalFolder(it) }
        id
    }

    /**
     * Rescans a virtual feed's folder. Audio files (recursively, subfolders
     * become a "Sub/Folder — " title prefix) map to episodes keyed by their
     * document URI, so play state and position survive rescans; files that
     * vanished are pruned. Returns the number of new episodes.
     */
    private suspend fun scanLocalFolder(podcast: Podcast): Int {
        val treeUri = podcast.localFolderUri ?: return 0
        val root = DocumentFile.fromTreeUri(appContext, Uri.parse(treeUri)) ?: return 0
        val found = mutableListOf<Episode>()

        fun scan(dir: DocumentFile, prefix: String, depth: Int) {
            if (depth > 5) return
            for (file in dir.listFiles()) {
                val name = file.name ?: continue
                if (name.startsWith(".")) continue
                if (file.isDirectory) {
                    scan(file, if (prefix.isEmpty()) name else "$prefix/$name", depth + 1)
                } else if (file.isFile && isAudioFile(name, file.type)) {
                    val title = name.substringBeforeLast('.')
                    found += Episode(
                        podcastId = podcast.id,
                        guid = file.uri.toString(),
                        title = if (prefix.isEmpty()) title else "$prefix — $title",
                        audioUrl = file.uri.toString(),
                        pubDateMs = file.lastModified()
                    )
                }
            }
        }
        scan(root, "", 0)

        val insertedIds = db.episodeDao().insertAll(found)
        val added = insertedIds.count { it != -1L }

        // One metadata pass per file that still needs something: duration
        // for new rows, embedded artwork for ANY episode without art (this
        // backfills libraries scanned before art support existed). Files
        // genuinely lacking an embedded picture get re-probed on rescans —
        // local IO on a handful of files, accepted over tracking state.
        val artDir = java.io.File(appContext.filesDir, "local_art")
            .apply { mkdirs() }
        var folderArt: String? = podcast.imageUrl
        for (ep in db.episodeDao().listForPodcast(podcast.id)) {
            if (folderArt == null && ep.imageUrl != null) folderArt = ep.imageUrl
            val wantDuration = ep.durationMs <= 0
            val wantArt = ep.imageUrl == null
            if (!wantDuration && !wantArt) continue
            runCatching {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(appContext, Uri.parse(ep.audioUrl))
                    if (wantDuration) {
                        retriever.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                        )?.toLongOrNull()?.takeIf { it > 0 }?.let { durationMs ->
                            db.episodeDao().updateDurationIfUnknown(ep.id, durationMs)
                        }
                    }
                    if (wantArt) {
                        retriever.embeddedPicture?.let { bytes ->
                            val artFile = java.io.File(artDir, "${ep.id}.jpg")
                            artFile.writeBytes(bytes)
                            val artUri = Uri.fromFile(artFile).toString()
                            db.episodeDao().updateImageUrlIfMissing(ep.id, artUri)
                            if (folderArt == null) folderArt = artUri
                        }
                    }
                } finally {
                    // release(), not use{}: AutoCloseable only arrived at API 29
                    retriever.release()
                }
            }
        }

        // prune episodes whose backing file is gone (and their cached art)
        val validGuids = found.mapTo(HashSet()) { it.guid }
        for (episode in db.episodeDao().listForPodcast(podcast.id)) {
            if (episode.guid !in validGuids) {
                db.queueDao().remove(episode.id)
                db.episodeDao().deleteById(episode.id)
                java.io.File(artDir, "${episode.id}.jpg").delete()
            }
        }
        db.podcastDao().update(
            podcast.copy(
                lastRefreshed = System.currentTimeMillis(),
                // the folder itself takes the first embedded art found
                imageUrl = podcast.imageUrl ?: folderArt
            )
        )
        return added
    }

    private fun isAudioFile(name: String, mimeType: String?): Boolean {
        if (mimeType?.startsWith("audio/") == true) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "wav", "wma")
    }

    // ---- folders (categories) ---------------------------------------------
    // Memberships live in the podcast_categories junction table — a podcast
    // can be in ANY number of categories. Podcast.folder is kept synced to
    // the first membership as the legacy/primary value.

    val podcastCategories get() = db.podcastCategoryDao().observeAll()

    suspend fun categoriesFor(podcastId: Long): List<String> =
        db.podcastCategoryDao().categoriesFor(podcastId)

    suspend fun categoryMemberIds(category: String): List<Long> =
        db.podcastCategoryDao().memberIds(category)

    suspend fun podcastCategoryList(): List<PodcastCategory> =
        db.podcastCategoryDao().listAll()

    private suspend fun syncPrimaryFolder(podcastId: Long) {
        db.podcastDao().updateFolder(
            podcastId,
            db.podcastCategoryDao().categoriesFor(podcastId).firstOrNull()
        )
    }

    /** Replaces every membership with [categories] (empty = uncategorized). */
    suspend fun setCategories(podcastId: Long, categories: List<String>) {
        db.podcastCategoryDao().removeAllFor(podcastId)
        categories.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach {
            ensureCategoryMeta(it)
            db.podcastCategoryDao().add(PodcastCategory(podcastId, it))
        }
        syncPrimaryFolder(podcastId)
    }

    suspend fun addToCategory(podcastId: Long, category: String) {
        val clean = category.trim()
        if (clean.isEmpty()) return
        ensureCategoryMeta(clean)
        db.podcastCategoryDao().add(PodcastCategory(podcastId, clean))
        syncPrimaryFolder(podcastId)
    }

    suspend fun removeFromCategory(podcastId: Long, category: String) {
        db.podcastCategoryDao().remove(podcastId, category)
        syncPrimaryFolder(podcastId)
    }

    /** Single-category replace — imports and legacy call sites. */
    suspend fun setSingleCategory(podcastId: Long, category: String?) {
        setCategories(podcastId, listOfNotNull(category?.trim()?.ifEmpty { null }))
    }

    /** Renames the category everywhere: memberships, SmartPlay rules, meta. */
    suspend fun renameCategory(oldName: String, newName: String) {
        val clean = newName.trim()
        if (clean.isEmpty() || clean == oldName) return
        db.podcastCategoryDao().renameCategory(oldName, clean)
        db.podcastDao().renameFolder(oldName, clean)
        db.smartPlayDao().renameEntryFolder(oldName, clean)
        val old = db.categoryDao().get(oldName)
        db.categoryDao().delete(oldName)
        db.categoryDao().upsert(
            CategoryMeta(
                name = clean,
                sortOrder = old?.sortOrder ?: ((db.categoryDao().maxSort() ?: -1) + 1),
                refreshHours = old?.refreshHours ?: 0,
                anchorMinutes = old?.anchorMinutes ?: -1
            )
        )
    }

    /**
     * Removes the category: its podcasts lose that membership (keeping any
     * others), and SmartPlay rules scoped to it are deleted (matching
     * BeyondPod's category-delete behavior).
     */
    suspend fun deleteCategory(name: String) {
        val members = db.podcastCategoryDao().memberIds(name)
        db.podcastCategoryDao().deleteCategory(name)
        db.podcastDao().clearFolder(name)
        members.forEach { syncPrimaryFolder(it) }
        db.smartPlayDao().deleteEntriesForFolder(name)
        db.categoryDao().delete(name)
    }

    // ---- category meta (manual order + refresh cadence) --------------------

    val categoryMetas get() = db.categoryDao().observeAll()

    private suspend fun ensureCategoryMeta(name: String) {
        if (db.categoryDao().get(name) == null) {
            db.categoryDao().upsert(
                CategoryMeta(name = name, sortOrder = (db.categoryDao().maxSort() ?: -1) + 1)
            )
        }
    }

    /** Backfills meta rows for categories created before metas existed. */
    suspend fun ensureCategoryMetas() {
        db.podcastCategoryDao().names().forEach { ensureCategoryMeta(it) }
        // pre-junction installs: a folder value may exist with no membership
        db.podcastDao().listAll().forEach { podcast ->
            val folder = podcast.folder?.takeIf(String::isNotEmpty) ?: return@forEach
            ensureCategoryMeta(folder)
            db.podcastCategoryDao().add(PodcastCategory(podcast.id, folder))
        }
    }

    suspend fun moveCategory(name: String, up: Boolean) {
        val metas = db.categoryDao().listAll()
        val index = metas.indexOfFirst { it.name == name }
        if (index < 0) return
        val other = if (up) index - 1 else index + 1
        if (other !in metas.indices) return
        val reordered = metas.toMutableList()
        val tmp = reordered[index]
        reordered[index] = reordered[other]
        reordered[other] = tmp
        reordered.forEachIndexed { i, meta -> db.categoryDao().setSort(meta.name, i) }
    }

    suspend fun setCategoryRefreshHours(name: String, hours: Int, anchorMinutes: Int = -1) {
        ensureCategoryMeta(name)
        val meta = db.categoryDao().get(name) ?: return
        db.categoryDao().upsert(
            meta.copy(
                refreshHours = hours.coerceIn(0, 168),
                anchorMinutes = anchorMinutes.coerceIn(-1, 24 * 60 - 1)
            )
        )
    }

    suspend fun categoryMetaList(): List<CategoryMeta> = db.categoryDao().listAll()

    /** Merged, newest-first episode list across every podcast in the folder. */
    fun episodesForCategory(category: String) = db.episodeDao().observeForFolder(category)

    // ---- SmartPlays -------------------------------------------------------

    val smartPlays get() = db.smartPlayDao().observeAll()

    /** Nudge a SmartPlay one slot up/down in the Up Next strip. */
    suspend fun moveSmartPlay(id: Long, up: Boolean) {
        val all = db.smartPlayDao().listAll()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) return
        val other = if (up) index - 1 else index + 1
        if (other !in all.indices) return
        val reordered = all.toMutableList()
        val tmp = reordered[index]
        reordered[index] = reordered[other]
        reordered[other] = tmp
        reordered.forEachIndexed { i, sp -> db.smartPlayDao().setSort(sp.id, i) }
    }

    fun observeSmartPlay(id: Long) = db.smartPlayDao().observe(id)

    fun observeSmartPlayEntries(smartPlayId: Long) =
        db.smartPlayDao().observeEntries(smartPlayId)

    /** Creates a SmartPlay with one default rule; returns its id. */
    suspend fun createSmartPlay(name: String): Long {
        // new ones join the END of the strip, not the front
        val nextOrder = (db.smartPlayDao().listAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
        val id = db.smartPlayDao().upsert(SmartPlay(name = name, sortOrder = nextOrder))
        db.smartPlayDao().upsertEntry(SmartPlayEntry(smartPlayId = id, sortOrder = 0))
        return id
    }

    suspend fun renameSmartPlay(id: Long, name: String) =
        db.smartPlayDao().rename(id, name.trim().ifEmpty { "SmartPlay" })

    suspend fun deleteSmartPlay(id: Long) {
        db.smartPlayDao().deleteEntriesFor(id)
        db.smartPlayDao().delete(id)
    }

    suspend fun saveSmartPlayEntry(entry: SmartPlayEntry) {
        val sortOrder = if (entry.id == 0L) {
            (db.smartPlayDao().maxEntrySort(entry.smartPlayId) ?: -1) + 1
        } else {
            entry.sortOrder
        }
        db.smartPlayDao().upsertEntry(entry.copy(sortOrder = sortOrder))
    }

    suspend fun deleteSmartPlayEntry(entryId: Long) = db.smartPlayDao().deleteEntry(entryId)

    /** Swaps the entry with its neighbor above/below, then renumbers 0..n. */
    suspend fun moveSmartPlayEntry(smartPlayId: Long, entryId: Long, up: Boolean) {
        val entries = db.smartPlayDao().entriesFor(smartPlayId)
        val index = entries.indexOfFirst { it.id == entryId }
        if (index < 0) return
        val other = if (up) index - 1 else index + 1
        if (other !in entries.indices) return
        val reordered = entries.toMutableList()
        val tmp = reordered[index]
        reordered[index] = reordered[other]
        reordered[other] = tmp
        reordered.forEachIndexed { i, entry -> db.smartPlayDao().setEntrySort(entry.id, i) }
    }

    /**
     * Resolves a SmartPlay to its episode list: rules run in order, each
     * appending its matches (sorted its own way, capped at maxTracks,
     * deduped against everything already picked).
     */
    suspend fun episodesFor(smartPlay: SmartPlay): List<Episode> {
        val result = mutableListOf<Episode>()
        val seen = HashSet<Long>()
        for (entry in db.smartPlayDao().entriesFor(smartPlay.id)) {
            val candidates = db.episodeDao().selectSmartPlayCandidates(
                folder = entry.folder,
                podcastId = entry.podcastId,
                includePlayed = if (entry.includePlayed) 1 else 0,
                downloadedOnly = if (entry.downloadedOnly) 1 else 0
            ).filter { it.id !in seen }
            val sorted = when (entry.episodeSort) {
                SmartPlayEntry.SORT_NAME_ASC -> candidates.sortedBy { it.title.lowercase() }
                SmartPlayEntry.SORT_NAME_DESC ->
                    candidates.sortedByDescending { it.title.lowercase() }
                SmartPlayEntry.SORT_OLDEST -> candidates.sortedBy { it.pubDateMs }
                SmartPlayEntry.SORT_NEWEST -> candidates.sortedByDescending { it.pubDateMs }
                SmartPlayEntry.SORT_DURATION ->
                    candidates.sortedBy { if (it.durationMs > 0) it.durationMs else Long.MAX_VALUE }
                SmartPlayEntry.SORT_SHUFFLE -> candidates.shuffled()
                else -> candidates
            }
            val picked = if (entry.maxTracks > 0) sorted.take(entry.maxTracks) else sorted
            picked.forEach { seen += it.id }
            result += picked
        }
        return result
    }

    /** Replaces the whole up-next queue with the given episodes, in order. */
    suspend fun replaceQueue(episodeIds: List<Long>) {
        // one transaction, one Flow emission — clear + inserts as separate
        // commits let observers see an EMPTY queue mid-swap, which flashed
        // the whole Up Next screen to its empty state after a drag-drop
        db.withTransaction {
            db.queueDao().clear()
            episodeIds.forEachIndexed { index, id ->
                db.queueDao().insert(QueueItem(id, index))
            }
        }
    }

    // ---- queue / up-next -------------------------------------------------

    val queue get() = db.queueDao().observeQueue()

    /** Running + failed downloads, for the download-activity dialog. */
    val downloadActivity get() = db.episodeDao().observeDownloadActivity()

    suspend fun downloadingIds(): List<Long> = db.episodeDao().downloadingIds()

    /**
     * The episode auto-continue should play when the queue runs dry: the
     * same show's next unplayed episode, honoring its sort preference.
     */
    suspend fun nextUnplayedAfter(episodeId: Long): Episode? {
        val episode = db.episodeDao().get(episodeId) ?: return null
        val podcast = db.podcastDao().get(episode.podcastId) ?: return null
        return db.episodeDao().nextUnplayedInPodcast(
            podcastId = podcast.id,
            excludeId = episodeId,
            oldestFirst = if (podcast.sortOldestFirst) 1 else 0
        )
    }

    /** Library-wide search: subscribed shows by title + episodes by title. */
    suspend fun searchLibrary(query: String): Pair<List<Podcast>, List<Episode>> {
        val q = query.trim()
        if (q.length < 2) return emptyList<Podcast>() to emptyList()
        val shows = allPodcasts().filter { it.title.contains(q, ignoreCase = true) }
        val episodes = db.episodeDao().searchByTitle(q)
        return shows to episodes
    }

    suspend fun queueSnapshot(): List<Episode> = db.queueDao().queueSnapshot()

    /** Appends the episode to the end of the up-next queue. */
    suspend fun addToQueueLast(episodeId: Long) {
        db.queueDao().insert(QueueItem(episodeId, (db.queueDao().maxPosition() ?: 0) + 1))
    }

    /** Puts the episode at the front of the up-next queue. */
    suspend fun addToQueueNext(episodeId: Long) {
        db.queueDao().insert(QueueItem(episodeId, (db.queueDao().minPosition() ?: 0) - 1))
    }

    /** Swipe-to-queue lands where the user configured: front or end. */
    suspend fun addToQueueBySwipe(episodeId: Long) {
        if (AppSettings.swipeQueueToTop) addToQueueNext(episodeId) else addToQueueLast(episodeId)
    }

    // ---- BeyondPod import --------------------------------------------------

    /** Appends any not-yet-known categories, keeping the given order. */
    suspend fun importCategoriesOrdered(names: List<String>) {
        for (name in names) {
            val clean = name.trim()
            if (clean.isNotEmpty()) ensureCategoryMeta(clean)
        }
    }

    /**
     * Inserts a podcast WITHOUT fetching its feed — a forced refresh
     * afterwards fills in title corrections, artwork, and episodes.
     * Returns the podcast id, whether new or already subscribed.
     */
    suspend fun importPodcastStub(
        feedUrl: String,
        title: String,
        imageUrl: String?,
        folder: String?,
        keepDownloads: Int,
        maxAgeDays: Int
    ): Long {
        val cleanFolder = folder?.trim()?.takeIf { it.isNotEmpty() }
        db.podcastDao().getByFeedUrl(feedUrl)?.let { existing ->
            // additive, never clobbers: a feed under two OPML outlines (or
            // re-imported with a category) just gains the membership
            if (cleanFolder != null) addToCategory(existing.id, cleanFolder)
            return existing.id
        }
        val id = db.podcastDao().insert(
            Podcast(
                feedUrl = feedUrl,
                title = title.ifBlank { feedUrl },
                imageUrl = imageUrl,
                folder = cleanFolder,
                keepDownloads = keepDownloads,
                maxAgeDays = maxAgeDays,
                lastRefreshed = 0
            )
        )
        cleanFolder?.let { addToCategory(id, it) }
        return id
    }

    suspend fun smartPlayList(): List<SmartPlay> = db.smartPlayDao().listAll()

    suspend fun episodeCount(): Int = db.episodeDao().countAll()

    /**
     * One-time healing for data poisoned by the optString-returns-"null"
     * restore bug: literal "null" folders on podcasts and SmartPlay rules
     * (which made every restored rule match nothing), "null" image URLs,
     * and the phantom "null" category. Idempotent and cheap.
     */
    suspend fun repairLegacyNullStrings() {
        db.podcastDao().repairNullFolders()
        db.podcastDao().repairNullImageUrls()
        db.smartPlayDao().repairNullFolders()
        db.categoryDao().delete("null")
    }

    /** How many episodes one SmartPlay rule matches right now. */
    suspend fun countSmartPlayMatches(entry: SmartPlayEntry): Int =
        db.episodeDao().selectSmartPlayCandidates(
            folder = entry.folder,
            podcastId = entry.podcastId,
            includePlayed = if (entry.includePlayed) 1 else 0,
            downloadedOnly = if (entry.downloadedOnly) 1 else 0
        ).size

    /**
     * Why does a rule match nothing? Reports what the rule's scope really
     * contains so a broken pointer is visible instead of mysterious.
     */
    suspend fun explainSmartPlayEntry(entry: SmartPlayEntry): String {
        entry.podcastId?.let { id ->
            val episodes = db.episodeDao().listForPodcast(id)
            val unplayed = episodes.count { !it.played }
            return "target feed has ${episodes.size} episodes, $unplayed unplayed"
        }
        entry.folder?.let { folder ->
            val members = db.podcastCategoryDao().memberCount(folder)
            return "category has $members podcasts"
        }
        return "scope: everything"
    }

    suspend fun smartPlayEntryList(smartPlayId: Long): List<SmartPlayEntry> =
        db.smartPlayDao().entriesFor(smartPlayId)

    /** Creates a SmartPlay with the given ordered rule entries. */
    suspend fun importSmartPlay(name: String, entries: List<SmartPlayEntry>): Long {
        val nextOrder = (db.smartPlayDao().listAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
        val id = db.smartPlayDao()
            .upsert(SmartPlay(name = name.ifBlank { "SmartPlay" }, sortOrder = nextOrder))
        entries.forEachIndexed { index, entry ->
            db.smartPlayDao().upsertEntry(
                entry.copy(id = 0, smartPlayId = id, sortOrder = index)
            )
        }
        return id
    }

    suspend fun removeFromQueue(episodeId: Long) = db.queueDao().remove(episodeId)

    suspend fun clearQueue() = db.queueDao().clear()

    /** Swaps the episode with its neighbor above/below, then renumbers 0..n. */
    suspend fun moveInQueue(episodeId: Long, up: Boolean) {
        val snapshot = db.queueDao().queueSnapshot()
        val idx = snapshot.indexOfFirst { it.id == episodeId }
        if (idx < 0) return
        val other = if (up) idx - 1 else idx + 1
        if (other !in snapshot.indices) return
        val reordered = snapshot.toMutableList()
        val tmp = reordered[idx]
        reordered[idx] = reordered[other]
        reordered[other] = tmp
        reordered.forEachIndexed { i, ep -> db.queueDao().setPosition(ep.id, i) }
    }

    suspend fun setPlayed(episodeId: Long, played: Boolean) {
        db.episodeDao().setPlayed(
            episodeId, played, if (played) System.currentTimeMillis() else 0L
        )
        if (played) db.queueDao().remove(episodeId)
    }

    suspend fun markAllPlayed(podcastId: Long) {
        db.queueDao().removeForPodcast(podcastId)
        db.episodeDao().markAllPlayed(podcastId, System.currentTimeMillis())
    }

    /** Recently finished episodes, newest first. */
    val history get() = db.episodeDao().observeHistory()

    // ---- downloads --------------------------------------------------------

    suspend fun setDownloadStatus(episodeId: Long, status: Int) =
        db.episodeDao().setDownloadStatus(episodeId, status)

    suspend fun setDownloadProgress(episodeId: Long, pct: Int) =
        db.episodeDao().setDownloadProgress(episodeId, pct)

    suspend fun setDownloaded(episodeId: Long, path: String) {
        db.episodeDao().setDownloaded(episodeId, path)
        // a success wipes the failure history — the enclosure works
        db.episodeDao().resetDownloadAttempts(episodeId)
    }

    suspend fun recordDownloadFailure(episodeId: Long) {
        db.episodeDao().setDownloadStatus(episodeId, Episode.DOWNLOAD_FAILED)
        db.episodeDao().incrementDownloadAttempts(episodeId)
    }

    /** Failed row's Dismiss: hide it AND stop auto-download re-adding it. */
    suspend fun dismissDownload(episodeId: Long) =
        db.episodeDao().dismissDownload(episodeId, Episode.MAX_AUTO_DOWNLOAD_ATTEMPTS)

    // ---- chapters ---------------------------------------------------------

    /**
     * Chapters for the episode. Inline (PSC) chapters parse directly;
     * "json:<url>" markers are fetched once (Podcasting 2.0 JSON), cached
     * back onto the row, and returned. Failures return empty and retry on
     * the next call.
     */
    suspend fun chaptersFor(episodeId: Long): List<Chapter> = withContext(Dispatchers.IO) {
        val episode = db.episodeDao().get(episodeId) ?: return@withContext emptyList()
        val stored = episode.chapters
            // no real chapters: mine the show notes for a timestamped
            // tracklist (the DJ-mix pattern) as synthetic chapters
            ?: return@withContext DescriptionChapters.parse(episode.description)
        if (!stored.startsWith(Chapters.JSON_PREFIX)) {
            return@withContext Chapters.parse(stored).ifEmpty {
                DescriptionChapters.parse(episode.description)
            }
        }
        val url = stored.removePrefix(Chapters.JSON_PREFIX)
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Stepcast/0.1")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val json = org.json.JSONObject(response.body?.string().orEmpty())
                val array = json.optJSONArray("chapters")
                    ?: return@withContext DescriptionChapters.parse(episode.description)
                val chapters = buildList {
                    for (i in 0 until array.length()) {
                        val entry = array.optJSONObject(i) ?: continue
                        add(
                            Chapter(
                                startMs = (entry.optDouble("startTime", 0.0) * 1000).toLong(),
                                title = entry.optString("title")
                            )
                        )
                    }
                }.sortedBy { it.startMs }
                db.episodeDao().setChapters(episodeId, Chapters.serialize(chapters))
                chapters
            }
        } catch (e: Exception) {
            DescriptionChapters.parse(episode.description)
        }
    }

    /** Removes the local file (if any) and clears download state. */
    suspend fun deleteDownload(episodeId: Long) = withContext(Dispatchers.IO) {
        db.episodeDao().get(episodeId)?.localFilePath?.let { path ->
            runCatching { File(path).delete() }
        }
        db.episodeDao().clearDownload(episodeId)
    }

    /** Bulk-applies download retention to every podcast in a category. */
    suspend fun setRetentionForCategory(category: String, keep: Int, maxAgeDays: Int) {
        for (id in db.podcastCategoryDao().memberIds(category)) {
            db.podcastDao().updateRetention(
                id, keep.coerceIn(0, 50), maxAgeDays.coerceIn(0, 3650)
            )
        }
    }

    /** Downloaded-file footprint per podcast, largest first. */
    suspend fun downloadUsage(): List<StorageUsage> = withContext(Dispatchers.IO) {
        buildList {
            for (podcast in db.podcastDao().listAll()) {
                var count = 0
                var bytes = 0L
                for (episode in db.episodeDao().listForPodcast(podcast.id)) {
                    val file = episode.localFilePath?.let(::File) ?: continue
                    if (file.exists()) {
                        count++
                        bytes += file.length()
                    }
                }
                if (count > 0) add(StorageUsage(podcast, count, bytes))
            }
        }.sortedByDescending { it.bytes }
    }

    /** Deletes every downloaded file for one podcast. */
    suspend fun deleteDownloadsForPodcast(podcastId: Long) {
        for (episode in db.episodeDao().listForPodcast(podcastId)) {
            if (episode.localFilePath != null) deleteDownload(episode.id)
        }
    }

    // ---- playback support -----------------------------------------------

    suspend fun savePosition(episodeId: Long, positionMs: Long, durationMs: Long) {
        db.episodeDao().updatePosition(episodeId, positionMs.coerceAtLeast(0))
        if (durationMs > 0) db.episodeDao().updateDurationIfUnknown(episodeId, durationMs)
    }

    /** "Finished" mark used by completion and done-and-delete paths. */
    suspend fun markPlayed(episodeId: Long) {
        val wasPlayed = db.episodeDao().get(episodeId)?.played ?: false
        db.episodeDao().setPlayed(episodeId, true, System.currentTimeMillis())
        if (!wasPlayed) ListenStats.addFinishedEpisode(appContext)
    }

    suspend fun introSkipMsFor(episodeId: Long): Long = skipMsFor(episodeId, intro = true)

    suspend fun outroSkipMsFor(episodeId: Long): Long = skipMsFor(episodeId, intro = false)

    private suspend fun skipMsFor(episodeId: Long, intro: Boolean): Long {
        val episode = db.episodeDao().get(episodeId) ?: return 0
        val podcast = db.podcastDao().get(episode.podcastId) ?: return 0
        val sec = if (intro) podcast.introSkipSec else podcast.outroSkipSec
        return sec.coerceAtLeast(0) * 1000L
    }

    private fun ParsedEpisode.toEntity(podcastId: Long) = Episode(
        podcastId = podcastId,
        guid = guid,
        title = title,
        description = description,
        audioUrl = audioUrl,
        imageUrl = imageUrl,
        pubDateMs = pubDateMs,
        durationMs = durationMs,
        chapters = chapters,
        transcriptUrl = transcriptUrl,
        transcriptType = transcriptType
    )

    /** Returns the number of genuinely new rows (conflicts are ignored). */
    private suspend fun insertEpisodes(podcastId: Long, feed: ParsedFeed): Int {
        // OnConflict IGNORE keeps existing rows (and their playback position)
        val added = db.episodeDao()
            .insertAll(feed.episodes.map { it.toEntity(podcastId) })
            .count { it != -1L }
        backfillTranscripts(podcastId, feed)
        return added
    }

    /** Like [insertEpisodes] but returns the NEW episodes' row ids. */
    private suspend fun insertEpisodesReturningIds(
        podcastId: Long,
        feed: ParsedFeed
    ): List<Long> {
        val ids = db.episodeDao()
            .insertAll(feed.episodes.map { it.toEntity(podcastId) })
            .filter { it != -1L }
        backfillTranscripts(podcastId, feed)
        return ids
    }

    /** Insert IGNOREs conflicts, so episodes that existed before a feed
     * started publishing transcripts pick them up here on refresh. */
    private suspend fun backfillTranscripts(podcastId: Long, feed: ParsedFeed) {
        for (ep in feed.episodes) {
            val url = ep.transcriptUrl ?: continue
            db.episodeDao().backfillTranscript(podcastId, ep.guid, url, ep.transcriptType)
        }
    }

    // ---- inbox (New episodes) ---------------------------------------------

    private fun inboxSinceMs() = System.currentTimeMillis() - INBOX_WINDOW_MS

    fun inbox() = db.episodeDao().observeInbox(inboxSinceMs())

    fun inboxCount() = db.episodeDao().observeInboxCount(inboxSinceMs())

    suspend fun dismissFromInbox(ids: List<Long>) =
        db.episodeDao().setInboxDismissed(ids, true)

    suspend fun restoreToInbox(ids: List<Long>) =
        db.episodeDao().setInboxDismissed(ids, false)

    // ---- transcripts --------------------------------------------------------

    /** Downloads and parses an episode's transcript. Throws on HTTP failure. */
    suspend fun fetchTranscript(url: String, type: String?): List<TranscriptCue> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Stepcast/0.1")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
                Transcripts.parse(response.body?.string().orEmpty(), type)
            }
        }

    // ---- stations (continuous SmartPlays) ----------------------------------

    suspend fun smartPlay(id: Long): SmartPlay? = db.smartPlayDao().get(id)

    suspend fun setSmartPlayContinuous(id: Long, continuous: Boolean) =
        db.smartPlayDao().setContinuous(id, continuous)

    /** Fetches and parses a feed WITHOUT subscribing — the Discover preview. */
    suspend fun previewFeed(feedUrl: String): ParsedFeed =
        withContext(Dispatchers.IO) { fetchFeed(feedUrl) }

    /**
     * Directory results (iTunes) often carry a different-but-equivalent URL
     * than the one a show was originally subscribed under — scheme, trailing
     * slash, host casing — so exact matching alone misses subscriptions.
     */
    suspend fun podcastIdForFeed(feedUrl: String): Long? {
        db.podcastDao().getByFeedUrl(feedUrl)?.let { return it.id }
        val target = normalizedFeedUrl(feedUrl)
        return db.podcastDao().listAll()
            .firstOrNull { normalizedFeedUrl(it.feedUrl) == target }?.id
    }

    /**
     * Dead-feed recovery: repoints an existing subscription at a new feed
     * URL. Episodes already in the library stay (played state, downloads,
     * position); the new feed's episodes merge in by guid. Validates by
     * actually fetching the new feed first, so a bad pick never bricks the
     * subscription. Caller must ensure no OTHER podcast already uses the URL.
     */
    suspend fun repointFeed(podcastId: Long, newFeedUrl: String) =
        withContext(Dispatchers.IO) {
            val podcast = db.podcastDao().get(podcastId)
                ?: throw IOException("podcast $podcastId is gone")
            val feed = fetchFeed(newFeedUrl)
            db.podcastDao().update(
                podcast.copy(
                    feedUrl = newFeedUrl,
                    title = feed.title.ifEmpty { podcast.title },
                    description = feed.description.ifEmpty { podcast.description },
                    imageUrl = feed.imageUrl ?: podcast.imageUrl,
                    author = feed.author.ifEmpty { podcast.author },
                    consecutiveFailures = 0,
                    lastRefreshed = System.currentTimeMillis()
                )
            )
            insertEpisodes(podcastId, feed)
            autoManageDownloads(podcastId)
        }

    /** Case-insensitive title match — the last-resort "already subscribed"
     * signal when the same show lives under an entirely different feed URL. */
    suspend fun podcastIdByTitle(title: String): Long? {
        val clean = title.trim()
        if (clean.isEmpty()) return null
        return db.podcastDao().listAll()
            .firstOrNull { it.title.equals(clean, ignoreCase = true) }?.id
    }

    private fun normalizedFeedUrl(url: String): String =
        url.trim().substringAfter("://").removeSuffix("/").lowercase()

    private fun fetchFeed(feedUrl: String): ParsedFeed {
        val request = Request.Builder()
            .url(feedUrl)
            .header("User-Agent", "Stepcast/0.1")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $feedUrl")
            val body = response.body ?: throw IOException("Empty body for $feedUrl")
            return RssParser.parse(body.byteStream())
        }
    }
}

/** One podcast's downloaded-file footprint. */
data class StorageUsage(val podcast: Podcast, val episodes: Int, val bytes: Long)

/** How far back the New-episodes inbox reaches. */
private const val INBOX_WINDOW_MS = 14L * 86_400_000
