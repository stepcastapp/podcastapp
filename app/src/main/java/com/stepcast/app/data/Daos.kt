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

    @Query("UPDATE podcasts SET introSkipSec = :introSec, outroSkipSec = :outroSec WHERE id = :id")
    suspend fun updateSkips(id: Long, introSec: Int, outroSec: Int)

    @Query("UPDATE podcasts SET adJumpSec = :sec WHERE id = :id")
    suspend fun updateAdJump(id: Long, sec: Int)

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

    @Query(
        "SELECT e.* FROM episodes e " +
            "WHERE (:folder IS NULL OR EXISTS (" +
            "SELECT 1 FROM podcast_categories pc " +
            "WHERE pc.podcastId = e.podcastId AND pc.category = :folder)) " +
            "AND (:podcastId IS NULL OR e.podcastId = :podcastId) " +
            "AND (e.played = 0 OR :includePlayed = 1) " +
            "AND (e.downloadStatus = 2 OR :downloadedOnly = 0) " +
            "ORDER BY e.pubDateMs DESC LIMIT 500"
    )
    suspend fun selectSmartPlayCandidates(
        folder: String?,
        podcastId: Long?,
        includePlayed: Int,
        downloadedOnly: Int
    ): List<Episode>

    @Query("DELETE FROM episodes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM episodes ORDER BY pubDateMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<Episode>>

    // ---- inbox: recent, unplayed, not swiped away, not local files -------
    @Query(
        "SELECT * FROM episodes WHERE played = 0 AND inboxDismissed = 0 " +
            "AND pubDateMs >= :sinceMs AND audioUrl NOT LIKE 'content:%' " +
            "ORDER BY pubDateMs DESC LIMIT 300"
    )
    fun observeInbox(sinceMs: Long): Flow<List<Episode>>

    @Query(
        "SELECT COUNT(*) FROM episodes WHERE played = 0 AND inboxDismissed = 0 " +
            "AND pubDateMs >= :sinceMs AND audioUrl NOT LIKE 'content:%'"
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

    /** Case-insensitive title search across the whole library. */
    @Query(
        "SELECT * FROM episodes WHERE title LIKE '%' || :query || '%' " +
            "ORDER BY pubDateMs DESC LIMIT 60"
    )
    suspend fun searchByTitle(query: String): List<Episode>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(episodes: List<Episode>): List<Long>

    @Query("UPDATE episodes SET positionMs = :positionMs WHERE id = :id")
    suspend fun updatePosition(id: Long, positionMs: Long)

    @Query("UPDATE episodes SET durationMs = :durationMs WHERE id = :id AND durationMs <= 0")
    suspend fun updateDurationIfUnknown(id: Long, durationMs: Long)

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

    /** Per-feed list cap: prune old rows, sparing downloads and the queue. */
    @Query(
        "DELETE FROM episodes WHERE podcastId = :podcastId AND downloadStatus = 0 " +
            "AND id NOT IN (SELECT episodeId FROM queue) " +
            "AND id NOT IN (SELECT id FROM episodes WHERE podcastId = :podcastId " +
            "ORDER BY pubDateMs DESC LIMIT :cap)"
    )
    suspend fun pruneBeyondCap(podcastId: Long, cap: Int)

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
