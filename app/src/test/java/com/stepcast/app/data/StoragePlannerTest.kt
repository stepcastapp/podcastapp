package com.stepcast.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePlannerTest {

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L
    private val mb = 1_048_576L

    private fun file(id: Long, ageDays: Int, played: Boolean = false, protected: Boolean = false) =
        StoragePlanner.DownloadedFile(id, 100 * mb, now - ageDays * day, played, protected)

    private fun show(
        keep: Int,
        files: List<StoragePlanner.DownloadedFile>,
        lastListenedDaysAgo: Int?,
        finished: Int
    ) = StoragePlanner.Show(
        podcastId = 1, title = "S", keepDownloads = keep, maxAgeDays = 0,
        typicalEpisodeBytes = 100 * mb, sizeIsEstimate = false, downloads = files,
        lastListenedMs = lastListenedDaysAgo?.let { now - it * day } ?: 0L,
        finishedRecently = finished
    )

    @Test
    fun staleShowStopsAndFreesItsUnprotectedUnplayed() {
        val s = StoragePlanner.suggestFor(
            show(5, listOf(file(1, 1), file(2, 2), file(3, 3, protected = true)), 120, 0), now
        )!!
        assertEquals(StoragePlanner.Kind.STOP, s.kind)
        assertEquals(0, s.newKeep)
        assertEquals(listOf(1L, 2L), s.removeNow.map { it.episodeId })
        assertEquals(500 * mb, s.plannedSavingBytes)
    }

    @Test
    fun keepsMoreThanListenedReducesToTheListeningRate() {
        val files = (1..6L).map { file(it, it.toInt()) }
        val s = StoragePlanner.suggestFor(show(6, files, 2, 2), now)!!
        assertEquals(StoragePlanner.Kind.REDUCE, s.kind)
        assertEquals(2, s.newKeep)
        // newest two stay; the four older unplayed go
        assertEquals(listOf(3L, 4L, 5L, 6L), s.removeNow.map { it.episodeId })
    }

    @Test
    fun heavyListenersAreLeftAlone() {
        assertNull(StoragePlanner.suggestFor(show(3, emptyList(), 1, 5), now))
        assertNull(StoragePlanner.suggestFor(show(0, emptyList(), null, 0), now))
    }

    @Test
    fun protectedEpisodesFillKeptSlotsAndAreNeverRemoved() {
        val files = listOf(file(1, 1, protected = true), file(2, 2), file(3, 3))
        val excess = StoragePlanner.excessUnplayed(show(3, files, 1, 1), keep = 1)
        assertEquals(listOf(2L, 3L), excess.map { it.episodeId })
    }

    @Test
    fun planTotalsAndRanking() {
        val big = show(10, emptyList(), null, 0).copy(podcastId = 2, title = "Big")
        val small = show(2, emptyList(), 1, 1).copy(podcastId = 3, title = "Small")
        val plan = StoragePlanner.plan(listOf(big, small), capBytes = 500 * mb, nowMs = now)
        assertEquals(1200 * mb, plan.plannedBytes)
        assertTrue(plan.rulesExceedCap)
        assertEquals("Big", plan.suggestions.first().show.title)
        assertEquals(1200 * mb - 1100 * mb, plan.plannedAfterAll)
    }

    @Test
    fun typicalSizePrefersRealFilesThenDurations() {
        assertEquals(30L to false, StoragePlanner.typicalSize(listOf(10, 30, 50), emptyList()))
        val (bytes, estimate) = StoragePlanner.typicalSize(emptyList(), listOf(60 * 60_000L))
        assertEquals(60 * StoragePlanner.BYTES_PER_MINUTE_ESTIMATE, bytes)
        assertTrue(estimate)
    }
}
