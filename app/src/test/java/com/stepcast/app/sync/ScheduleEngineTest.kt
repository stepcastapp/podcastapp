package com.stepcast.app.sync

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleEngineTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(2026, Calendar.JULY, day, hour, minute, 0)
        }.timeInMillis

    private val noQuiet = ScheduleEngine.Config(
        checkpointMinutes = listOf(390, 1050), // 6:30, 17:30
        quietEnabled = false,
        quietStartMinutes = 1380,
        quietEndMinutes = 360
    )

    private val withQuiet = noQuiet.copy(quietEnabled = true)

    // ---- modes -------------------------------------------------------------

    @Test
    fun `manual is never due and has no next check`() {
        assertFalse(
            ScheduleEngine.isDue(
                ScheduleEngine.MODE_MANUAL, 0, 0L, null, at(8, 12), noQuiet, utc
            )
        )
        assertNull(
            ScheduleEngine.nextCheck(
                ScheduleEngine.MODE_MANUAL, 0, 0L, null, at(8, 12), noQuiet, utc
            )
        )
    }

    @Test
    fun `hourly due after 55 minutes`() {
        assertTrue(
            ScheduleEngine.isDue(
                ScheduleEngine.MODE_HOURLY, 0, at(8, 10), null, at(8, 11, 5),
                noQuiet, utc
            )
        )
        assertFalse(
            ScheduleEngine.isDue(
                ScheduleEngine.MODE_HOURLY, 0, at(8, 10), null, at(8, 10, 30),
                noQuiet, utc
            )
        )
    }

    @Test
    fun `daily-at due once its slot passes`() {
        // pinned daily at 7:00; refreshed 6:00, now 8:00 -> due
        assertTrue(
            ScheduleEngine.isDue(
                ScheduleEngine.MODE_DAILY_AT, 420, at(8, 6), null, at(8, 8),
                noQuiet, utc
            )
        )
        // refreshed 7:30 -> not due again at 8:00
        assertFalse(
            ScheduleEngine.isDue(
                ScheduleEngine.MODE_DAILY_AT, 420, at(8, 7, 30), null, at(8, 8),
                noQuiet, utc
            )
        )
    }

    @Test
    fun `weekly-at fires on its weekday slot`() {
        // Wednesday (ISO 3) at 9:00 -> param 3*1440+540; Jul 8 2026 is Wed
        val param = 3 * 1440 + 540
        assertEquals(
            at(8, 9),
            ScheduleEngine.latestWeeklySlotMs(param, at(10, 12), utc)
        )
        assertTrue(
            ScheduleEngine.isDue(
                ScheduleEngine.MODE_WEEKLY_AT, param, at(7, 12), null, at(8, 10),
                noQuiet, utc
            )
        )
        assertFalse(
            ScheduleEngine.isDue(
                ScheduleEngine.MODE_WEEKLY_AT, param, at(8, 10), null, at(10, 12),
                noQuiet, utc
            )
        )
    }

    // ---- automatic ---------------------------------------------------------

    @Test
    fun `auto meets checkpoints`() {
        // refreshed 5:00; at 7:00 the 6:30 checkpoint has passed -> due
        assertTrue(
            ScheduleEngine.isDue(
                ScheduleEngine.MODE_AUTO, 0, at(8, 5), null, at(8, 7), noQuiet, utc
            )
        )
        // refreshed 6:45 (after the checkpoint) -> not due at 7:00
        assertFalse(
            ScheduleEngine.isDue(
                ScheduleEngine.MODE_AUTO, 0, at(8, 6, 45), null, at(8, 7),
                noQuiet, utc
            )
        )
    }

    @Test
    fun `auto checks inside the release window and stops after it`() {
        val expected = at(8, 6)
        // refreshed overnight; at 6:05 with an expected 6:00 drop -> due
        assertTrue(
            ScheduleEngine.isDue(
                ScheduleEngine.MODE_AUTO, 0, at(7, 22), expected, at(8, 6, 5),
                ScheduleEngine.Config(emptyList(), false, 0, 0), utc
            )
        )
        // window over (6:00 + 3h): not due from release anymore
        assertFalse(
            ScheduleEngine.isDue(
                ScheduleEngine.MODE_AUTO, 0, at(8, 6, 10), expected, at(8, 10),
                ScheduleEngine.Config(emptyList(), false, 0, 0), utc
            )
        )
        // rechecks inside the window are spaced by the recheck gap
        assertFalse(
            ScheduleEngine.isDue(
                ScheduleEngine.MODE_AUTO, 0, at(8, 6, 5), expected, at(8, 6, 40),
                ScheduleEngine.Config(emptyList(), false, 0, 0), utc
            )
        )
        assertTrue(
            ScheduleEngine.isDue(
                ScheduleEngine.MODE_AUTO, 0, at(8, 6, 5), expected, at(8, 7, 10),
                ScheduleEngine.Config(emptyList(), false, 0, 0), utc
            )
        )
    }

    @Test
    fun `auto baseline catches everything daily`() {
        assertTrue(
            ScheduleEngine.isDue(
                ScheduleEngine.MODE_AUTO, 0, at(7, 5), null, at(8, 6),
                ScheduleEngine.Config(emptyList(), false, 0, 0), utc
            )
        )
    }

    // ---- quiet hours -------------------------------------------------------

    @Test
    fun `quiet hours suppress checks and slide the next one`() {
        // 23:30 is inside 23:00-06:00 quiet
        assertFalse(
            ScheduleEngine.isDue(
                ScheduleEngine.MODE_HOURLY, 0, at(8, 20), null, at(8, 23, 30),
                withQuiet, utc
            )
        )
        // an hourly check landing at 23:30 slides to quiet end 6:00 next day
        val next = ScheduleEngine.nextCheck(
            ScheduleEngine.MODE_HOURLY, 0, at(8, 22, 35), null, at(8, 23, 30),
            withQuiet, utc
        )
        assertEquals(at(9, 6), next?.timeMs)
    }

    @Test
    fun `quiet wrap math`() {
        assertTrue(ScheduleEngine.inQuiet(at(8, 23, 30), withQuiet, utc))
        assertTrue(ScheduleEngine.inQuiet(at(8, 2), withQuiet, utc))
        assertFalse(ScheduleEngine.inQuiet(at(8, 12), withQuiet, utc))
    }

    // ---- next check --------------------------------------------------------

    @Test
    fun `auto next check is the soonest upcoming promise`() {
        // refreshed at 7:00 (just after the 6:30 checkpoint); next promise is
        // the 17:30 checkpoint today
        val next = ScheduleEngine.nextCheck(
            ScheduleEngine.MODE_AUTO, 0, at(8, 7), null, at(8, 7, 5), noQuiet, utc
        )
        assertEquals(at(8, 17, 30), next?.timeMs)
        assertEquals(ScheduleEngine.Reason.CHECKPOINT, next?.reason)
    }

    @Test
    fun `auto next check prefers an imminent release`() {
        val next = ScheduleEngine.nextCheck(
            ScheduleEngine.MODE_AUTO, 0, at(8, 7), at(8, 9), at(8, 7, 5),
            noQuiet, utc
        )
        assertEquals(at(8, 9), next?.timeMs)
        assertEquals(ScheduleEngine.Reason.RELEASE, next?.reason)
    }

    @Test
    fun `daily-at next check is the next slot`() {
        val next = ScheduleEngine.nextCheck(
            ScheduleEngine.MODE_DAILY_AT, 420, at(8, 7, 30), null, at(8, 8),
            noQuiet, utc
        )
        assertEquals(at(9, 7), next?.timeMs)
        assertEquals(ScheduleEngine.Reason.DAILY, next?.reason)
    }
}
