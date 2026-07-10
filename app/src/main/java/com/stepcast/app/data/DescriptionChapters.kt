package com.stepcast.app.data

/**
 * Synthetic chapters mined from show-notes tracklists — the DJ-mix pattern:
 *
 *     Tracklist:
 *     RADIO WONDERLAND OPENER 00:00
 *     Peggy Gou & Ayra Starr - Wo, Man 00:42
 *     ...
 *
 * Used only when an episode has no real (PSC / Podcasting 2.0) chapters.
 * A timestamp at the START or END of a line becomes a chapter whose title
 * is the rest of that line. Guards against false positives: at least three
 * hits, and the sequence must be (weakly) increasing — prose that happens
 * to mention a time won't produce a chapter list.
 */
object DescriptionChapters {

    // 0:42, 00:42, 1:02:03 — bounded so bare "2026" or prices don't match
    private val TIMESTAMP = Regex("""\b(?:(\d{1,2}):)?(\d{1,3}):(\d{2})\b""")
    private val LEADING_JUNK = Regex("""^[\s\-–—:.)\]]+""")
    private val TRAILING_JUNK = Regex("""[\s\-–—:.(\[]+$""")

    fun parse(description: String?): List<Chapter> {
        if (description.isNullOrBlank()) return emptyList()
        val lines = htmlToLines(description)
        val found = ArrayList<Chapter>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.length < 4 || trimmed.length > 200) continue
            val matches = TIMESTAMP.findAll(trimmed).toList()
            if (matches.isEmpty()) continue
            // only trust a stamp anchored to an end of the line
            val match = when {
                matches.last().range.last >= trimmed.length - 2 -> matches.last()
                matches.first().range.first <= 1 -> matches.first()
                else -> continue
            }
            val ms = toMs(match) ?: continue
            val title = trimmed.removeRange(match.range)
                .replace(LEADING_JUNK, "")
                .replace(TRAILING_JUNK, "")
                .trim()
            if (title.isEmpty()) continue
            found.add(Chapter(ms, title))
        }
        // demand a real list: 3+ entries, weakly increasing, starting <10min
        if (found.size < 3) return emptyList()
        val increasing = found.zipWithNext().all { (a, b) -> b.startMs >= a.startMs }
        if (!increasing) return emptyList()
        if (found.first().startMs > 10 * 60_000L) return emptyList()
        return found
    }

    private fun toMs(match: MatchResult): Long? {
        val (h, m, s) = match.destructured
        val hours = h.ifEmpty { "0" }.toLongOrNull() ?: return null
        val minutes = m.toLongOrNull() ?: return null
        val seconds = s.toLongOrNull() ?: return null
        if (seconds > 59) return null
        if (h.isNotEmpty() && minutes > 59) return null
        return ((hours * 60 + minutes) * 60 + seconds) * 1000
    }

    /** Crude HTML → text lines; <br>/<p>/<li> boundaries become newlines. */
    fun htmlToLines(text: String): List<String> {
        val withBreaks = text
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|li|div|h[1-6]|tr)>"), "\n")
            .replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
        return withBreaks.lines()
    }
}
