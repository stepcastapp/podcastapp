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

    private val lock = Any()

    fun normalize(url: String): String =
        url.trim().substringAfter("://").removeSuffix("/").lowercase()

    // ---- JSON (shared with StepcastBackup) --------------------------------

    fun entryToJson(e: Entry): JSONObject = JSONObject()
        .put("f", e.feedUrl)
        .put("g", e.guid)
        .put("u", e.audioUrl)
        .put("pl", e.played)
        .put("pa", e.playedAtMs)
        .put("pos", e.positionMs)
        .put("fav", e.favorite)

    fun entryFromJson(o: JSONObject): Entry? {
        val feed = o.optString("f").takeIf { it.isNotBlank() } ?: return null
        return Entry(
            feedUrl = feed,
            guid = o.optString("g"),
            audioUrl = o.optString("u"),
            played = o.optBoolean("pl", false),
            playedAtMs = o.optLong("pa", 0L),
            positionMs = o.optLong("pos", 0L),
            favorite = o.optBoolean("fav", false)
        )
    }

    fun queueToJson(q: QueueRef): JSONObject =
        JSONObject().put("f", q.feedUrl).put("g", q.guid).put("u", q.audioUrl)

    fun queueFromJson(o: JSONObject): QueueRef? {
        val feed = o.optString("f").takeIf { it.isNotBlank() } ?: return null
        return QueueRef(feed, o.optString("g"), o.optString("u"))
    }

    // ---- staging ------------------------------------------------------------

    /** Adds [entries] and [queue] to whatever is already pending. */
    fun stage(context: Context, entries: List<Entry>, queue: List<QueueRef>) {
        if (entries.isEmpty() && queue.isEmpty()) return
        synchronized(lock) {
            val root = read(context) ?: JSONObject()
            val eps = root.optJSONArray("episodes") ?: JSONArray()
            entries.forEach { eps.put(entryToJson(it)) }
            root.put("episodes", eps)
            if (queue.isNotEmpty()) {
                // a newer restore's queue replaces an older pending one
                root.put("queue", JSONArray().apply { queue.forEach { put(queueToJson(it)) } })
                root.put("queueResolved", JSONObject())
            }
            root.put("stagedAt", System.currentTimeMillis())
            write(context, root)
        }
    }

    fun hasPending(context: Context): Boolean = synchronized(lock) { read(context) != null }

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
        val (mine, queueMine) = synchronized(lock) {
            val root = read(context) ?: return
            if (System.currentTimeMillis() - root.optLong("stagedAt", 0L) > EXPIRY_MS) {
                delete(context)
                return
            }
            val eps = root.optJSONArray("episodes") ?: JSONArray()
            val mine = mutableListOf<Entry>()
            for (i in 0 until eps.length()) {
                val e = eps.optJSONObject(i)?.let(::entryFromJson) ?: continue
                if (normalize(e.feedUrl) == key) mine += e
            }
            val queue = root.optJSONArray("queue") ?: JSONArray()
            val queueMine = mutableListOf<Pair<Int, QueueRef>>()
            for (i in 0 until queue.length()) {
                val q = queue.optJSONObject(i)?.let(::queueFromJson) ?: continue
                if (normalize(q.feedUrl) == key) queueMine += i to q
            }
            mine to queueMine
        }
        if (mine.isEmpty() && queueMine.isEmpty()) return

        repository.applyEpisodeStates(podcastId, mine)
        val resolved = queueMine.map { (index, ref) ->
            index to repository.resolveEpisodeId(podcastId, ref.guid, ref.audioUrl)
        }

        val finalQueue: List<Long>? = synchronized(lock) {
            val root = read(context) ?: return
            // this feed's entries are done — drop them
            val eps = root.optJSONArray("episodes") ?: JSONArray()
            val kept = JSONArray()
            for (i in 0 until eps.length()) {
                val o = eps.optJSONObject(i) ?: continue
                if (normalize(o.optString("f")) != key) kept.put(o)
            }
            root.put("episodes", kept)
            var result: List<Long>? = null
            val queue = root.optJSONArray("queue")
            if (queue != null) {
                val done = root.optJSONObject("queueResolved") ?: JSONObject()
                // -1 = the feed was processed but the episode is gone
                resolved.forEach { (index, id) -> done.put(index.toString(), id ?: -1L) }
                root.put("queueResolved", done)
                if (done.length() >= queue.length()) {
                    result = (0 until queue.length())
                        .map { done.optLong(it.toString(), -1L) }
                        .filter { it > 0 }
                    root.remove("queue")
                    root.remove("queueResolved")
                }
            }
            if (kept.length() == 0 && !root.has("queue")) {
                delete(context)
            } else {
                write(context, root)
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

    // parsed once and written through: a restore refreshes hundreds of feeds,
    // and re-parsing a multi-megabyte file per feed would dominate the run
    private var cached: JSONObject? = null

    private fun read(context: Context): JSONObject? {
        cached?.let { return it }
        val f = file(context)
        if (!f.exists()) return null
        return runCatching { JSONObject(f.readText()) }.getOrNull()?.also { cached = it }
    }

    private fun write(context: Context, root: JSONObject) {
        cached = root
        val f = file(context)
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(f)
    }

    private fun delete(context: Context) {
        cached = null
        file(context).delete()
    }
}
