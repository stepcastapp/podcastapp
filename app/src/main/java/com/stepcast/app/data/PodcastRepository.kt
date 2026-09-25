package com.stepcast.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.stepcast.app.download.DownloadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
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
    private val http: OkHttpClient = Http.api
) {
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * ONE shared upstream per observed query. The UI used to call a getter
     * that built a fresh Room Flow on every access — and collectAsState keys
     * on the Flow instance, so every recomposition restarted the query.
     * distinctUntilChanged matters just as much: every position save (each
     * few seconds while playing) invalidates the episodes table and Room
     * re-runs + re-emits every episodes query with identical rows, which
     * then recomposed whole screens. WhileSubscribed(5s) stops the queries
     * shortly after the last screen goes away (app backgrounded).
     */
    private fun <T> Flow<T>.shared(): SharedFlow<T> =
        distinctUntilChanged().shareIn(repoScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    val podcasts = db.podcastDao().observeAll().shared()

    /**
     * "My library" — excludes shows that exist only to hold a one-off
     * saved episode. Use this for anything that treats a podcast as a
     * subscription (the grid, refresh, schedule rules, SmartPlay scopes,
     * OPML export); use [podcasts] when resolving an episode's show.
     */
    val subscribedPodcasts = db.podcastDao().observeSubscribed().shared()

    suspend fun subscribedPodcastList(): List<Podcast> =
        db.podcastDao().listSubscribed()

    /**
     * Keeps ONE episode without subscribing to its show — "I heard about
     * this episode, I want to hear it, I don't want the feed."
     *
     * The show still gets a row (an episode has to belong to something,
     * and Up Next/Downloads/History resolve titles and artwork through
     * it) but it is flagged unsubscribed, so it stays out of the library
     * grid and is never refreshed. keepDownloads = 0 matters: the
     * auto-manage pass deletes played downloads only when it is above
     * zero, and a deliberately saved episode must not evaporate the
     * moment it is finished.
     *
     * Idempotent — saving the same episode twice returns the same row.
     */
    suspend fun saveEpisodeWithoutSubscribing(
        feedUrl: String,
        feed: ParsedFeed,
        episode: ParsedEpisode
    ): Long = withContext(Dispatchers.IO) {
        val podcastId = podcastIdForFeed(feedUrl) ?: insertPodcastOrExisting(
            Podcast(
                feedUrl = feedUrl,
                title = feed.title,
                description = feed.description,
                imageUrl = feed.imageUrl,
                author = feed.author,
                subscribed = false,
                keepDownloads = 0,
                lastRefreshed = System.currentTimeMillis()
            )
        )
        db.episodeDao().insertAll(listOf(episode.toEntity(podcastId)))
        // by audioUrl, not the insert's return: a repeat save conflicts
        // (IGNORE) and yields -1, but the row we want is already there
        db.episodeDao().getByAudioUrl(episode.audioUrl)?.id ?: 0L
    }

    fun episodesFor(podcastId: Long) = db.episodeDao().observeForPodcast(podcastId).distinctUntilChanged()

    fun observePodcast(podcastId: Long) = db.podcastDao().observe(podcastId).distinctUntilChanged()

    suspend fun podcast(podcastId: Long) = db.podcastDao().get(podcastId)

    suspend fun podcastsByIds(ids: Collection<Long>): List<Podcast> =
        if (ids.isEmpty()) emptyList() else db.podcastDao().getByIds(ids.distinct())

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
            // the show may already exist purely to hold a saved episode —
            // subscribing promotes that row rather than duplicating it
            db.podcastDao().markSubscribed(existingId)
            refresh(existingId)
            return@withContext existingId
        }
        val feed = prefetched ?: fetchFeed(feedUrl)
        val id = insertPodcastOrExisting(
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
        applyPendingRestore(id, feedUrl)
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
        val fetched = fetchFeedConditional(
            podcast.feedUrl, podcast.feedEtag, podcast.feedLastModified
        )
        if (fetched == null) {
            // 304: nothing changed since the last full fetch — no download,
            // no parse, no per-episode writes. The common case for a
            // 300-feed library checked several times a day.
            db.podcastDao().markRefreshedUnchanged(podcastId, System.currentTimeMillis())
            // download rules still run — they depend on what was played and
            // how old things got, not on whether the feed changed
            if (!isInitialImport) autoManageDownloads(podcastId)
            return@withContext 0
        }
        val moved = adoptMovedFeed(podcast, fetched)
        val feed = moved.feed
        val newIds = insertEpisodesReturningIds(podcastId, feed)
        db.podcastDao().updateFunding(podcastId, feed.fundingUrl, feed.fundingLabel)
        // only once the rows are safely stored: validators saved before a
        // failed insert would turn every later refresh into a 304 that
        // never delivers those episodes
        db.podcastDao().updateValidators(podcastId, moved.etag, moved.lastModified)
        // restored backup state waits for these rows to exist
        applyPendingRestore(podcastId, podcast.feedUrl)
        db.podcastDao().updateFromFeed(
            podcastId,
            // the parser's placeholder must never replace a real title
            title = feed.title.takeIf { it != "(untitled feed)" }.orEmpty(),
            description = feed.description,
            imageUrl = feed.imageUrl,
            author = feed.author,
            lastRefreshed = System.currentTimeMillis()
        )
        if (podcast.autoQueue && newIds.isNotEmpty()) {
            // append in the show's own listening order — a serial show
            // (oldest-first) used to get new episodes queued in reverse
            val newEps = newIds.mapNotNull { db.episodeDao().get(it) }
            val ordered = if (podcast.sortOldestFirst) {
                newEps.sortedBy { it.pubDateMs }
            } else {
                newEps.sortedByDescending { it.pubDateMs }
            }
            appendToQueueLast(ordered.map { it.id })
        }
        if (podcast.episodeCap > 0) {
            val pruned = db.episodeDao().pruneBeyondCap(podcastId, podcast.episodeCap)
            if (pruned > 0) PlaybackJournal.log("prune", "pod=$podcastId removed=$pruned")
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
                // exhausted/dismissed enclosures excluded BEFORE take():
                // with keep=2, two dead newest episodes used to consume
                // both slots forever and nothing else ever auto-downloaded
                .filter { it.downloadAttempts < Episode.MAX_AUTO_DOWNLOAD_ATTEMPTS }
                .take(podcast.keepDownloads)
                .filter { it.downloadStatus == Episode.DOWNLOAD_NONE }
                .forEach { DownloadWorker.start(appContext, it.id, userInitiated = false) }
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
        if (episodeCap > 0) {
            val pruned = db.episodeDao().pruneBeyondCap(podcastId, episodeCap)
            if (pruned > 0) PlaybackJournal.log("prune", "pod=$podcastId removed=$pruned")
        }
    }

    suspend fun recordRefreshFailure(podcastId: Long) {
        // local folders have no feed to "fail" — a transient SAF hiccup
        // must not badge them or offer the feed-replacement repair
        if (db.podcastDao().get(podcastId)?.localFolderUri != null) return
        db.podcastDao().incrementFailures(podcastId)
    }

    fun episodesForPaged(
        podcastId: Long,
        sortMode: Int,
        oldestFirst: Boolean,
        limit: Int
    ) = db.episodeDao().observeForPodcastPaged(
        podcastId, sortMode, if (oldestFirst) 1 else 0, limit
    ).distinctUntilChanged()

    /** True total/unplayed counts, independent of the paged list. */
    fun episodeCounts(podcastId: Long) = db.episodeDao().observeCounts(podcastId).distinctUntilChanged()

    /** Per-podcast downloaded/favorite/unplayed counts for Home badges. */
    val podcastBadgeCounts = db.episodeDao().observeBadgeCounts().shared()

    /** Each show's newest episode date, for the Library's "most recent" sort. */
    val podcastLatestEpisodeDates = db.episodeDao().observeLatestEpisodeDates().shared()

    suspend fun setFavorite(episodeId: Long, favorite: Boolean) =
        db.episodeDao().setFavorite(episodeId, favorite)

    suspend fun setLastEpisodeFilter(podcastId: Long, mode: Int) =
        db.podcastDao().setLastEpisodeFilter(podcastId, mode)

    suspend fun setEpisodeSortMode(podcastId: Long, mode: Int) =
        db.podcastDao().setEpisodeSortMode(podcastId, mode)

    /** Bulk cleanup: everything older than [days] becomes played. */
    suspend fun markPlayedOlderThan(podcastId: Long, days: Int) {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        // playedAtMs = 0: bulk cleanups must not flood History (which
        // filters playedAtMs > 0) and evict what the user actually heard
        db.episodeDao().markPlayedOlderThan(podcastId, cutoff, 0L)
        // scoped: cleaning one show must not clear other shows' played
        // episodes the user deliberately left in Up Next
        db.queueDao().removePlayedForPodcast(podcastId)
        PlaybackJournal.log("bulk", "olderThan pod=$podcastId days=$days")
    }

    suspend fun markPlayedOlderThanInCategory(category: String, days: Int) {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        db.episodeDao().markPlayedOlderThanInFolder(category, cutoff, 0L)
        db.queueDao().removePlayedForFolder(category)
        PlaybackJournal.log("bulk", "olderThan folder=$category days=$days")
    }

    /** Per-podcast listening time accumulation (see ListenStats). */
    suspend fun addPodcastListening(podcastId: Long, wallMs: Long, contentMs: Long) {
        // insert-IGNORE first, then bump: the old bump-then-insert order
        // dropped the delta when another writer created the row in between
        db.listenStatDao().insert(ListenStat(podcastId, 0, 0))
        db.listenStatDao().bump(podcastId, wallMs, contentMs)
        // per-day too: the yearly recap needs WHEN, not just how much
        val day = java.time.LocalDate.now().toEpochDay()
        db.listenDailyDao().insert(ListenDaily(day, podcastId))
        db.listenDailyDao().bump(day, podcastId, wallMs, contentMs)
    }

    suspend fun topListenStats(limit: Int = 8): List<Pair<Podcast, ListenStat>> =
        db.listenStatDao().top(limit).mapNotNull { stat ->
            db.podcastDao().get(stat.podcastId)?.let { it to stat }
        }

    suspend fun clearListenStats() {
        db.listenStatDao().clear()
        db.listenDailyDao().clear()
    }

    // ---- yearly recap -------------------------------------------------------

    data class YearRecap(
        val year: Int,
        val wallMs: Long,
        val contentMs: Long,
        val episodesFinished: Int,
        val activeDays: Int,
        /** Top shows by listening time, largest first. */
        val topShows: List<Pair<Podcast, Long>>,
        /** 1..12 → listening ms. */
        val byMonth: Map<Int, Long>,
        val longestStreakDays: Int
    )

    suspend fun yearRecap(year: Int): YearRecap = withContext(Dispatchers.IO) {
        val zone = java.time.ZoneId.systemDefault()
        val first = java.time.LocalDate.of(year, 1, 1)
        val last = java.time.LocalDate.of(year, 12, 31)
        val rows = db.listenDailyDao().range(first.toEpochDay(), last.toEpochDay())
        val byShow = rows.groupBy { it.podcastId }.mapValues { (_, r) -> r.sumOf { it.wallMs } }
        val shows = podcastsByIds(byShow.keys).associateBy { it.id }
        val days = rows.filter { it.wallMs > 0 }.map { it.day }.toSortedSet()
        var longest = 0
        var run = 0
        var prev = Long.MIN_VALUE
        for (d in days) {
            run = if (d == prev + 1) run + 1 else 1
            longest = maxOf(longest, run)
            prev = d
        }
        YearRecap(
            year = year,
            wallMs = rows.sumOf { it.wallMs },
            contentMs = rows.sumOf { it.contentMs },
            episodesFinished = db.episodeDao().countPlayedBetween(
                first.atStartOfDay(zone).toInstant().toEpochMilli(),
                last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            ),
            activeDays = days.size,
            topShows = byShow.entries.sortedByDescending { it.value }
                .mapNotNull { (id, ms) -> shows[id]?.let { it to ms } }
                .take(5),
            byMonth = rows.groupBy { java.time.LocalDate.ofEpochDay(it.day).monthValue }
                .mapValues { (_, r) -> r.sumOf { it.wallMs } },
            longestStreakDays = longest
        )
    }

    // ---- bookmarks ----------------------------------------------------------

    fun bookmarksFor(episodeId: Long) = db.bookmarkDao().observeFor(episodeId).distinctUntilChanged()

    val allBookmarks = db.bookmarkDao().observeAll().shared()

    suspend fun addBookmark(episodeId: Long, positionMs: Long, note: String = ""): Long =
        db.bookmarkDao().insert(Bookmark(episodeId = episodeId, positionMs = positionMs, note = note))

    suspend fun setBookmarkNote(id: Long, note: String) = db.bookmarkDao().setNote(id, note)

    suspend fun deleteBookmark(id: Long) = db.bookmarkDao().delete(id)

    suspend fun allBookmarkList(): List<Bookmark> = db.bookmarkDao().listAll()

    /** Bookmarks as portable references for the backup. */
    suspend fun exportBookmarks(): List<EpisodeStateRestore.BookmarkRef> {
        val list = db.bookmarkDao().listAll()
        if (list.isEmpty()) return emptyList()
        val episodes = list.map { it.episodeId }.distinct()
            .mapNotNull { db.episodeDao().get(it) }.associateBy { it.id }
        val feeds = podcastsByIds(episodes.values.map { it.podcastId })
            .filter { it.localFolderUri == null }
            .associate { it.id to it.feedUrl }
        return list.mapNotNull { b ->
            val ep = episodes[b.episodeId] ?: return@mapNotNull null
            val feed = feeds[ep.podcastId] ?: return@mapNotNull null
            EpisodeStateRestore.BookmarkRef(feed, ep.guid, ep.audioUrl, b.positionMs, b.note, b.createdAt)
        }
    }

    /** Restore: skips a bookmark already present at the same spot. */
    suspend fun restoreBookmarks(podcastId: Long, refs: List<EpisodeStateRestore.BookmarkRef>) {
        for (r in refs) {
            val id = resolveEpisodeId(podcastId, r.guid, r.audioUrl) ?: continue
            val existing = db.bookmarkDao().listAll()
                .any { it.episodeId == id && it.positionMs == r.positionMs }
            if (!existing) {
                db.bookmarkDao().insert(
                    Bookmark(episodeId = id, positionMs = r.positionMs, note = r.note, createdAt = r.createdAt)
                )
            }
        }
    }

    // ---- full-text search ---------------------------------------------------

    /**
     * (Re)builds the show-notes search index from the episodes table. Run
     * once in the background after the v24 upgrade (the migration only
     * creates the empty index); new/changed rows stay indexed via triggers.
     */
    suspend fun rebuildSearchIndex() = withContext(Dispatchers.IO) {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO episodes_fts(episodes_fts) VALUES('rebuild')"
        )
    }

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

    /**
     * Subscribes to many feeds concurrently (bounded); returns successes.
     * OPML entries carry their outline folder — round-tripping our own
     * export now preserves categories instead of flattening the library.
     */
    suspend fun subscribeAll(entries: List<Opml.Entry>): Int = coroutineScope {
        val gate = Semaphore(6)
        entries
            .map { entry ->
                async {
                    gate.withPermit {
                        runCatching {
                            val id = subscribe(entry.url, suppressBacklogAutoDownload = true)
                            entry.folder?.let { addToCategory(id, it) }
                        }.isSuccess
                    }
                }
            }
            .awaitAll()
            .count { it }
    }

    suspend fun unsubscribe(podcastId: Long) = withContext(Dispatchers.IO) {
        val episodes = db.episodeDao().listForPodcast(podcastId)
        episodes.forEach { episode ->
            episode.localFilePath?.let { runCatching { File(it).delete() } }
            // local-folder art cache would otherwise leak forever
            runCatching {
                File(File(appContext.filesDir, "local_art"), "${episode.id}.jpg").delete()
            }
        }
        // one transaction: five separate commits left visible half-deleted
        // states and, on interruption, orphaned rows
        db.withTransaction {
            db.queueDao().removeForPodcast(podcastId)
            db.bookmarkDao().deleteForPodcast(podcastId)
            db.episodeDao().deleteForPodcast(podcastId)
            db.podcastCategoryDao().removeAllFor(podcastId)
            // rowids get recycled — a leaked stats row would gift the NEXT
            // subscription this show's lifetime listening time
            db.listenStatDao().deleteFor(podcastId)
            db.podcastDao().delete(podcastId)
        }
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
        // 0 = clear the override; otherwise the same range the global
        // default allows — a per-show 4x the UI can't express was possible
        val clamped = if (speed <= 0f) 0f else speed.coerceIn(0.5f, 3.0f)
        db.podcastDao().updatePlaybackSpeed(podcastId, clamped)
    }

    /** Per-podcast playback speed for the episode's feed; 0 = no override. */
    /**
     * Everything the player needs to start an episode CORRECTLY, in one
     * podcast lookup: the row itself (for the resume position), the intro
     * skip, and the per-show speed.
     *
     * Fetched together on purpose. These are all applied in the moment
     * between a media-item transition and the first audible output, and
     * every separate suspending round trip in there is a window where
     * ExoPlayer is already producing sound with the PREVIOUS episode's
     * settings — heard as a flash of the intro, or (field report) the first
     * second of a slow show playing at the fast show's speed.
     */
    suspend fun episodeStartSettings(episodeId: Long): EpisodeStartSettings {
        val episode = db.episodeDao().get(episodeId)
            ?: return EpisodeStartSettings(null, 0L, 0f)
        val podcast = db.podcastDao().get(episode.podcastId)
        return EpisodeStartSettings(
            episode = episode,
            introMs = (podcast?.introSkipSec ?: 0).coerceAtLeast(0) * 1000L,
            speed = podcast?.playbackSpeed ?: 0f
        )
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
        val id = insertPodcastOrExisting(
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
                        pubDateMs = file.lastModified(),
                        sourceFileName = name
                    )
                }
            }
        }
        scan(root, "", 0)

        val insertedIds = db.episodeDao().insertAll(found)
        val added = insertedIds.count { it != -1L }

        // backfill the raw filename onto episodes scanned before this field
        // existed (insertAll ignores conflicts, so it never touches them) —
        // guid is the file's own URI, same key `found` is keyed by
        val nameByGuid = found.associate { it.guid to it.sourceFileName }
        for (ep in db.episodeDao().listForPodcast(podcast.id)) {
            if (ep.sourceFileName == null) {
                nameByGuid[ep.guid]?.let { name ->
                    db.episodeDao().updateSourceFileNameIfMissing(ep.id, name)
                }
            }
        }

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

        // prune episodes whose backing file is gone (and their cached art) —
        // but an EMPTY scan of a folder that used to have episodes almost
        // always means the storage/SAF grant was momentarily unreadable
        // (ejected SD card, revoked permission, slow mount at boot), not
        // that the user deleted every file. Wiping the rows would destroy
        // all positions/played flags; skip the prune and let a later scan
        // that can actually see files do it.
        val existing = db.episodeDao().listForPodcast(podcast.id)
        if (found.isEmpty() && existing.isNotEmpty()) {
            PlaybackJournal.log(
                "scan-empty", "pod=${podcast.id} kept=${existing.size} prune skipped"
            )
        } else {
            val validGuids = found.mapTo(HashSet()) { it.guid }
            for (episode in existing) {
                if (episode.guid !in validGuids) {
                    db.queueDao().remove(episode.id)
                    db.episodeDao().deleteById(episode.id)
                    java.io.File(artDir, "${episode.id}.jpg").delete()
                }
            }
        }
        // narrow write: the SAF walk above can take a while, and a stale
        // full-row update would revert settings edited meanwhile
        db.podcastDao().updateLocalScan(
            podcast.id, System.currentTimeMillis(), folderArt
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

    val podcastCategories = db.podcastCategoryDao().observeAll().shared()

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
        // atomic: delete-then-re-add as separate commits let observers see
        // (and an interruption keep) an uncategorized intermediate state
        db.withTransaction {
            db.podcastCategoryDao().removeAllFor(podcastId)
            categories.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach {
                ensureCategoryMeta(it)
                db.podcastCategoryDao().add(PodcastCategory(podcastId, it))
            }
            syncPrimaryFolder(podcastId)
        }
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
        // atomic so an interruption can't lose the meta (sort order) between
        // the delete and the re-upsert
        db.withTransaction {
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
    }

    /**
     * Removes the category: its podcasts lose that membership (keeping any
     * others), and SmartPlay rules scoped to it are deleted (matching
     * BeyondPod's category-delete behavior).
     */
    suspend fun deleteCategory(name: String) {
        db.withTransaction {
            val members = db.podcastCategoryDao().memberIds(name)
            db.podcastCategoryDao().deleteCategory(name)
            db.podcastDao().clearFolder(name)
            members.forEach { syncPrimaryFolder(it) }
            db.smartPlayDao().deleteEntriesForFolder(name)
            // a SmartPlay whose last rule pointed here would live on in the
            // strip/widget/shortcuts resolving to nothing — remove empties
            db.smartPlayDao().listAll().forEach { play ->
                if (db.smartPlayDao().entriesFor(play.id).isEmpty()) {
                    db.smartPlayDao().delete(play.id)
                }
            }
            db.categoryDao().delete(name)
        }
    }

    // ---- category meta (manual order + refresh cadence) --------------------

    val categoryMetas = db.categoryDao().observeAll().shared()

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
    fun episodesForCategory(category: String) = db.episodeDao().observeForFolder(category).distinctUntilChanged()

    // ---- SmartPlays -------------------------------------------------------

    val smartPlays = db.smartPlayDao().observeAll().shared()

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

    fun observeSmartPlay(id: Long) = db.smartPlayDao().observe(id).distinctUntilChanged()

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
                downloadedOnly = if (entry.downloadedOnly) 1 else 0,
                oldestFirst = if (entry.episodeSort == SmartPlayEntry.SORT_OLDEST) 1 else 0
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

    val queue = db.queueDao().observeQueue().shared()

    /** Running + failed downloads, for the download-activity dialog. */
    /** "Continue listening" on the Library. */
    val inProgress = db.episodeDao().observeInProgress().shared()

    val downloadActivity = db.episodeDao().observeDownloadActivity().shared()

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
        // escape LIKE wildcards: searching "100%" must not match everything
        val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val titleHits = db.episodeDao().searchByTitle(escaped)
        // then show notes: every word must appear (prefix match, so "clim"
        // finds "climate"); title hits stay first
        val terms = q.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        val notesHits = if (terms.isEmpty()) {
            emptyList()
        } else {
            runCatching {
                db.episodeDao().searchFullText(terms.joinToString(" ") { "$it*" })
            }.getOrDefault(emptyList())
        }
        val seen = titleHits.mapTo(HashSet()) { it.id }
        return shows to (titleHits + notesHits.filter { seen.add(it.id) }).take(100)
    }

    suspend fun queueSnapshot(): List<Episode> = db.queueDao().queueSnapshot()

    /** Appends the episode to the end of the up-next queue. */
    suspend fun addToQueueLast(episodeId: Long) {
        db.queueDao().insert(QueueItem(episodeId, (db.queueDao().maxPosition() ?: 0) + 1))
    }

    /**
     * Batch append in ONE transaction = one Flow emission. Station refill
     * appends several episodes at once; per-row inserts let the UI's
     * queueSync interleave mid-refill and double-add the same episodes.
     */
    suspend fun appendToQueueLast(episodeIds: List<Long>) {
        if (episodeIds.isEmpty()) return
        db.withTransaction {
            var position = (db.queueDao().maxPosition() ?: 0)
            for (id in episodeIds) {
                db.queueDao().insert(QueueItem(id, ++position))
            }
        }
    }

    /**
     * The one streaming-off policy: where this episode may play from RIGHT
     * NOW. Local file when downloaded; the remote URL only when streaming
     * is allowed (or it's a local-folder content: uri); null = not playable
     * until downloaded. Every timeline-building path routes through this so
     * "Wi-Fi only" can't leak via queue tails, SmartPlays, station refills,
     * auto-continue, Android Auto, or resumption.
     */
    fun playableUri(episode: Episode): String? {
        episode.localFilePath
            ?.let { java.io.File(it) }
            ?.takeIf { it.exists() }
            ?.let { return android.net.Uri.fromFile(it).toString() }
        if (episode.audioUrl.startsWith("content:")) return episode.audioUrl
        return if (AppSettings.streamWhenNotDownloaded) episode.audioUrl else null
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
        val id = insertPodcastOrExisting(
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

    /**
     * IGNORE-insert returns -1 on a feedUrl unique-index conflict — which
     * happens for real (concurrent OPML subscribes, an import racing a
     * refresh). Using -1 as a podcast id used to scatter orphan episode
     * rows under podcastId = -1; resolve to the winner's id instead.
     */
    private suspend fun insertPodcastOrExisting(podcast: Podcast): Long {
        val id = db.podcastDao().insert(podcast)
        if (id > 0) return id
        return db.podcastDao().getByFeedUrl(podcast.feedUrl)?.id
            ?: throw IOException("insert lost a race and no row exists for ${podcast.feedUrl}")
    }

    /** Per-show refresh rule (see ScheduleEngine.MODE_*). */
    suspend fun setScheduleRule(podcastId: Long, mode: Int, param: Int) =
        db.podcastDao().updateScheduleRule(podcastId, mode, param)

    /** Newest-first publish times, the release-pattern inference input. */
    suspend fun recentPubDates(podcastId: Long): List<Long> =
        db.episodeDao().recentPubDates(podcastId)

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
            downloadedOnly = if (entry.downloadedOnly) 1 else 0,
            oldestFirst = 0
        ).size

    /**
     * Why does a rule match nothing? Reports what the rule's scope really
     * contains so a broken pointer is visible instead of mysterious.
     */
    /** Raw scope counts; the UI phrases them (strings live in resources). */
    sealed class SmartPlayExplain {
        data class Feed(val episodes: Int, val unplayed: Int) : SmartPlayExplain()
        data class Category(val podcasts: Int) : SmartPlayExplain()
        object Everything : SmartPlayExplain()
    }

    suspend fun explainSmartPlayEntry(entry: SmartPlayEntry): SmartPlayExplain {
        entry.podcastId?.let { id ->
            val episodes = db.episodeDao().listForPodcast(id)
            return SmartPlayExplain.Feed(episodes.size, episodes.count { !it.played })
        }
        entry.folder?.let { folder ->
            return SmartPlayExplain.Category(db.podcastCategoryDao().memberCount(folder))
        }
        return SmartPlayExplain.Everything
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
        // one transaction: per-row updates emitted N intermediate queue
        // states per tap (flicker + racy observers)
        db.withTransaction {
            reordered.forEachIndexed { i, ep -> db.queueDao().setPosition(ep.id, i) }
        }
    }

    suspend fun setPlayed(episodeId: Long, played: Boolean) {
        val wasPlayed = db.episodeDao().get(episodeId)?.played ?: false
        db.episodeDao().setPlayed(
            episodeId, played, if (played) System.currentTimeMillis() else 0L
        )
        if (played) {
            db.queueDao().remove(episodeId)
            // a deliberate single mark-played counts as finished, same as
            // playback completion (bulk cleanups deliberately do NOT)
            if (!wasPlayed) ListenStats.addFinishedEpisode(appContext)
        }
        PlaybackJournal.log("played", "toggle=$played ep=$episodeId")
    }

    suspend fun markAllPlayed(podcastId: Long) {
        db.queueDao().removeForPodcast(podcastId)
        db.episodeDao().markAllPlayed(podcastId, 0L) // 0: keep History honest
    }

    /** Recently finished episodes, newest first. */
    val history = db.episodeDao().observeHistory().shared()

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
                // never cache EMPTY over the json: marker — that used to
                // destroy the URL pointer forever after one bad fetch
                if (chapters.isNotEmpty()) {
                    db.episodeDao().setChapters(episodeId, Chapters.serialize(chapters))
                }
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

    /** Saves category-level retention defaults and applies them to every member podcast. */
    suspend fun setCategoryRetention(category: String, keep: Int, maxAge: Int) {
        val k = keep.coerceIn(0, 50)
        val a = maxAge.coerceIn(0, 3650)
        db.categoryDao().updateRetention(category, k, a)
    }

    /** Resets every podcast in the category to the category's stored retention. */
    suspend fun resetPodcastsToCategory(category: String) {
        val meta = db.categoryDao().get(category) ?: return
        for (id in db.podcastCategoryDao().memberIds(category)) {
            db.podcastDao().updateRetention(id, meta.keepDownloads, meta.maxAgeDays)
            autoManageDownloads(id)
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

    /**
     * Storage limit: deletes downloads of PLAYED episodes, oldest-played
     * first, until [bytes] are freed (or nothing played is left). Unplayed
     * downloads are never touched to make room.
     */
    suspend fun freeSpaceFromPlayedDownloads(bytes: Long) = withContext(Dispatchers.IO) {
        var freed = 0L
        val candidates = db.episodeDao().listDownloadedPlayed()
        for (ep in candidates) {
            if (freed >= bytes) break
            val size = ep.localFilePath?.let { File(it).length() } ?: 0L
            deleteDownload(ep.id)
            freed += size
        }
        freed
    }

    /** Deletes every downloaded file for one podcast. */
    suspend fun deleteDownloadsForPodcast(podcastId: Long) {
        for (episode in db.episodeDao().listForPodcast(podcastId)) {
            if (episode.localFilePath != null) deleteDownload(episode.id)
        }
    }

    // ---- playback support -----------------------------------------------

    suspend fun savePosition(
        episodeId: Long,
        positionMs: Long,
        durationMs: Long,
        source: String = "save"
    ) {
        db.episodeDao().updatePosition(episodeId, positionMs.coerceAtLeast(0))
        // the player's duration is authoritative for the loaded file: it
        // corrects a lying feed value (wrong units, placeholders), which
        // otherwise poisons the near-end resume guard forever
        if (durationMs > 0) db.episodeDao().correctDuration(episodeId, durationMs)
        PlaybackJournal.log("pos", "$source ep=$episodeId pos=$positionMs dur=$durationMs")
    }

    // ---- backup of listening state ---------------------------------------

    /** Every episode carrying played/position/favorite state, keyed by feed URL. */
    suspend fun exportEpisodeStates(): List<EpisodeStateRestore.Entry> {
        val feedById = db.podcastDao().listAll()
            .filter { it.localFolderUri == null } // SAF grants don't transfer
            .associate { it.id to it.feedUrl }
        return db.episodeDao().listWithState().mapNotNull { row ->
            val feed = feedById[row.podcastId] ?: return@mapNotNull null
            EpisodeStateRestore.Entry(
                feedUrl = feed,
                guid = row.guid,
                audioUrl = row.audioUrl,
                played = row.played,
                playedAtMs = row.playedAtMs,
                positionMs = row.positionMs,
                favorite = row.favorite
            )
        }
    }

    /** Up Next as portable references (feed + guid), in play order. */
    suspend fun exportQueueRefs(): List<EpisodeStateRestore.QueueRef> {
        val queue = db.queueDao().queueSnapshot()
        val feedById = podcastsByIds(queue.map { it.podcastId })
            .filter { it.localFolderUri == null }
            .associate { it.id to it.feedUrl }
        return queue.mapNotNull { ep ->
            feedById[ep.podcastId]?.let {
                EpisodeStateRestore.QueueRef(it, ep.guid, ep.audioUrl)
            }
        }
    }

    suspend fun resolveEpisodeId(podcastId: Long, guid: String, audioUrl: String): Long? =
        guid.takeIf { it.isNotEmpty() }?.let { db.episodeDao().idByGuid(podcastId, it) }
            ?: audioUrl.takeIf { it.isNotEmpty() }
                ?.let { db.episodeDao().idByAudioUrl(podcastId, it) }

    /**
     * Merges restored listening state into existing rows: played wins, the
     * later played-at wins, a local in-progress position is kept, favorites
     * union. One transaction = one list re-render.
     */
    suspend fun applyEpisodeStates(podcastId: Long, entries: List<EpisodeStateRestore.Entry>) {
        if (entries.isEmpty()) return
        db.withTransaction {
            for (e in entries) {
                val id = resolveEpisodeId(podcastId, e.guid, e.audioUrl) ?: continue
                val local = db.episodeDao().get(id) ?: continue
                val played = local.played || e.played
                val position = when {
                    played -> 0L
                    local.positionMs > 0 -> local.positionMs
                    else -> e.positionMs.coerceAtLeast(0)
                }
                db.episodeDao().restoreState(
                    id,
                    played = played,
                    playedAtMs = maxOf(local.playedAtMs, e.playedAtMs),
                    positionMs = position,
                    favorite = local.favorite || e.favorite
                )
                // restored history must not resurface in "New"
                if (played) db.episodeDao().setInboxDismissed(listOf(id), true)
            }
        }
    }

    /** A restored Up Next: replaces an empty queue, otherwise appends what's missing. */
    suspend fun restoreQueue(ids: List<Long>) {
        val current = db.queueDao().queueSnapshot().map { it.id }.toHashSet()
        if (current.isEmpty()) {
            replaceQueue(ids)
        } else {
            appendToQueueLast(ids.filter { it !in current })
        }
    }

    /** Unsubscribed shows that exist only to hold saved one-off episodes. */
    suspend fun savedEpisodeShows(): List<Pair<Podcast, List<Episode>>> =
        db.podcastDao().listAll()
            .filter { !it.subscribed && it.localFolderUri == null }
            .map { it to db.episodeDao().listForPodcast(it.id) }
            .filter { it.second.isNotEmpty() }

    /** Per-show listening totals for the backup, keyed by feed URL. */
    suspend fun exportListenStats(): List<Triple<String, Long, Long>> {
        val feedById = db.podcastDao().listAll().associate { it.id to it.feedUrl }
        return db.listenStatDao().listAll().mapNotNull { stat ->
            feedById[stat.podcastId]?.let { Triple(it, stat.wallMs, stat.contentMs) }
        }
    }

    /** Restore: raises (never adds) so restoring the same file twice can't double-count. */
    suspend fun restoreListenStat(podcastId: Long, wallMs: Long, contentMs: Long) {
        db.listenStatDao().insert(ListenStat(podcastId, 0, 0))
        db.listenStatDao().raiseTo(podcastId, wallMs, contentMs)
    }

    /** Pending restored state for this feed, if a backup restore staged any. */
    private suspend fun applyPendingRestore(podcastId: Long, feedUrl: String) {
        runCatching {
            EpisodeStateRestore.applyFor(appContext, this, podcastId, feedUrl)
        }.onFailure { PlaybackJournal.log("restore-state", "failed pod=$podcastId: $it") }
    }

    /** "Finished" mark used by completion and done-and-delete paths. */
    suspend fun markPlayed(episodeId: Long, source: String = "ui") {
        val wasPlayed = db.episodeDao().get(episodeId)?.played ?: false
        db.episodeDao().setPlayed(episodeId, true, System.currentTimeMillis())
        // finished episodes leave Up Next, same as setPlayed — a completed
        // episode lingering in the queue replays from the top on next start
        db.queueDao().remove(episodeId)
        if (!wasPlayed) ListenStats.addFinishedEpisode(appContext)
        PlaybackJournal.log("played", "$source ep=$episodeId")
    }

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
        transcriptType = transcriptType,
        season = season,
        episodeNumber = episodeNumber,
        episodeType = episodeType,
        persons = persons
    )

    /** Returns the number of genuinely new rows (conflicts are ignored). */
    private suspend fun insertEpisodes(podcastId: Long, feed: ParsedFeed): Int =
        insertEpisodesReturningIds(podcastId, feed).size

    /**
     * Feed insert that survives identity churn. Dedup is keyed on guid
     * (falling back to the enclosure URL at parse time) — but feeds CHANGE
     * both across fetches (rotating tracking prefixes, ad-insertion tokens,
     * CMS migrations). Naive insert-IGNORE then re-creates the same episode
     * as a fresh row with positionMs = 0 / played = false, and a SmartPlay
     * that picks the fresh twin looks exactly like "my episode started
     * over". So: rows whose guid vanished from the feed get REKEYED to the
     * matching parsed item (same enclosure URL, or same title + pubDate)
     * instead of duplicated — keeping position, played state, and download.
     * A sweep then removes progress-less shadow duplicates from before this
     * defense existed. Returns the genuinely-NEW episodes' row ids.
     */
    private suspend fun insertEpisodesReturningIds(
        podcastId: Long,
        feed: ParsedFeed
    ): List<Long> = db.withTransaction {
        val dao = db.episodeDao()
        val existing = dao.listForPodcast(podcastId)
        val knownGuids = existing.mapTo(HashSet()) { it.guid }
        val parsedGuids = feed.episodes.mapTo(HashSet()) { it.guid }
        // progress-carrying rows claim their new identity first, so a
        // resumable row never loses a rekey match to an untouched twin
        val orphaned = existing
            .filter { it.guid !in parsedGuids }
            .sortedWith(
                compareByDescending<Episode> {
                    it.positionMs > 0 || it.played ||
                        it.downloadStatus != Episode.DOWNLOAD_NONE
                }.thenBy { it.id }
            )
            .toMutableList()
        val toInsert = mutableListOf<Episode>()
        for (parsed in feed.episodes) {
            val entity = parsed.toEntity(podcastId)
            if (entity.guid in knownGuids) {
                // known episode: adopt corrected metadata (frozen-forever
                // rows were the single biggest feed-vs-app divergence)
                dao.updateEpisodeMeta(
                    podcastId, entity.guid,
                    title = entity.title.takeIf { it != "(untitled)" }.orEmpty(),
                    description = entity.description,
                    imageUrl = entity.imageUrl,
                    durationMs = entity.durationMs,
                    chapters = entity.chapters
                )
                dao.updateEpisodeExtras(
                    podcastId, entity.guid, entity.season, entity.episodeNumber,
                    entity.episodeType, entity.persons
                )
                continue
            }
            val twin = orphaned.firstOrNull {
                it.audioUrl == entity.audioUrl ||
                    (entity.pubDateMs > 0 && it.pubDateMs == entity.pubDateMs &&
                        it.title == entity.title)
            }
            if (twin != null) {
                orphaned.remove(twin)
                knownGuids += entity.guid
                dao.rekeyEpisode(
                    twin.id, entity.guid, entity.audioUrl,
                    title = entity.title.takeIf { it != "(untitled)" }.orEmpty(),
                    description = entity.description,
                    imageUrl = entity.imageUrl,
                    pubDateMs = entity.pubDateMs,
                    durationMs = entity.durationMs
                )
                PlaybackJournal.log(
                    "rekey", "ep=${twin.id} pod=$podcastId pos=${twin.positionMs}"
                )
            } else if (knownGuids.add(entity.guid)) {
                toInsert += entity
            }
        }
        // sweep BEFORE inserting: a row inserted this refresh must never be
        // deleted after its id was already collected as "new" (phantom
        // queue entries / notifications about episodes that don't exist)
        val shadows = dao.deleteShadowDuplicates(podcastId)
        if (shadows > 0) {
            PlaybackJournal.log("dedup", "pod=$podcastId removed=$shadows")
        }
        val ids = dao.insertAll(toInsert).filter { it != -1L }
        backfillTranscripts(podcastId, feed)
        ids
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val inboxFlow: SharedFlow<List<Episode>> = slidingInboxSince()
        .flatMapLatest { since -> db.episodeDao().observeInbox(since) }
        .shared()

    fun inbox(): SharedFlow<List<Episode>> = inboxFlow

    /** The inbox cutoff, re-emitted as the 14-day window slides forward. */
    private fun slidingInboxSince() = kotlinx.coroutines.flow.flow {
        while (true) {
            emit(inboxSinceMs())
            kotlinx.coroutines.delay(INBOX_WINDOW_STEP_MS)
        }
    }

    /** ALL inbox ids, not just the 300 the list shows — Clear-all uses this. */
    suspend fun inboxAllIds(): List<Long> = db.episodeDao().inboxIds(inboxSinceMs())

    /**
     * Warmed at construction (StepcastApplication.onCreate, well before any
     * screen renders) instead of on first collection. The Home tab's "New
     * episodes" card sits ABOVE the category list; when this was a cold
     * Flow that only started querying once Home first composed, the card
     * consistently landed a beat after everything else on the screen —
     * long enough to shift the category list down right as a tap arrived.
     * Eagerly collecting from app start means the value is normally
     * already resolved by the time any screen asks for it.
     */
    // The window slides: the playback service keeps this process alive for
    // days, and a cutoff computed once at startup let the count drift up
    // with episodes long past 14 days (and disagree with the inbox list).
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val inboxCountFlow: StateFlow<Int> =
        slidingInboxSince()
            .flatMapLatest { since -> db.episodeDao().observeInboxCount(since) }
            .distinctUntilChanged()
            .stateIn(repoScope, SharingStarted.Eagerly, 0)

    fun inboxCount(): StateFlow<Int> = inboxCountFlow

    suspend fun notifyCandidates(afterId: Long): List<NotifyCandidate> =
        db.episodeDao().notifyCandidates(afterId, inboxSinceMs())

    suspend fun maxEpisodeId(): Long = db.episodeDao().maxId()

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
            db.podcastDao().repoint(
                podcastId,
                feedUrl = newFeedUrl,
                title = feed.title.takeIf { it != "(untitled feed)" }.orEmpty(),
                description = feed.description,
                imageUrl = feed.imageUrl,
                author = feed.author,
                lastRefreshed = System.currentTimeMillis()
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

    private fun fetchFeed(feedUrl: String): ParsedFeed =
        fetchFeedConditional(feedUrl, null, null)?.feed
            ?: throw IOException("Unexpected 304 for $feedUrl")

    /** A full fetch: the parsed feed plus what the HTTP layer told us about it. */
    private class FetchedFeed(
        val feed: ParsedFeed,
        val etag: String?,
        val lastModified: String?,
        /** Set when EVERY redirect hop was permanent (301/308). */
        val permanentlyMovedTo: String?
    )

    /** null = 304 Not Modified (only possible when validators were sent). */
    private fun fetchFeedConditional(
        feedUrl: String,
        etag: String?,
        lastModified: String?
    ): FetchedFeed? {
        val request = Request.Builder()
            .url(feedUrl)
            .apply {
                etag?.let { header("If-None-Match", it) }
                lastModified?.let { header("If-Modified-Since", it) }
            }
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 304) return null
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $feedUrl")
            val body = response.body ?: throw IOException("Empty body for $feedUrl")
            val feed = RssParser.parse(body.byteStream())
            // walk the redirect chain: only an all-permanent chain means
            // "the feed moved" — a temporary hop (tracking, CDN) must not
            // rewrite the subscription
            var hop = response.priorResponse
            var allPermanent = hop != null
            while (hop != null) {
                if (hop.code != 301 && hop.code != 308) allPermanent = false
                hop = hop.priorResponse
            }
            val finalUrl = response.request.url.toString()
            return FetchedFeed(
                feed = feed,
                etag = response.header("ETag"),
                lastModified = response.header("Last-Modified"),
                permanentlyMovedTo = finalUrl.takeIf { allPermanent && it != feedUrl }
            )
        }
    }

    /**
     * Publishers move feeds with a permanent redirect or <itunes:new-feed-url>.
     * Following the redirect alone works until the OLD host is shut down —
     * then the subscription dies. Adopt the new URL (never onto a URL
     * another subscription already uses), and for new-feed-url fetch the new
     * location first so a bad hint can't break a working feed.
     */
    private suspend fun adoptMovedFeed(podcast: Podcast, fetched: FetchedFeed): MovedFeedResult {
        var feed = fetched.feed
        var adoptedUrl = fetched.permanentlyMovedTo
        var etag = fetched.etag
        var lastModified = fetched.lastModified
        val hinted = feed.newFeedUrl
        if (hinted != null &&
            normalizedFeedUrl(hinted) != normalizedFeedUrl(adoptedUrl ?: podcast.feedUrl)
        ) {
            runCatching { fetchFeedConditional(hinted, null, null) }.getOrNull()
                ?.takeIf { it.feed.episodes.isNotEmpty() }
                ?.let {
                    feed = it.feed
                    adoptedUrl = it.permanentlyMovedTo ?: hinted
                    etag = it.etag
                    lastModified = it.lastModified
                }
        }
        val target = adoptedUrl
        if (target != null) {
            val owner = podcastIdForFeed(target)
            if (owner == null || owner == podcast.id) {
                db.podcastDao().adoptMovedFeedUrl(podcast.id, target)
                PlaybackJournal.logSchedule(
                    "feed-moved", "${podcast.title}: ${podcast.feedUrl} -> $target"
                )
            }
        }
        return MovedFeedResult(feed, etag, lastModified)
    }

    private class MovedFeedResult(
        val feed: ParsedFeed,
        val etag: String?,
        val lastModified: String?
    )
}

/** One podcast's downloaded-file footprint. */
data class StorageUsage(val podcast: Podcast, val episodes: Int, val bytes: Long)

/** See [PodcastRepository.episodeStartSettings]. speed 0 = follow the global. */
data class EpisodeStartSettings(
    val episode: Episode?,
    val introMs: Long,
    val speed: Float
)

/** How far back the New-episodes inbox reaches. */
private const val INBOX_WINDOW_MS = 14L * 86_400_000

/** How often the inbox count's sliding window moves forward. */
private const val INBOX_WINDOW_STEP_MS = 15L * 60_000
