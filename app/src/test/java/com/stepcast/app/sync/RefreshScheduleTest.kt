package com.stepcast.app.sync

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshScheduleTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            set(2026, Calendar.JULY, 8, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    // ---- plain interval (no anchor) ----------------------------------------

    @Test
    fun `no anchor - due after interval`() {
        assertTrue(RefreshSchedule.isDue(at(1), 3, -1, at(4), utc))
        assertFalse(RefreshSchedule.isDue(at(2), 3, -1, at(4), utc))
    }

    // ---- anchored slots ------------------------------------------------------

    @Test
    fun `anchored - slots land on anchor plus multiples`() {
        // anchor 5:00, every 6h -> latest slot at 12:30 is 11:00
        assertEquals(at(11), RefreshSchedule.latestSlotMs(300, 6, at(12, 30), utc))
        // right at a slot
        assertEquals(at(17), RefreshSchedule.latestSlotMs(300, 6, at(17), utc))
    }

    @Test
    fun `anchored - before today's anchor uses yesterday's cycle`() {
        // anchor 5:00 daily; at 3:00 the latest slot is YESTERDAY 5:00
        val slot = RefreshSchedule.latestSlotMs(300, 24, at(3), utc)
        assertEquals(at(5) - 86_400_000L, slot)
    }

    @Test
    fun `anchored - due only when a slot passed since last refresh`() {
        // refreshed at 11:30 (after the 11:00 slot); at 12:30 nothing new
        assertFalse(RefreshSchedule.isDue(at(11, 30), 6, 300, at(12, 30), utc))
        // at 17:30 the 17:00 slot has passed -> due
        assertTrue(RefreshSchedule.isDue(at(11, 30), 6, 300, at(17, 30), utc))
        // refreshed days ago -> due immediately
        assertTrue(RefreshSchedule.isDue(0L, 6, 300, at(12, 30), utc))
    }

    // ---- anchor parsing ------------------------------------------------------

    @Test
    fun `parse and format anchors`() {
        assertEquals(300, RefreshSchedule.parseAnchor("5:00"))
        assertEquals(300, RefreshSchedule.parseAnchor("05:00"))
        assertEquals(23 * 60 + 59, RefreshSchedule.parseAnchor("23:59"))
        assertNull(RefreshSchedule.parseAnchor("24:00"))
        assertNull(RefreshSchedule.parseAnchor("5"))
        assertNull(RefreshSchedule.parseAnchor(""))
        assertEquals("5:00", RefreshSchedule.formatAnchor(300))
        assertEquals("23:05", RefreshSchedule.formatAnchor(23 * 60 + 5))
    }
}
