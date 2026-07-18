package com.stepcast.app.sync

import java.util.Calendar
import java.util.TimeZone

/**
 * The schedule's brain: given a show's rule, its inferred release pattern,
 * and the global config (checkpoints + quiet hours), decides whether a
 * check is due now and when the next one should happen. Pure and
 * zone-parameterized for deterministic unit tests.
 *
 * The paradigm: a schedule is a set of PROMISES with clock times, not
 * rolling intervals. Every element here answers "next check at TIME,
 * because REASON."
 *
 *  - AUTO (default): fresh by every enabled checkpoint, checked shortly
 *    after the show's expected release, and at least once a day.
 *  - Pinned rules (HOURLY / DAILY_AT / WEEKLY_AT / MANUAL) do exactly what
 *    they say and ignore checkpoints — power overrides are literal.
 *  - Quiet hours suppress all automatic checks; anything that would land
 *    inside slides to the quiet end.
 */
object ScheduleEngine {

    const val MODE_AUTO = 0
    const val MODE_HOURLY = 1

    /** scheduleParam = minutes after midnight. */
    const val MODE_DAILY_AT = 2

    /** scheduleParam = isoDayOfWeek * 1440 + minutes after midnight. */
    const val MODE_WEEKLY_AT = 3
    const val MODE_MANUAL = 4

    const val HOURLY_MS = 55 * 60_000L
    const val RELEASE_WINDOW_MS = 3 * 3_600_000L
    const val RELEASE_RECHECK_MS = 50 * 60_000L
    const val BASELINE_MS = 24 * 3_600_000L
    const val CHECKPOINT_STALE_MS = 30 * 60_000L
    private const val DAY_MS = 86_400_000L

    data class Config(
        /** Enabled checkpoint times, minutes after midnight. */
        val checkpointMinutes: List<Int>,
        val quietEnabled: Boolean,
        val quietStartMinutes: Int,
        val quietEndMinutes: Int
    )

    enum class Reason { CHECKPOINT, RELEASE, HOURLY, DAILY, WEEKLY, BASELINE }

    data class NextCheck(val timeMs: Long, val reason: Reason)

    fun isDue(
        mode: Int,
        param: Int,
        lastRefreshedMs: Long,
        expectedReleaseMs: Long?,
        nowMs: Long,
        cfg: Config,
        zone: TimeZone = TimeZone.getDefault()
    ): Boolean {
        if (mode == MODE_MANUAL) return false
        if (cfg.quietEnabled && inQuiet(nowMs, cfg, zone)) return false
        return when (mode) {
            MODE_HOURLY -> nowMs - lastRefreshedMs >= HOURLY_MS
            MODE_DAILY_AT ->
                RefreshSchedule.isDue(lastRefreshedMs, 24, param, nowMs, zone)
            MODE_WEEKLY_AT ->
                lastRefreshedMs < latestWeeklySlotMs(param, nowMs, zone)
            else -> { // AUTO
                val checkpointSlot = cfg.checkpointMinutes.maxOfOrNull {
                    RefreshSchedule.latestSlotMs(it, 24, nowMs, zone)
                }
                val checkpointDue = checkpointSlot != null &&
                    lastRefreshedMs < checkpointSlot &&
                    nowMs - lastRefreshedMs >= CHECKPOINT_STALE_MS
                val releaseDue = expectedReleaseMs != null &&
                    nowMs >= expectedReleaseMs &&
                    nowMs <= expectedReleaseMs + RELEASE_WINDOW_MS &&
                    nowMs - lastRefreshedMs >= RELEASE_RECHECK_MS
                val baselineDue = nowMs - lastRefreshedMs >= BASELINE_MS
                checkpointDue || releaseDue || baselineDue
            }
        }
    }

    /**
     * When this show should next be checked, and why. Null for MANUAL.
     * Quiet hours slide the time to the quiet end.
     */
    fun nextCheck(
        mode: Int,
        param: Int,
        lastRefreshedMs: Long,
        expectedReleaseMs: Long?,
        nowMs: Long,
        cfg: Config,
        zone: TimeZone = TimeZone.getDefault()
    ): NextCheck? {
        if (mode == MODE_MANUAL) return null
        val raw = when (mode) {
            MODE_HOURLY ->
                NextCheck(lastRefreshedMs + HOURLY_MS, Reason.HOURLY)
            MODE_DAILY_AT -> NextCheck(
                RefreshSchedule.latestSlotMs(param, 24, nowMs, zone) + DAY_MS,
                Reason.DAILY
            )
            MODE_WEEKLY_AT -> NextCheck(
                latestWeeklySlotMs(param, nowMs, zone) + 7 * DAY_MS,
                Reason.WEEKLY
            )
            else -> {
                val candidates = buildList {
                    // each checkpoint's next upcoming slot; min wins below
                    cfg.checkpointMinutes.forEach {
                        add(
                            NextCheck(
                                RefreshSchedule.latestSlotMs(it, 24, nowMs, zone) +
                                    DAY_MS,
                                Reason.CHECKPOINT
                            )
                        )
                    }
                    expectedReleaseMs
                        ?.takeIf { it + RELEASE_WINDOW_MS >= nowMs }
                        ?.let { add(NextCheck(maxOf(it, nowMs), Reason.RELEASE)) }
                    add(NextCheck(lastRefreshedMs + BASELINE_MS, Reason.BASELINE))
                }
                candidates.minByOrNull { it.timeMs }
                    ?: NextCheck(lastRefreshedMs + BASELINE_MS, Reason.BASELINE)
            }
        }
        val at = maxOf(raw.timeMs, nowMs)
        val slid = if (cfg.quietEnabled) quietEndAfter(at, cfg, zone) else at
        return NextCheck(slid, raw.reason)
    }

    /** The most recent weekly slot (ISO dow + minutes) at or before now. */
    fun latestWeeklySlotMs(param: Int, nowMs: Long, zone: TimeZone): Long {
        val dow = (param / 1440).coerceIn(1, 7)
        val minutes = (param % 1440).coerceIn(0, 1439)
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = nowMs
        cal.set(Calendar.HOUR_OF_DAY, minutes / 60)
        cal.set(Calendar.MINUTE, minutes % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        // ISO Mon=1..Sun=7 -> Calendar SUNDAY=1..SATURDAY=7
        val calDow = (dow % 7) + 1
        var guard = 0
        while (cal.get(Calendar.DAY_OF_WEEK) != calDow && guard++ < 8) {
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        if (cal.timeInMillis > nowMs) cal.add(Calendar.DAY_OF_MONTH, -7)
        return cal.timeInMillis
    }

    fun inQuiet(ms: Long, cfg: Config, zone: TimeZone): Boolean {
        if (!cfg.quietEnabled) return false
        val m = ReleasePattern.minutesOfDay(ms, zone)
        val start = cfg.quietStartMinutes
        val end = cfg.quietEndMinutes
        return if (start <= end) m in start until end else (m >= start || m < end)
    }

    /** First non-quiet instant at or after [ms]. */
    fun quietEndAfter(ms: Long, cfg: Config, zone: TimeZone): Long {
        if (!inQuiet(ms, cfg, zone)) return ms
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = ms
        cal.set(Calendar.HOUR_OF_DAY, cfg.quietEndMinutes / 60)
        cal.set(Calendar.MINUTE, cfg.quietEndMinutes % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= ms) cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }
}
