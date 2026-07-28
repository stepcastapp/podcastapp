package com.stepcast.app.ui

import java.text.DateFormat
import java.util.Date

/**
 * One date/duration style for every episode row. Before this, four screens
 * used four formats — and a two-hour episode read "120 min".
 */
object Formatters {

    /** "1h 45m" / "2h" / "38 min"; empty for unknown. */
    fun duration(ms: Long): String {
        if (ms <= 0) return ""
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            totalMin > 0 -> "$totalMin min"
            else -> "<1 min"
        }
    }

    fun date(ms: Long): String =
        if (ms <= 0) "" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

    fun dateTime(ms: Long): String = if (ms <= 0) {
        ""
    } else {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
    }

    /** "date • duration", omitting whichever half is unknown. */
    fun episodeMeta(pubDateMs: Long, durationMs: Long): String =
        listOf(date(pubDateMs), duration(durationMs))
            .filter { it.isNotEmpty() }
            .joinToString(" • ")
}
