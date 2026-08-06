package com.stepcast.app.ui

import androidx.core.text.HtmlCompat
import java.text.DateFormat
import java.util.Date

/**
 * Feed prose (show/episode descriptions) as displayable plain text.
 *
 * Feeds put real HTML in these fields, so anything that renders one raw
 * shows literal `<p>`/`<em>`/`&nbsp;`/`<a href=...>` markup to the user.
 *
 * Two wrinkles this handles:
 *  - Many feeds (Mixcloud/SoundCloud-style tracklists especially) rely on
 *    bare newlines for line breaks instead of `<br>`/`<p>`. HTML parsing
 *    correctly collapses raw \n into ordinary whitespace per spec, so a
 *    plain fromHtml() flattens the whole thing into one run-on paragraph;
 *    promoting literal newlines to `<br>` first keeps both kinds of break.
 *  - That promotion can double up where a source `<br>` is followed by its
 *    own literal newline, so runs of blank lines collapse to one gap.
 */
fun feedHtmlToText(raw: String): String {
    if (raw.isBlank()) return ""
    val withExplicitBreaks = raw.replace(Regex("\r\n|\r|\n"), "<br>")
    return HtmlCompat.fromHtml(withExplicitBreaks, HtmlCompat.FROM_HTML_MODE_COMPACT)
        .toString()
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

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
