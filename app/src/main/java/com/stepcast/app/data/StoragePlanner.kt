package com.stepcast.app.data

/**
 * Download rules vs the storage limit, and where listening history says
 * space can be won back.
 *
 * Every show's "auto-keep newest N" is a standing order for disk space:
 * N × that show's typical episode size. Summed across a big library that
 * can exceed the global limit before a single new episode arrives — and
 * then every automatic download is refused. The planner makes the sum
 * visible and ranks concrete per-show changes by how much they free,
 * using what the listener actually does:
 *
 *  - STOP: the show auto-downloads but hasn't been listened to in
 *    [STALE_DAYS] days (or ever) → stop auto-downloading it.
 *  - REDUCE: the show keeps more unplayed episodes than the listener
 *    finishes in [RATE_DAYS] days → keep about what actually gets heard.
 *
 * Applying a suggestion changes the show's rule AND removes the unplayed
 * auto-downloads beyond the new count (oldest first) — those are
 * re-downloadable on tap. Never removed: anything in Up Next, half-listened
 * or favorited. Pure: the repository gathers [Show]s, the UI renders.
 */
object StoragePlanner {

    const val STALE_DAYS = 60
    const val RATE_DAYS = 30
    private const val DAY_MS = 86_400_000L

    /** Below this a suggestion isn't worth the screen space. */
    const val MIN_SAVING_BYTES = 50L * 1024 * 1024

    /** Typical speech podcast ≈ 128 kbps ≈ 0.96 MB per minute. */
    const val BYTES_PER_MINUTE_ESTIMATE = 960_000L

    data class DownloadedFile(
        val episodeId: Long,
        val bytes: Long,
        val pubDateMs: Long,
        val played: Boolean,
        /** In Up Next, half-listened or favorited — never removed to make room. */
        val protected: Boolean
    )

    data class Show(
        val podcastId: Long,
        val title: String,
        val keepDownloads: Int,
        val maxAgeDays: Int,
        /** Median downloaded file size, or a duration-based estimate. */
        val typicalEpisodeBytes: Long,
        val sizeIsEstimate: Boolean,
        val downloads: List<DownloadedFile>,
        /** Last time anything of this show was played; 0 = never. */
        val lastListenedMs: Long,
        /** Episodes of this show finished in the last [RATE_DAYS] days. */
        val finishedRecently: Int
    ) {
        val usedBytes get() = downloads.sumOf { it.bytes }
        /** What this show's rule asks for: keep × typical size. */
        val plannedBytes get() = keepDownloads.toLong() * typicalEpisodeBytes
    }

    enum class Kind { STOP, REDUCE }

    data class Suggestion(
        val show: Show,
        val kind: Kind,
        val newKeep: Int,
        /** Unplayed, unprotected downloads the change would delete now. */
        val removeNow: List<DownloadedFile>,
        val freesNowBytes: Long,
        /** Drop in the show's standing reservation (keep × typical size). */
        val plannedSavingBytes: Long
    ) {
        /** Ranking key: the larger of immediate and long-run savings. */
        val impactBytes get() = maxOf(freesNowBytes, plannedSavingBytes)
    }

    data class Plan(
        val usedBytes: Long,
        val plannedBytes: Long,
        val capBytes: Long,
        val suggestions: List<Suggestion>
    ) {
        val overCap get() = capBytes > 0 && usedBytes >= capBytes
        val rulesExceedCap get() = capBytes > 0 && plannedBytes > capBytes
        /** Planned total if every suggestion were applied. */
        val plannedAfterAll get() = plannedBytes - suggestions.sumOf { it.plannedSavingBytes }
    }

    fun plan(shows: List<Show>, capBytes: Long, nowMs: Long): Plan {
        val suggestions = shows.mapNotNull { suggestFor(it, nowMs) }
            .filter { it.impactBytes >= MIN_SAVING_BYTES }
            .sortedByDescending { it.impactBytes }
        return Plan(
            usedBytes = shows.sumOf { it.usedBytes },
            plannedBytes = shows.sumOf { it.plannedBytes },
            capBytes = capBytes,
            suggestions = suggestions
        )
    }

    internal fun suggestFor(show: Show, nowMs: Long): Suggestion? {
        if (show.keepDownloads <= 0) return null
        val stale = show.lastListenedMs <= 0 ||
            nowMs - show.lastListenedMs > STALE_DAYS * DAY_MS
        val newKeep = when {
            stale -> 0
            // listens to fewer than it keeps: keep what actually gets heard
            // (at least one, so the next episode is always ready)
            show.finishedRecently < show.keepDownloads ->
                show.finishedRecently.coerceAtLeast(1)
            else -> return null
        }
        if (newKeep >= show.keepDownloads) return null
        val removeNow = excessUnplayed(show, newKeep)
        return Suggestion(
            show = show,
            kind = if (stale) Kind.STOP else Kind.REDUCE,
            newKeep = newKeep,
            removeNow = removeNow,
            freesNowBytes = removeNow.sumOf { it.bytes },
            plannedSavingBytes = (show.keepDownloads - newKeep) * show.typicalEpisodeBytes
        )
    }

    /**
     * Unplayed, unprotected downloads beyond the newest [keep] — what a
     * lower keep count makes surplus. Protected ones still count toward
     * the kept slots (they're staying anyway).
     */
    fun excessUnplayed(show: Show, keep: Int): List<DownloadedFile> {
        val unplayed = show.downloads.filter { !it.played }.sortedByDescending { it.pubDateMs }
        var slots = keep
        val excess = mutableListOf<DownloadedFile>()
        for (f in unplayed) {
            if (f.protected) {
                slots-- // stays regardless, and fills a slot
                continue
            }
            if (slots > 0) {
                slots--
            } else {
                excess += f
            }
        }
        return excess
    }

    /** Median of known sizes, else median duration × bitrate estimate. */
    fun typicalSize(fileSizes: List<Long>, durationsMs: List<Long>): Pair<Long, Boolean> {
        val sizes = fileSizes.filter { it > 0 }.sorted()
        if (sizes.isNotEmpty()) return sizes[sizes.size / 2] to false
        val durations = durationsMs.filter { it > 0 }.sorted()
        val minutes = if (durations.isNotEmpty()) durations[durations.size / 2] / 60_000 else 45
        return minutes * BYTES_PER_MINUTE_ESTIMATE to true
    }
}
