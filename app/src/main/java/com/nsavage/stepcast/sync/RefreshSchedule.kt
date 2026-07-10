package com.nsavage.stepcast.sync

import java.util.Calendar
import java.util.TimeZone

/**
 * Pure due-time logic for category refresh cycles. Two modes:
 *
 *  - No anchor (anchorMinutes < 0): due once [freqHours] have passed since
 *    the last refresh — the original rolling interval.
 *  - Anchored: refresh slots sit at the reference time of day and every
 *    [freqHours] after it (5:00 + 6h → 5:00, 11:00, 17:00, 23:00 local).
 *    A feed is due when a slot has passed that it hasn't been refreshed
 *    for. The background worker ticks hourly, so an anchored refresh runs
 *    within the hour after its slot.
 */
object RefreshSchedule {

    fun isDue(
        lastRefreshedMs: Long,
        freqHours: Int,
        anchorMinutes: Int,
        nowMs: Long,
        zone: TimeZone = TimeZone.getDefault()
    ): Boolean {
        val hours = freqHours.coerceAtLeast(1)
        if (anchorMinutes < 0) {
            return nowMs >= lastRefreshedMs + hours * 3_600_000L
        }
        val slot = latestSlotMs(anchorMinutes, hours, nowMs, zone)
        return lastRefreshedMs < slot
    }

    /** The most recent anchored slot at or before [nowMs]. */
    fun latestSlotMs(
        anchorMinutes: Int,
        freqHours: Int,
        nowMs: Long,
        zone: TimeZone = TimeZone.getDefault()
    ): Long {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = nowMs
        cal.set(Calendar.HOUR_OF_DAY, (anchorMinutes / 60).coerceIn(0, 23))
        cal.set(Calendar.MINUTE, (anchorMinutes % 60).coerceIn(0, 59))
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        var anchor = cal.timeInMillis
        if (anchor > nowMs) anchor -= 86_400_000L
        val stepMs = freqHours.coerceAtLeast(1) * 3_600_000L
        val steps = (nowMs - anchor) / stepMs
        return anchor + steps * stepMs
    }

    /** "05:00" ↔ 300; parse returns null for anything that isn't HH:MM. */
    fun parseAnchor(text: String): Int? {
        val match = Regex("""^(\d{1,2}):(\d{2})$""").find(text.trim()) ?: return null
        val h = match.groupValues[1].toInt()
        val m = match.groupValues[2].toInt()
        if (h > 23 || m > 59) return null
        return h * 60 + m
    }

    fun formatAnchor(anchorMinutes: Int): String =
        "%d:%02d".format(anchorMinutes / 60, anchorMinutes % 60)
}
