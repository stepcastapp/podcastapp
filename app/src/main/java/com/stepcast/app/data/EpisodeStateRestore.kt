package com.stepcast.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Listening state (played / position / favorite / Up Next) restored from a
 * backup — which can only land once the episode ROWS exist.
 *
 * A restore onto a fresh install creates podcast stubs with no episodes;
 * the episodes arrive on each feed's first refresh, minutes later. So the
 * backup's per-episode state is staged in a small file and applied from
 * [PodcastRepository.refresh] as each feed fills in, matched by guid (the
 * enclosure URL as fallback — feeds rotate both). Up Next is rebuilt once
 * every feed it references has been processed, so its order survives.
 *
 * Merge rule (restoring onto a phone that already has some of this):
 * played wins, the later played-at wins, a local position is never
 * overwritten, favorites union.
 *
 * Wire format (backup "episodeState" and the staging file alike) is
 * compact on purpose — a big library carries ~100k played episodes:
 * `{"feeds": [url, …], "rows": [[feedIndex, guid, audioUrl, played01,
 * playedAtMs, positionMs, favorite01], …]}`.
 */
object EpisodeStateRestore {

    private const val FILE = "pending_episode_state.json"

    /** Pending entries older than this are dropped (a feed that never refreshes). */
    private const val EXPIRY_MS = 30L * 86_400_000

    data class Entry(
        val feedUrl: String,
        val guid: String,
        val audioUrl: String,
        val played: Boolean,
        val playedAtMs: Long,
        val positionMs: Long,
        val favorite: Boolean
    )

    data class QueueRef(val feedUrl: String, val guid: String, val audioUrl: String)

    data class BookmarkRef(
        val feedUrl: String,
        val guid: String,
        val audioUrl: String,
        val positionMs: Long,
        val note: String,
        val createdAt: Long
    )

    fun encodeBookmarks(list: List<BookmarkRef>): JSONArray = JSONArray().apply {
        list.forEach {
            put(
                JSONArray().put(it.feedUrl).put(it.guid).put(it.audioUrl)
                    .put(it.positionMs).put(it.note).put(it.createdAt)
            )
        }
    }

    fun decodeBookmarks(a: JSONArray?): List<BookmarkRef> {
        a ?: return emptyList()
        return buildList {
            for (i in 0 until a.length()) {
                val r = a.optJSONArray(i) ?: continue
                val feed = r.optString(0).takeIf { it.isNotBlank() } ?: continue
                add(
                    BookmarkRef(
                        feed, r.optString(1), r.optString(2),
                        r.optLong(3), r.optString(4), r.optLong(5)
                    )
                )
            }
        }
    }

    fun normalize(url: String): String =
        url.trim().substringAfter("://").removeSuffix("/").lowercase()

    // ---- wire format --------------------------------------------------------

    fun encodeEntries(entries: List<Entry>): JSONObject {
        val feedIndex = LinkedHashMap<String, Int>()
        val rows = JSONArray()
        for (e in entries) {
            val fi = feedIndex.getOrPut(e.feedUrl) { feedIndex.size }
            rows.put(
                JSONArray()
                    .put(fi).put(e.guid).put(e.audioUrl)
                    .put(if (e.played) 1 else 0)
                    .put(e.playedAtMs).put(e.positionMs)
                    .put(if (e.favorite) 1 else 0)
            )
        }
        return JSONObject().put("feeds", JSONArray(feedIndex.keys.toList())).put("rows", rows)
    }

    fun decodeEntries(o: JSONObject?): List<Entry> {
        o ?: return emptyList()
        val feeds = o.optJSONArray("feeds") ?: return emptyList()
        val rows = o.optJSONArray("rows") ?: return emptyList()
        return buildList {
            for (i in 0 until rows.length()) {
                val r = rows.optJSONArray(i) ?: continue
                val feed = feeds.optString(r.optInt(0, -1)).takeIf { it.isNotBlank() } ?: continue
                add(
                    Entry(
                        feedUrl = feed,
                        guid = r.optString(1),
                        audioUrl = r.optString(2),
                        played = r.optInt(3) == 1,
                        playedAtMs = r.optLong(4),
                        positionMs = r.optLong(5),
                        favorite = r.optInt(6) == 1
                    )
                )
            }
        }
    }

    fun encodeQueue(queue: List<QueueRef>): JSONArray = JSONArray().apply {
        queue.forEach { put(JSONArray().put(it.feedUrl).put(it.guid).put(it.audioUrl)) }
    }

    fun decodeQueue(a: JSONArray?): List<QueueRef> {
        a ?: return emptyList()
        return buildList {
            for (i in 0 until a.length()) {
                val r = a.optJSONArray(i) ?: continue
                val feed = r.optString(0).takeIf { it.isNotBlank() } ?: continue
                add(QueueRef(feed, r.optString(1), r.optString(2)))
            }
        }
    }

    // ---- staged state (in memory, written through to [FILE]) ----------------

    private class Pending(
        val byFeed: HashMap<String, MutableList<Entry>>,
        val bookmarksByFeed: HashMap<String, MutableList<BookmarkRef>>,
        /** null = no queue restore pending. */
        var queue: List<QueueRef>?,
        /** queue index → resolved episode id (-1 = feed processed, episode gone). */
        val queueResolved: HashMap<Int, Long>,
        var stagedAt: Long
    ) {
        fun isEmpty() = byFeed.isEmpty() && bookmarksByFeed.isEmpty() && queue == null
    }

    private val lock = Any()
    private var loaded = false
    private var pending: Pending? = null

