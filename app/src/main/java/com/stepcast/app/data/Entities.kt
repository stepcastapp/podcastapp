package com.stepcast.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "podcasts",
    indices = [Index(value = ["feedUrl"], unique = true)]
)
data class Podcast(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedUrl: String,
    val title: String,
    val description: String = "",
    val imageUrl: String? = null,
    val author: String = "",
    /** Per-feed auto-skip, in seconds. 0 = disabled. */
    val introSkipSec: Int = 0,
    val outroSkipSec: Int = 0,
    /**
     * Per-feed manual "ad jump": a second forward button sized for this
     * show's mid-roll ad breaks (player + media notification). 0 = hidden.
     */
    val adJumpSec: Int = 0,
    /** Per-feed playback speed. 0 = follow the global/manual speed. */
    val playbackSpeed: Float = 0f,
    /** Folder (category) this podcast belongs to; null/empty = ungrouped. */
    val folder: String? = null,
    /** Auto-download the newest N unplayed episodes; 0 = off. */
    val keepDownloads: Int = 2,
    /** Delete downloads whose episode is older than this; 0 = never. */
    val maxAgeDays: Int = 0,
    /** Keep only the newest N episodes listed; 0 = unlimited. */
    val episodeCap: Int = 0,
    /** Serial shows: list episodes oldest-first. */
    val sortOldestFirst: Boolean = false,
    /** New episodes are appended to Up Next automatically. */
    val autoQueue: Boolean = false,
    /** Refreshes failed in a row; the UI badges feeds at 3+. */
    val consecutiveFailures: Int = 0,
    /**
     * When set, this is a virtual feed: a SAF tree URI of a local folder
     * whose audio files are the episodes (refresh = rescan). feedUrl holds
     * the same URI so uniqueness still works.
     */
    val localFolderUri: String? = null,
    val subscribedAt: Long = System.currentTimeMillis(),
    val lastRefreshed: Long = 0
)

@Entity(
    tableName = "episodes",
    indices = [
        Index(value = ["podcastId", "guid"], unique = true),
        Index(value = ["podcastId", "pubDateMs"])
    ]
)
data class Episode(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val podcastId: Long,
    val guid: String,
    val title: String,
    val description: String = "",
    val audioUrl: String,
    val imageUrl: String? = null,
    val pubDateMs: Long = 0,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val played: Boolean = false,
    /** When the episode was (last) marked played; 0 = never. */
    val playedAtMs: Long = 0,
    val downloadStatus: Int = DOWNLOAD_NONE,
    val downloadProgress: Int = 0,
    /**
     * Terminal download failures so far. Auto-download rules give up at
     * [MAX_AUTO_DOWNLOAD_ATTEMPTS] so a dead enclosure doesn't reappear
     * on every refresh; manual retries are always allowed and a success
     * resets the count.
     */
    val downloadAttempts: Int = 0,
    val localFilePath: String? = null,
    /** See [Chapters] for the storage format. */
    val chapters: String? = null,
    /** Podcasting 2.0 transcript (<podcast:transcript>); null = none. */
    val transcriptUrl: String? = null,
    val transcriptType: String? = null,
    /** Swiped out of the New-episodes inbox; playing/marking played also clears it. */
    val inboxDismissed: Boolean = false
) {
    val progressFraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    val isDownloaded: Boolean get() = downloadStatus == DOWNLOAD_DONE && localFilePath != null
    val isDownloading: Boolean get() = downloadStatus == DOWNLOAD_RUNNING

    companion object {
        const val DOWNLOAD_NONE = 0
        const val DOWNLOAD_RUNNING = 1
        const val DOWNLOAD_DONE = 2
        const val DOWNLOAD_FAILED = 3
        const val MAX_AUTO_DOWNLOAD_ATTEMPTS = 3
    }
}

/** Per-category settings: manual ordering and refresh cadence. */
@Entity(tableName = "categories")
data class CategoryMeta(
    @PrimaryKey val name: String,
    val sortOrder: Int = 0,
    /** Refresh this category every N hours; 0 = app default. */
    val refreshHours: Int = 0,
    /**
     * Reference time of day (minutes after local midnight) the refresh
     * cycle anchors to — e.g. 300 + every 6h refreshes at 5:00, 11:00,
     * 17:00, 23:00. -1 = plain interval since the last refresh.
     */
    val anchorMinutes: Int = -1
)

/**
 * Category membership — a podcast can belong to any number of categories.
 * Source of truth for grouping; [Podcast.folder] is kept synced to the
 * first membership purely as a legacy/primary fallback.
 */
@Entity(
    tableName = "podcast_categories",
    primaryKeys = ["podcastId", "category"],
    indices = [Index(value = ["category"])]
)
data class PodcastCategory(
    val podcastId: Long,
    val category: String
)

/** Up-next queue membership. Lower position plays first. */
@Entity(tableName = "queue")
data class QueueItem(
    @PrimaryKey val episodeId: Long,
    val position: Int
)

/**
 * A rule-based playlist: an ORDERED list of [SmartPlayEntry] rules. Playing
 * one walks the rules in order, appending each rule's episodes (deduped
 * against everything already picked) into the up-next queue.
 */
@Entity(tableName = "smartplays")
data class SmartPlay(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Position in the Up Next strip; user-rearrangeable. */
    val sortOrder: Int = 0,
    /**
     * Station mode: while this SmartPlay is the active station, the service
     * re-evaluates its rules and refills the queue whenever it runs low —
     * press play once and it keeps going.
     */
    val continuous: Boolean = false
)

/**
 * One rule inside a SmartPlay. Scope is a specific podcast, a folder, or
 * (both null) every podcast. maxTracks 0 = no limit.
 */
@Entity(
    tableName = "smartplay_entries",
    indices = [Index(value = ["smartPlayId", "sortOrder"])]
)
data class SmartPlayEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val smartPlayId: Long,
    val sortOrder: Int = 0,
    val podcastId: Long? = null,
    val folder: String? = null,
    val maxTracks: Int = 5,
    val episodeSort: Int = SORT_OLDEST,
    val includePlayed: Boolean = false,
    val downloadedOnly: Boolean = false
) {
    companion object {
        const val SORT_NAME_ASC = 0
        const val SORT_NAME_DESC = 1
        const val SORT_OLDEST = 2
        const val SORT_NEWEST = 3
        const val SORT_DURATION = 4
        const val SORT_SHUFFLE = 100
    }
}

/** Per-podcast listening time (wall clock vs content consumed). */
@Entity(tableName = "listen_stats")
data class ListenStat(
    @PrimaryKey val podcastId: Long,
    val wallMs: Long = 0,
    val contentMs: Long = 0
)
