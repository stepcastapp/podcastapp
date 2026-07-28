package com.stepcast.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastDao {
    @Query("SELECT * FROM podcasts ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<Podcast>>

    @Query("SELECT * FROM podcasts ORDER BY title COLLATE NOCASE")
    suspend fun listAll(): List<Podcast>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    fun observe(id: Long): Flow<Podcast?>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    suspend fun get(id: Long): Podcast?

    @Query("SELECT * FROM podcasts WHERE feedUrl = :feedUrl")
    suspend fun getByFeedUrl(feedUrl: String): Podcast?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(podcast: Podcast): Long

    @Update
    suspend fun update(podcast: Podcast)

    /**
     * Feed-derived columns ONLY. Never write back a whole row that was
     * read before a multi-second network fetch — any setting the user
     * changed during the fetch (retention, schedule rule, speed, …) would
     * be silently reverted. Empty strings keep the existing value, so a
     * partially-parsed feed can't blank a good title or description.
     */
    @Query(
        "UPDATE podcasts SET " +
            "title = CASE WHEN :title = '' THEN title ELSE :title END, " +
            "description = CASE WHEN :description = '' THEN description ELSE :description END, " +
            "imageUrl = COALESCE(:imageUrl, imageUrl), " +
            "author = CASE WHEN :author = '' THEN author ELSE :author END, " +
            "lastRefreshed = :lastRefreshed, consecutiveFailures = 0 " +
            "WHERE id = :id"
    )
    suspend fun updateFromFeed(
        id: Long,
        title: String,
        description: String,
        imageUrl: String?,
        author: String,
        lastRefreshed: Long
    )

    /** Local-folder rescan bookkeeping — same narrow-write rule as above. */
    @Query(
        "UPDATE podcasts SET lastRefreshed = :lastRefreshed, " +
            "imageUrl = COALESCE(imageUrl, :fallbackArt) WHERE id = :id"
    )
    suspend fun updateLocalScan(id: Long, lastRefreshed: Long, fallbackArt: String?)

    /** Dead-feed repair: repoint + adopt the new feed's metadata, narrowly. */
    @Query(
        "UPDATE podcasts SET feedUrl = :feedUrl, " +
            "title = CASE WHEN :title = '' THEN title ELSE :title END, " +
            "description = CASE WHEN :description = '' THEN description ELSE :description END, " +
            "imageUrl = COALESCE(:imageUrl, imageUrl), " +
            "author = CASE WHEN :author = '' THEN author ELSE :author END, " +
            "lastRefreshed = :lastRefreshed, consecutiveFailures = 0 " +
            "WHERE id = :id"
    )
    suspend fun repoint(
        id: Long,
        feedUrl: String,
        title: String,
        description: String,
        imageUrl: String?,
        author: String,
        lastRefreshed: Long
    )

    @Query("UPDATE podcasts SET introSkipSec = :introSec, outroSkipSec = :outroSec WHERE id = :id")
    suspend fun updateSkips(id: Long, introSec: Int, outroSec: Int)

    @Query("UPDATE podcasts SET adJumpSec = :sec WHERE id = :id")
    suspend fun updateAdJump(id: Long, sec: Int)

    @Query("UPDATE podcasts SET scheduleMode = :mode, scheduleParam = :param WHERE id = :id")
    suspend fun updateScheduleRule(id: Long, mode: Int, param: Int)

    @Query("UPDATE podcasts SET playbackSpeed = :speed WHERE id = :id")
    suspend fun updatePlaybackSpeed(id: Long, speed: Float)

    @Query("UPDATE podcasts SET folder = :folder WHERE id = :id")
    suspend fun updateFolder(id: Long, folder: String?)

    @Query("UPDATE podcasts SET keepDownloads = :keep, maxAgeDays = :maxAge WHERE id = :id")
    suspend fun updateRetention(id: Long, keep: Int, maxAge: Int)

    @Query("UPDATE podcasts SET folder = :newName WHERE folder = :oldName")
    suspend fun renameFolder(oldName: String, newName: String)

    @Query("UPDATE podcasts SET folder = NULL WHERE folder = :name")
    suspend fun clearFolder(name: String)

    @Query("UPDATE podcasts SET folder = NULL WHERE folder = 'null'")
    suspend fun repairNullFolders()

    @Query(
        "UPDATE podcasts SET episodeCap = :cap, sortOldestFirst = :oldestFirst, " +
            "autoQueue = :autoQueue WHERE id = :id"
    )
    suspend fun updateListPrefs(id: Long, cap: Int, oldestFirst: Boolean, autoQueue: Boolean)

    @Query("UPDATE podcasts SET consecutiveFailures = consecutiveFailures + 1 WHERE id = :id")
    suspend fun incrementFailures(id: Long)

    @Query("UPDATE podcasts SET consecutiveFailures = 0 WHERE id = :id")
    suspend fun resetFailures(id: Long)

    @Query("UPDATE podcasts SET imageUrl = NULL WHERE imageUrl = 'null'")
    suspend fun repairNullImageUrls()

    @Query("DELETE FROM podcasts WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY pubDateMs DESC")
    fun observeForPodcast(podcastId: Long): Flow<List<Episode>>

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId")
    suspend fun listForPodcast(podcastId: Long): List<Episode>

    @Query(
        "SELECT e.* FROM episodes e INNER JOIN podcast_categories pc " +
            "ON e.podcastId = pc.podcastId " +
            "WHERE pc.category = :folder ORDER BY e.pubDateMs DESC LIMIT 500"
    )
    fun observeForFolder(folder: String): Flow<List<Episode>>

    /**
     * The 500-row window used to be cut newest-first ALWAYS, so an
     * oldest-first rule on a big scope returned the oldest of the newest
     * 500 — the opposite of the ask. The window now follows the rule's
     * direction; other sorts (duration, shuffle, name) still apply in
     * Kotlin over their newest-500 window.
     */
    @Query(
        "SELECT e.* FROM episodes e " +
            "WHERE (:folder IS NULL OR EXISTS (" +
            "SELECT 1 FROM podcast_categories pc " +
            "WHERE pc.podcastId = e.podcastId AND pc.category = :folder)) " +
            "AND (:podcastId IS NULL OR e.podcastId = :podcastId) " +
            "AND (e.played = 0 OR :includePlayed = 1) " +
            "AND (e.downloadStatus = 2 OR :downloadedOnly = 0) " +
            "ORDER BY CASE WHEN :oldestFirst = 1 THEN e.pubDateMs END ASC, " +
            "CASE WHEN :oldestFirst = 0 THEN e.pubDateMs END DESC LIMIT 500"
    )
    suspend fun selectSmartPlayCandidates(
        folder: String?,
        podcastId: Long?,
        includePlayed: Int,
        downloadedOnly: Int,
        oldestFirst: Int
    ): List<Episode>

    @Query("DELETE FROM episodes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM episodes ORDER BY pubDateMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<Episode>>

    // ---- inbox: recent, unplayed, not swiped away, not local files -------
    // Floor per show = MAX(window, when the user subscribed): subscribing
    // to a daily show must not dump two weeks of back catalog into "New".
    @Query(
        "SELECT * FROM episodes WHERE played = 0 AND inboxDismissed = 0 " +
            "AND pubDateMs >= MAX(:sinceMs, COALESCE((SELECT p.subscribedAt " +
            "FROM podcasts p WHERE p.id = episodes.podcastId), 0)) " +
            "AND audioUrl NOT LIKE 'content:%' " +
            "ORDER BY pubDateMs DESC LIMIT 300"
    )
    fun observeInbox(sinceMs: Long): Flow<List<Episode>>

    @Query(
        "SELECT COUNT(*) FROM episodes WHERE played = 0 AND inboxDismissed = 0 " +
            "AND pubDateMs >= MAX(:sinceMs, COALESCE((SELECT p.subscribedAt " +
            "FROM podcasts p WHERE p.id = episodes.podcastId), 0)) " +
            "AND audioUrl NOT LIKE 'content:%'"
    )
    fun observeInboxCount(sinceMs: Long): Flow<Int>

    @Query("UPDATE episodes SET inboxDismissed = :dismissed WHERE id IN (:ids)")
    suspend fun setInboxDismissed(ids: List<Long>, dismissed: Boolean)

    @Query(
        "UPDATE episodes SET transcriptUrl = :url, transcriptType = :type " +
            "WHERE podcastId = :podcastId AND guid = :guid AND transcriptUrl IS NULL"
    )
    suspend fun backfillTranscript(podcastId: Long, guid: String, url: String, type: String?)

    @Query("UPDATE episodes SET imageUrl = :imageUrl WHERE id = :id AND imageUrl IS NULL")
    suspend fun updateImageUrlIfMissing(id: Long, imageUrl: String)

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun get(id: Long): Episode?

    @Query("SELECT * FROM episodes WHERE audioUrl = :audioUrl LIMIT 1")
    suspend fun getByAudioUrl(audioUrl: String): Episode?

    @Query("SELECT id FROM episodes WHERE downloadStatus = 1")
    suspend fun downloadingIds(): List<Long>

    /**
     * In-flight (1) and failed (3) downloads, running first. The old
     * LIMIT 100 hid everything past 100 during mass imports — failed rows
     * sort last, so they vanished first. 2000 comfortably covers a full
     * library import while still bounding a runaway.
     */
    @Query(
        "SELECT * FROM episodes WHERE downloadStatus IN (1, 3) " +
            "ORDER BY downloadStatus ASC, pubDateMs DESC LIMIT 2000"
    )
    fun observeDownloadActivity(): Flow<List<Episode>>

    /** Next unplayed episode of the same podcast, honoring its sort order. */
    @Query(
        "SELECT * FROM episodes WHERE podcastId = :podcastId AND played = 0 " +
            "AND id != :excludeId " +
            "ORDER BY CASE WHEN :oldestFirst = 1 THEN pubDateMs END ASC, " +
            "CASE WHEN :oldestFirst = 0 THEN pubDateMs END DESC LIMIT 1"
    )
    suspend fun nextUnplayedInPodcast(
        podcastId: Long,
        excludeId: Long,
        oldestFirst: Int
    ): Episode?

    /** Case-insensitive title search; caller escapes %, _ and \. */
    @Query(
        "SELECT * FROM episodes WHERE title LIKE '%' || :query || '%' ESCAPE '\\' " +
            "ORDER BY pubDateMs DESC LIMIT 60"
    )
    suspend fun searchByTitle(query: String): List<Episode>

    /** Release-pattern inference input (ScheduleEngine's Automatic mode). */
    @Query(
        "SELECT pubDateMs FROM episodes WHERE podcastId = :podcastId " +
            "AND pubDateMs > 0 ORDER BY pubDateMs DESC LIMIT 20"
    )
    suspend fun recentPubDates(podcastId: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(episodes: List<Episode>): List<Long>

    @Query("UPDATE episodes SET positionMs = :positionMs WHERE id = :id")
    suspend fun updatePosition(id: Long, positionMs: Long)

    @Query("UPDATE episodes SET durationMs = :durationMs WHERE id = :id AND durationMs <= 0")
    suspend fun updateDurationIfUnknown(id: Long, durationMs: Long)

    /**
     * The player's duration for the loaded file is authoritative — it
     * OVERWRITES a lying feed value, not just fills a missing one (the 2s
     * band avoids a dirty write, and Room invalidation, on every save).
     */
    @Query(
        "UPDATE episodes SET durationMs = :durationMs WHERE id = :id " +
            "AND ABS(durationMs - :durationMs) > 2000"
    )
    suspend fun correctDuration(id: Long, durationMs: Long)

    /**
     * Adopts a feed item's new identity for an existing row. Feeds churn
     * guids and enclosure URLs (tracking prefixes, ad-insertion tokens);
     * without this the same episode re-inserts as a fresh row with
     * positionMs = 0 — which reads as "my episode lost its place". The
     * feed item's metadata comes along too (the row may have been matched
     * by URL with a corrected title/date). Player-corrected durations and
     * cached chapters are kept.
     */
    @Query(
        "UPDATE episodes SET guid = :guid, audioUrl = :audioUrl, " +
            "title = CASE WHEN :title = '' THEN title ELSE :title END, " +
            "description = CASE WHEN :description = '' THEN description ELSE :description END, " +
            "imageUrl = COALESCE(:imageUrl, imageUrl), " +
            "pubDateMs = CASE WHEN :pubDateMs > 0 THEN :pubDateMs ELSE pubDateMs END, " +
            "durationMs = CASE WHEN durationMs <= 0 THEN :durationMs ELSE durationMs END " +
            "WHERE id = :id"
    )
    suspend fun rekeyEpisode(
        id: Long,
        guid: String,
        audioUrl: String,
        title: String,
        description: String,
        imageUrl: String?,
        pubDateMs: Long,
        durationMs: Long
    )

    /**
     * Refresh-time metadata adoption for a KNOWN guid: publishers fix
     * typos, add show notes, add durations and chapters to old episodes —
     * rows used to freeze at first insert forever. Playback-owned columns
     * (position, played, downloads) are never touched; a player-corrected
     * duration and already-cached chapters win over the feed. The WHERE
     * tail skips the write (and Room invalidation) when nothing changed.
     */
    @Query(
        "UPDATE episodes SET " +
            "title = CASE WHEN :title = '' THEN title ELSE :title END, " +
            "description = CASE WHEN :description = '' THEN description ELSE :description END, " +
            "imageUrl = COALESCE(:imageUrl, imageUrl), " +
            "durationMs = CASE WHEN durationMs <= 0 THEN :durationMs ELSE durationMs END, " +
            "chapters = COALESCE(chapters, :chapters) " +
            "WHERE podcastId = :podcastId AND guid = :guid AND (" +
            "(:title != '' AND title != :title) OR " +
            "(:description != '' AND description != :description) OR " +
            "(imageUrl IS NULL AND :imageUrl IS NOT NULL) OR " +
            "(durationMs <= 0 AND :durationMs > 0) OR " +
            "(chapters IS NULL AND :chapters IS NOT NULL))"
    )
    suspend fun updateEpisodeMeta(
        podcastId: Long,
        guid: String,
        title: String,
        description: String,
        imageUrl: String?,
        durationMs: Long,
        chapters: String?
    )

    /**
     * Deletes progress-less duplicate rows created by past guid churn: same
     * title + pubDate as another row that either carries progress (position,
     * played mark, download) or is simply older. Two guards keep it from
     * eating REAL episodes: the twin must be corroborated by a matching
     * enclosure URL or a matching known duration (serials/multi-part drops
     * legitimately share title + timestamp), and it runs BEFORE the
     * refresh insert so a just-inserted row can't be deleted after its id
     * was already reported as new. Downloads and queued rows are never
     * touched. Returns how many rows were removed.
     */
    @Query(
        "DELETE FROM episodes WHERE podcastId = :podcastId " +
            "AND played = 0 AND positionMs = 0 AND downloadStatus = 0 " +
            "AND pubDateMs > 0 " +
            "AND id NOT IN (SELECT episodeId FROM queue) " +
            "AND EXISTS (SELECT 1 FROM episodes e2 WHERE " +
            "e2.podcastId = episodes.podcastId AND e2.id != episodes.id " +
            "AND e2.title = episodes.title AND e2.pubDateMs = episodes.pubDateMs " +
            "AND (e2.audioUrl = episodes.audioUrl " +
            "OR (e2.durationMs > 0 AND e2.durationMs = episodes.durationMs)) " +
            "AND (e2.positionMs > 0 OR e2.played = 1 OR e2.downloadStatus != 0 " +
            "OR e2.id < episodes.id))"
    )
    suspend fun deleteShadowDuplicates(podcastId: Long): Int

    @Query(
        "UPDATE episodes SET played = :played, positionMs = 0, " +
            "playedAtMs = :playedAtMs WHERE id = :id"
    )
    suspend fun setPlayed(id: Long, played: Boolean, playedAtMs: Long)

    @Query(
        "UPDATE episodes SET played = 1, positionMs = 0, playedAtMs = :playedAtMs " +
            "WHERE podcastId = :podcastId AND played = 0"
    )
    suspend fun markAllPlayed(podcastId: Long, playedAtMs: Long)

    @Query(
        "SELECT * FROM episodes WHERE played = 1 AND playedAtMs > 0 " +
            "ORDER BY playedAtMs DESC LIMIT 200"
    )
    fun observeHistory(): Flow<List<Episode>>

    @Query("UPDATE episodes SET downloadStatus = :status, downloadProgress = 0 WHERE id = :id")
    suspend fun setDownloadStatus(id: Long, status: Int)

    @Query("UPDATE episodes SET downloadAttempts = downloadAttempts + 1 WHERE id = :id")
    suspend fun incrementDownloadAttempts(id: Long)

    @Query("UPDATE episodes SET downloadAttempts = 0 WHERE id = :id")
    suspend fun resetDownloadAttempts(id: Long)

    /** Bulk-import backlog suppression: mark a podcast's current episodes (in)eligible for auto-download. */
    @Query("UPDATE episodes SET autoDownloadEligible = :eligible WHERE podcastId = :podcastId")
    suspend fun setAutoDownloadEligibleForPodcast(podcastId: Long, eligible: Boolean)

    /** Dismiss: leave the pane AND stop auto-download from re-adding it. */
    @Query(
        "UPDATE episodes SET downloadStatus = 0, downloadProgress = 0, " +
            "downloadAttempts = MAX(downloadAttempts, :minAttempts) WHERE id = :id"
    )
    suspend fun dismissDownload(id: Long, minAttempts: Int)

    @Query("UPDATE episodes SET downloadProgress = :pct WHERE id = :id")
    suspend fun setDownloadProgress(id: Long, pct: Int)

    @Query(
        "UPDATE episodes SET downloadStatus = 2, downloadProgress = 100, " +
            "localFilePath = :path WHERE id = :id"
    )
    suspend fun setDownloaded(id: Long, path: String)

    @Query(
        "UPDATE episodes SET downloadStatus = 0, downloadProgress = 0, " +
            "localFilePath = NULL WHERE id = :id"
    )
    suspend fun clearDownload(id: Long)

    @Query("UPDATE episodes SET chapters = :chapters WHERE id = :id")
    suspend fun setChapters(id: Long, chapters: String?)

    @Query("DELETE FROM episodes WHERE podcastId = :podcastId")
    suspend fun deleteForPodcast(podcastId: Long)

    @Query("SELECT COUNT(*) FROM episodes")
    suspend fun countAll(): Int

    /** True feed totals for the podcast header — the visible list is paged. */
    @Query(
        "SELECT COUNT(*) AS total, " +
            "COALESCE(SUM(CASE WHEN played = 0 THEN 1 ELSE 0 END), 0) AS unplayed " +
            "FROM episodes WHERE podcastId = :podcastId"
    )
    fun observeCounts(podcastId: Long): Flow<EpisodeCounts>

    @Query(
        "SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY " +
            "CASE WHEN :oldestFirst = 1 THEN pubDateMs END ASC, " +
            "CASE WHEN :oldestFirst = 0 THEN pubDateMs END DESC LIMIT :limit"
    )
    fun observeForPodcastPaged(
        podcastId: Long,
        oldestFirst: Int,
        limit: Int
    ): Flow<List<Episode>>

    /**
     * Per-feed list cap: prune old rows, sparing downloads, the queue,
     * in-progress episodes, and played history ("keep the newest N" reads
     * like a display setting — it must never eat listening state). The id
     * tiebreaker keeps the survivor set deterministic for equal pubDates.
     */
    @Query(
        "DELETE FROM episodes WHERE podcastId = :podcastId AND downloadStatus = 0 " +
            "AND positionMs = 0 AND played = 0 " +
            "AND id NOT IN (SELECT episodeId FROM queue) " +
            "AND id NOT IN (SELECT id FROM episodes WHERE podcastId = :podcastId " +
            "ORDER BY pubDateMs DESC, id DESC LIMIT :cap)"
    )
    suspend fun pruneBeyondCap(podcastId: Long, cap: Int): Int

    @Query(
        "UPDATE episodes SET played = 1, positionMs = 0, playedAtMs = :playedAtMs " +
            "WHERE podcastId = :podcastId AND played = 0 AND pubDateMs < :cutoffMs"
    )
    suspend fun markPlayedOlderThan(podcastId: Long, cutoffMs: Long, playedAtMs: Long)

    @Query(
        "UPDATE episodes SET played = 1, positionMs = 0, playedAtMs = :playedAtMs " +
            "WHERE played = 0 AND pubDateMs < :cutoffMs AND podcastId IN " +
            "(SELECT podcastId FROM podcast_categories WHERE category = :folder)"
    )
    suspend fun markPlayedOlderThanInFolder(folder: String, cutoffMs: Long, playedAtMs: Long)
}

@Dao
interface ListenStatDao {
    @Query(
        "UPDATE listen_stats SET wallMs = wallMs + :wallMs, " +
            "contentMs = contentMs + :contentMs WHERE podcastId = :podcastId"
    )
    suspend fun bump(podcastId: Long, wallMs: Long, contentMs: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(stat: ListenStat): Long

    @Query("SELECT * FROM listen_stats ORDER BY wallMs DESC LIMIT :limit")
    suspend fun top(limit: Int): List<ListenStat>

    @Query("DELETE FROM listen_stats")
    suspend fun clear()

    @Query("DELETE FROM listen_stats WHERE podcastId = :podcastId")
    suspend fun deleteFor(podcastId: Long)
}

@Dao
interface QueueDao {
    @Query(
        "SELECT e.* FROM episodes e INNER JOIN queue q ON e.id = q.episodeId " +
            "ORDER BY q.position"
    )
    fun observeQueue(): Flow<List<Episode>>

    @Query(
        "SELECT e.* FROM episodes e INNER JOIN queue q ON e.id = q.episodeId " +
            "ORDER BY q.position"
    )
    suspend fun queueSnapshot(): List<Episode>

    @Query("SELECT MAX(position) FROM queue")
    suspend fun maxPosition(): Int?

    @Query("SELECT MIN(position) FROM queue")
    suspend fun minPosition(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: QueueItem)

    @Query("UPDATE queue SET position = :position WHERE episodeId = :episodeId")
    suspend fun setPosition(episodeId: Long, position: Int)

    @Query("DELETE FROM queue WHERE episodeId = :episodeId")
    suspend fun remove(episodeId: Long)

    @Query(
        "DELETE FROM queue WHERE episodeId IN " +
            "(SELECT id FROM episodes WHERE podcastId = :podcastId)"
    )
    suspend fun removeForPodcast(podcastId: Long)

    @Query("DELETE FROM queue")
    suspend fun clear()

    @Query(
        "DELETE FROM queue WHERE episodeId IN " +
            "(SELECT id FROM episodes WHERE played = 1)"
    )
    suspend fun removePlayed()

    // scoped variants: a per-show/per-category bulk cleanup must not clear
    // OTHER shows' deliberately-queued played episodes from Up Next
    @Query(
        "DELETE FROM queue WHERE episodeId IN " +
            "(SELECT id FROM episodes WHERE played = 1 AND podcastId = :podcastId)"
    )
    suspend fun removePlayedForPodcast(podcastId: Long)

    @Query(
        "DELETE FROM queue WHERE episodeId IN " +
            "(SELECT e.id FROM episodes e INNER JOIN podcast_categories pc " +
            "ON pc.podcastId = e.podcastId " +
            "WHERE e.played = 1 AND pc.category = :folder)"
    )
    suspend fun removePlayedForFolder(folder: String)
}

@Dao
interface SmartPlayDao {
    @Query("SELECT * FROM smartplays ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeAll(): Flow<List<SmartPlay>>

    @Query("SELECT * FROM smartplays ORDER BY sortOrder, name COLLATE NOCASE")
    suspend fun listAll(): List<SmartPlay>

    @Query("UPDATE smartplays SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSort(id: Long, sortOrder: Int)

    @Query("SELECT * FROM smartplays WHERE id = :id")
    fun observe(id: Long): Flow<SmartPlay?>

    @Query("SELECT * FROM smartplays WHERE id = :id")
    suspend fun get(id: Long): SmartPlay?

    @Query("UPDATE smartplays SET continuous = :continuous WHERE id = :id")
    suspend fun setContinuous(id: Long, continuous: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(smartPlay: SmartPlay): Long

    @Query("UPDATE smartplays SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM smartplays WHERE id = :id")
    suspend fun delete(id: Long)

    // ---- entries ----

    @Query("SELECT * FROM smartplay_entries WHERE smartPlayId = :smartPlayId ORDER BY sortOrder")
    fun observeEntries(smartPlayId: Long): Flow<List<SmartPlayEntry>>

    @Query("SELECT * FROM smartplay_entries WHERE smartPlayId = :smartPlayId ORDER BY sortOrder")
    suspend fun entriesFor(smartPlayId: Long): List<SmartPlayEntry>

    @Query("SELECT MAX(sortOrder) FROM smartplay_entries WHERE smartPlayId = :smartPlayId")
    suspend fun maxEntrySort(smartPlayId: Long): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntry(entry: SmartPlayEntry): Long

    @Query("UPDATE smartplay_entries SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setEntrySort(id: Long, sortOrder: Int)

    @Query("DELETE FROM smartplay_entries WHERE id = :id")
    suspend fun deleteEntry(id: Long)

    @Query("DELETE FROM smartplay_entries WHERE smartPlayId = :smartPlayId")
    suspend fun deleteEntriesFor(smartPlayId: Long)

    @Query("UPDATE smartplay_entries SET folder = :newName WHERE folder = :oldName")
    suspend fun renameEntryFolder(oldName: String, newName: String)

    @Query("DELETE FROM smartplay_entries WHERE folder = :name")
    suspend fun deleteEntriesForFolder(name: String)

    @Query("UPDATE smartplay_entries SET folder = NULL WHERE folder = 'null'")
    suspend fun repairNullFolders()
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sortOrder")
    fun observeAll(): Flow<List<CategoryMeta>>

    @Query("SELECT * FROM categories ORDER BY sortOrder")
    suspend fun listAll(): List<CategoryMeta>

    @Query("SELECT * FROM categories WHERE name = :name")
    suspend fun get(name: String): CategoryMeta?

    @Query("SELECT MAX(sortOrder) FROM categories")
    suspend fun maxSort(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: CategoryMeta)

    @Query("UPDATE categories SET sortOrder = :sortOrder WHERE name = :name")
    suspend fun setSort(name: String, sortOrder: Int)

    @Query("DELETE FROM categories WHERE name = :name")
    suspend fun delete(name: String)
}

/** Projection for [EpisodeDao.observeCounts]. */
data class EpisodeCounts(val total: Int, val unplayed: Int)

@Dao
interface PodcastCategoryDao {
    @Query("SELECT * FROM podcast_categories")
    fun observeAll(): Flow<List<PodcastCategory>>

    @Query("SELECT * FROM podcast_categories")
    suspend fun listAll(): List<PodcastCategory>

    @Query(
        "SELECT category FROM podcast_categories WHERE podcastId = :podcastId " +
            "ORDER BY category COLLATE NOCASE"
    )
    suspend fun categoriesFor(podcastId: Long): List<String>

    @Query("SELECT podcastId FROM podcast_categories WHERE category = :category")
    suspend fun memberIds(category: String): List<Long>

    @Query("SELECT COUNT(*) FROM podcast_categories WHERE category = :category")
    suspend fun memberCount(category: String): Int

    @Query(
        "SELECT DISTINCT category FROM podcast_categories " +
            "ORDER BY category COLLATE NOCASE"
    )
    suspend fun names(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(entry: PodcastCategory)

    @Query(
        "DELETE FROM podcast_categories " +
            "WHERE podcastId = :podcastId AND category = :category"
    )
    suspend fun remove(podcastId: Long, category: String)

    @Query("DELETE FROM podcast_categories WHERE podcastId = :podcastId")
    suspend fun removeAllFor(podcastId: Long)

    /** OR REPLACE: a podcast already in both categories just merges. */
    @Query(
        "UPDATE OR REPLACE podcast_categories SET category = :newName " +
            "WHERE category = :oldName"
    )
    suspend fun renameCategory(oldName: String, newName: String)

    @Query("DELETE FROM podcast_categories WHERE category = :category")
    suspend fun deleteCategory(category: String)
}