    /** Adds [entries] and [queue] to whatever is already pending. */
    fun stage(
        context: Context,
        entries: List<Entry>,
        queue: List<QueueRef>,
        bookmarks: List<BookmarkRef> = emptyList()
    ) {
        if (entries.isEmpty() && queue.isEmpty() && bookmarks.isEmpty()) return
        synchronized(lock) {
            val p = load(context) ?: Pending(HashMap(), HashMap(), null, HashMap(), 0L)
            for (e in entries) p.byFeed.getOrPut(normalize(e.feedUrl)) { mutableListOf() } += e
            for (b in bookmarks) {
                p.bookmarksByFeed.getOrPut(normalize(b.feedUrl)) { mutableListOf() } += b
            }
            if (queue.isNotEmpty()) {
                // a newer restore's queue replaces an older pending one
                p.queue = queue
                p.queueResolved.clear()
            }
            p.stagedAt = System.currentTimeMillis()
            pending = p
            save(context)
        }
    }

    /**
     * Applies every pending entry for [feedUrl] to [podcastId]'s rows. Called
     * after a refresh inserted that feed's episodes (and right away for shows
     * that already existed at restore time).
     */
    suspend fun applyFor(
        context: Context,
        repository: PodcastRepository,
        podcastId: Long,
        feedUrl: String
    ) {
        val key = normalize(feedUrl)
        var bookmarksMine: List<BookmarkRef> = emptyList()
        val (mine, queueMine) = synchronized(lock) {
            val p = load(context) ?: return
            if (System.currentTimeMillis() - p.stagedAt > EXPIRY_MS) {
                clear(context)
                return
            }
            val mine = p.byFeed[key].orEmpty().toList()
            bookmarksMine = p.bookmarksByFeed[key].orEmpty().toList()
            val queueMine = p.queue.orEmpty().withIndex()
                .filter { normalize(it.value.feedUrl) == key && it.index !in p.queueResolved }
                .map { it.index to it.value }
            mine to queueMine
        }
        if (mine.isEmpty() && queueMine.isEmpty() && bookmarksMine.isEmpty()) return

        repository.applyEpisodeStates(podcastId, mine)
        repository.restoreBookmarks(podcastId, bookmarksMine)
        val resolved = queueMine.map { (index, ref) ->
            index to (repository.resolveEpisodeId(podcastId, ref.guid, ref.audioUrl) ?: -1L)
        }

        val finalQueue: List<Long>? = synchronized(lock) {
            val p = load(context) ?: return
            p.byFeed.remove(key)
            p.bookmarksByFeed.remove(key)
            var result: List<Long>? = null
            val queue = p.queue
            if (queue != null) {
                resolved.forEach { (index, id) -> p.queueResolved[index] = id }
                if (p.queueResolved.size >= queue.size) {
                    result = queue.indices.map { p.queueResolved[it] ?: -1L }.filter { it > 0 }
                    p.queue = null
                    p.queueResolved.clear()
                }
            }
            // throttled: rewriting a multi-MB file after each of 300 feeds
            // is pure churn, and a lost save only means re-applying some
            // entries later — the merge is idempotent
            if (p.isEmpty()) {
                clear(context)
            } else if (System.currentTimeMillis() - lastSaveMs > SAVE_THROTTLE_MS) {
                save(context)
            }
            result
        }
        if (!finalQueue.isNullOrEmpty()) repository.restoreQueue(finalQueue)
        PlaybackJournal.log(
            "restore-state",
            "pod=$podcastId applied=${mine.size} queue=${finalQueue?.size ?: "pending"}"
        )
    }

    private fun file(context: Context) = File(context.filesDir, FILE)

    /** Parsed once, then kept: a restore refreshes hundreds of feeds. */
    private fun load(context: Context): Pending? {
        if (loaded) return pending
        loaded = true
        val f = file(context)
        if (!f.exists()) return null
        pending = runCatching {
            val root = JSONObject(f.readText())
            val byFeed = HashMap<String, MutableList<Entry>>()
            for (e in decodeEntries(root.optJSONObject("episodes"))) {
                byFeed.getOrPut(normalize(e.feedUrl)) { mutableListOf() } += e
            }
            val resolved = HashMap<Int, Long>()
            root.optJSONObject("queueResolved")?.let { r ->
                r.keys().forEach { k -> k.toIntOrNull()?.let { resolved[it] = r.optLong(k) } }
            }
            val bookmarksByFeed = HashMap<String, MutableList<BookmarkRef>>()
            for (b in decodeBookmarks(root.optJSONArray("bookmarks"))) {
                bookmarksByFeed.getOrPut(normalize(b.feedUrl)) { mutableListOf() } += b
            }
            Pending(
                byFeed,
                bookmarksByFeed,
                root.optJSONArray("queue")?.let(::decodeQueue),
                resolved,
                root.optLong("stagedAt", 0L)
            )
        }.getOrNull()
        return pending
    }

    private var lastSaveMs = 0L
    private const val SAVE_THROTTLE_MS = 30_000L

    private fun save(context: Context) {
        val p = pending ?: return
        lastSaveMs = System.currentTimeMillis()
        val root = JSONObject()
            .put("episodes", encodeEntries(p.byFeed.values.flatten()))
            .put("bookmarks", encodeBookmarks(p.bookmarksByFeed.values.flatten()))
            .put("stagedAt", p.stagedAt)
        p.queue?.let { q ->
            root.put("queue", encodeQueue(q))
            root.put(
                "queueResolved",
                JSONObject().apply { p.queueResolved.forEach { (k, v) -> put(k.toString(), v) } }
            )
        }
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file(context))
    }

    private fun clear(context: Context) {
        pending = null
        file(context).delete()
    }
}
