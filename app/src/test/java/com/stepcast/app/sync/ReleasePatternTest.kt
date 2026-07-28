package com.stepcast.app.sync

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasePatternTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(
        day: Int,
        hour: Int,
        minute: Int = 0,
        month: Int = Calendar.JULY
    ): Long = Calendar.getInstance(utc).apply {
        clear()
        set(2026, month, day, hour, minute, 0)
    }.timeInMillis

    // ---- inference ---------------------------------------------------------

    @Test
    fun `too few episodes is unknown`() {
        val p = ReleasePattern.infer(listOf(at(1, 6), at(2, 6)), utc)
        assertEquals(ReleasePattern.Kind.UNKNOWN, p.kind)
    }

    @Test
    fun `daily dawn show infers daily at its hour`() {
        val dates = (1..10).map { at(it, 6, 30) }
        val p = ReleasePattern.infer(dates, utc)
        assertEquals(ReleasePattern.Kind.DAILY, p.kind)
        assertEquals(6 * 60 + 30, p.minutesOfDay)
        assertTrue(p.confidence >= 0.9f)
    }

    @Test
    fun `weekly tuesday show infers weekly on tuesday`() {
        // July 2026: the 7th, 14th, 21st, 28th are Tuesdays
        val dates = listOf(7, 14, 21, 28).map { at(it, 5) } +
            listOf(2, 9, 16, 23).map { at(it, 5, 0, Calendar.JUNE) }
        // June 2026: 2, 9, 16, 23 are Tuesdays
        val p = ReleasePattern.infer(dates, utc)
        assertEquals(ReleasePattern.Kind.WEEKLY, p.kind)
        assertEquals(setOf(2), p.daysOfWeek) // ISO Tuesday
        assertEquals(5 * 60, p.minutesOfDay)
    }

    @Test
    fun `monthly show is sparse`() {
        val dates = listOf(
            at(1, 12), at(1, 12, 0, Calendar.JUNE), at(1, 12, 0, Calendar.MAY),
            at(1, 12, 0, Calendar.APRIL)
        )
        assertEquals(ReleasePattern.Kind.SPARSE, ReleasePattern.infer(dates, utc).kind)
    }

    // ---- next expected -----------------------------------------------------

    @Test
    fun `daily show expects tomorrow at its time`() {
        val dates = (1..10).map { at(it, 6, 30) }
        val p = ReleasePattern.infer(dates, utc)
        val next = ReleasePattern.nextExpectedMs(p, at(10, 6, 30), at(10, 12), utc)
        assertEquals(at(11, 6, 30), next)
    }

    @Test
    fun `missed expected drops advance to the next candidate`() {
        val dates = (1..10).map { at(it, 6, 30) }
        val p = ReleasePattern.infer(dates, utc)
        // it's noon on the 13th and nothing has arrived since the 10th: the
        // 11th-13th 6:30 candidates are all >3h past (missed), so the next
        // candidate inside the grace window is the 14th
        val next = ReleasePattern.nextExpectedMs(p, at(10, 6, 30), at(13, 12), utc)
        assertEquals(at(14, 6, 30), next)
    }

    @Test
    fun `weekly tuesday expects next tuesday`() {
        val dates = listOf(7, 14, 21, 28).map { at(it, 5) } +
            listOf(2, 9, 16, 23).map { at(it, 5, 0, Calendar.JUNE) }
        val p = ReleasePattern.infer(dates, utc)
        // last drop Tue Jul 28; on Thu Jul 30 the next expected is Tue Aug 4
        val next = ReleasePattern.nextExpectedMs(p, at(28, 5), at(30, 9), utc)
        assertEquals(at(4, 5, 0, Calendar.AUGUST), next)
    }

    @Test
    fun `sparse and unknown produce no expectation`() {
        val sparse = ReleasePattern.infer(
            listOf(at(1, 12), at(1, 12, 0, Calendar.JUNE), at(1, 12, 0, Calendar.MAY)),
            utc
        )
        assertNull(ReleasePattern.nextExpectedMs(sparse, at(1, 12), at(20, 9), utc))
        assertNull(
            ReleasePattern.nextExpectedMs(ReleasePattern.UNKNOWN, at(1, 12), at(20, 9), utc)
        )
    }

    // ---- helpers -----------------------------------------------------------

    @Test
    fun `iso day of week maps correctly`() {
        assertEquals(3, ReleasePattern.isoDayOfWeek(at(8, 12), utc))  // Jul 8 2026 = Wed
        assertEquals(7, ReleasePattern.isoDayOfWeek(at(12, 12), utc)) // Jul 12 2026 = Sun
    }

    @Test
    fun `circular median lands near midnight for straddling times`() {
        // 23:45, 23:50, 23:55, 00:05, 00:10, 00:15 — a linear median would
        // report ~23:45 or drift toward noon depending on the split
        val median = ReleasePattern.circularMedianMinutes(
            listOf(1425, 1430, 1435, 5, 10, 15)
        )
        val toMidnight = minOf(median, 1440 - median)
        assertTrue("median $median should sit near midnight", toMidnight <= 30)
    }

    @Test
    fun `circular median matches plain median away from midnight`() {
        assertEquals(390, ReleasePattern.circularMedianMinutes(listOf(380, 390, 400)))
    }

    @Test
    fun `midnight-straddling daily show still gets a sane expectation`() {
        // releases alternating 23:55 / 00:05 across midnight, daily
        val dates = buildList {
            for (d in 1..10) {
                add(if (d % 2 == 0) at(d, 23, 55) else at(d, 0, 5))
            }
        }
        val p = ReleasePattern.infer(dates, utc)
        val toMidnight = minOf(p.minutesOfDay, 1440 - p.minutesOfDay)
        assertTrue("minutesOfDay ${p.minutesOfDay} near midnight", toMidnight <= 60)
    }
}
