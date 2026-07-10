package com.stepcast.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ListenStatsFormatTest {

    @Test
    fun formatsMinutesHoursAndBigTotals() {
        assertEquals("0m", ListenStats.formatDuration(0))
        assertEquals("45m", ListenStats.formatDuration(45 * 60_000L))
        assertEquals("1h 30m", ListenStats.formatDuration(90 * 60_000L))
        assertEquals("99h 59m", ListenStats.formatDuration((99 * 60 + 59) * 60_000L))
        // beyond 100h the minutes stop mattering
        assertEquals("250h", ListenStats.formatDuration(250 * 3_600_000L))
    }
}
