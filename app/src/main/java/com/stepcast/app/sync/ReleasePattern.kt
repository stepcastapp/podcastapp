package com.stepcast.app.sync

import java.util.Calendar
import java.util.TimeZone

/**
 * Infers a feed's release pattern from its recent episode publish times —
 * the data the app already has in Room. Pure and zone-parameterized so it
 * unit-tests deterministically.
 *
 * The point: podcasts release on schedules, so "Automatic" scheduling can
 * check each feed shortly after its expected drop instead of polling on a
 * frameless interval. A weekly show stops being polled 8x a day; a dawn
 * news show is fresh minutes after it drops.
 */
object ReleasePattern {

    enum class Kind { DAILY, WEEKLY, SPARSE, IRREGULAR, UNKNOWN }

    data class Pattern(
        val kind: Kind,
        /** ISO days of week the show releases on (1=Mon..7=Sun); empty = any. */
        val daysOfWeek: Set<Int>,
        /** Typical local publish time, minutes after midnight. */
        val minutesOfDay: Int,
        /** Fraction of recent intervals consistent with the median. */
        val confidence: Float,
        val medianIntervalMs: Long
    )

    private const val DAY_MS = 86_400_000L

    /** Candidates older than this before now count as missed and are skipped. */
    const val MISSED_GRACE_MS = 3 * 3_600_000L

    val UNKNOWN = Pattern(Kind.UNKNOWN, emptySet(), 6 * 60, 0f, 0L)

    fun infer(
        pubDatesMs: List<Long>,
        zone: TimeZone = TimeZone.getDefault()
    ): Pattern {
        val dates = pubDatesMs.filter { it > 0 }.sortedDescending().take(20)
        if (dates.size < 3) return UNKNOWN
        // dates are newest-first, so zipWithNext gives positive gaps
        val intervals = dates.zipWithNext { a, b -> a - b }.filter { it > 60_000L }
        if (intervals.isEmpty()) return UNKNOWN
        val median = intervals.sorted()[intervals.size / 2]
        val consistent = intervals.count { it in (median / 2)..(median * 3 / 2) }
        val confidence = consistent.toFloat() / intervals.size
        val minuteList = dates.map { minutesOfDay(it, zone) }.sorted()
        val minutes = minuteList[minuteList.size / 2]
        val dows = dates.map { isoDayOfWeek(it, zone) }

        return when {
            median <= DAY_MS * 3 / 2 -> {
                // ~daily; a show that never posts weekends is weekday-daily
                val weekendShare =
                    dows.count { it >= 6 }.toFloat() / dows.size
                val daySet = if (weekendShare < 0.08f) {
                    setOf(1, 2, 3, 4, 5)
                } else {
                    (1..7).toSet()
                }
                Pattern(Kind.DAILY, daySet, minutes, confidence, median)
            }
            median <= DAY_MS * 10 -> {
                val dowCounts = dows.groupingBy { it }.eachCount()
                val top = dowCounts.maxByOrNull { it.value }
                if (top != null && top.value.toFloat() / dows.size >= 0.6f) {
                    Pattern(Kind.WEEKLY, setOf(top.key), minutes, confidence, median)
                } else if (confidence >= 0.5f) {
                    // steady cadence but wandering weekday: weak weekly signal
                    Pattern(
                        Kind.WEEKLY,
                        setOf(isoDayOfWeek(dates.first(), zone)),
                        minutes,
                        confidence * 0.5f,
                        median
                    )
                } else {
                    Pattern(Kind.IRREGULAR, emptySet(), minutes, confidence, median)
                }
            }
            else -> Pattern(Kind.SPARSE, emptySet(), minutes, confidence, median)
        }
    }

    /**
     * The next expected release at/after roughly now, or null when the
     * pattern isn't predictable enough to plan around (the engine then
     * falls back to checkpoints + the daily baseline).
     */
    fun nextExpectedMs(
        pattern: Pattern,
        lastPubMs: Long,
        nowMs: Long,
        zone: TimeZone = TimeZone.getDefault()
    ): Long? {
        if (pattern.kind != Kind.DAILY && pattern.kind != Kind.WEEKLY) return null
        if (pattern.confidence < 0.4f) return null
        if (lastPubMs <= 0) return null
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = lastPubMs
        cal.set(Calendar.HOUR_OF_DAY, (pattern.minutesOfDay / 60).coerceIn(0, 23))
        cal.set(Calendar.MINUTE, (pattern.minutesOfDay % 60).coerceIn(0, 59))
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        var guard = 0
        while (guard++ < 400) {
            val candidate = cal.timeInMillis
            val validDay = pattern.daysOfWeek.isEmpty() ||
                isoDayOfWeek(candidate, zone) in pattern.daysOfWeek
            val afterLastPub = candidate > lastPubMs + 60_000L
            val notLongMissed = candidate + MISSED_GRACE_MS >= nowMs
            if (validDay && afterLastPub && notLongMissed) return candidate
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return null
    }

    fun minutesOfDay(ms: Long, zone: TimeZone): Int {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = ms
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    /** Calendar's SUNDAY=1..SATURDAY=7 mapped to ISO Mon=1..Sun=7. */
    fun isoDayOfWeek(ms: Long, zone: TimeZone): Int {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = ms
        return ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
    }
}
