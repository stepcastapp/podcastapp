package com.nsavage.stepcast.data

data class Chapter(val startMs: Long, val title: String)

/**
 * Chapters are stored on the episode row as newline-separated
 * "startMs|title" lines, or "json:<url>" for Podcasting 2.0 JSON chapters
 * that haven't been fetched yet (resolved lazily, then cached in place).
 */
object Chapters {

    const val JSON_PREFIX = "json:"

    fun serialize(chapters: List<Chapter>): String =
        chapters.joinToString("\n") { "${it.startMs}|${it.title}" }

    fun parse(stored: String?): List<Chapter> {
        if (stored.isNullOrEmpty() || stored.startsWith(JSON_PREFIX)) return emptyList()
        return stored.lineSequence()
            .mapNotNull { line ->
                val split = line.indexOf('|')
                if (split <= 0) return@mapNotNull null
                line.substring(0, split).toLongOrNull()
                    ?.let { Chapter(it, line.substring(split + 1)) }
            }
            .sortedBy { it.startMs }
            .toList()
    }

    /** NPT timestamps: "HH:MM:SS.mmm", "MM:SS", or plain (fractional) seconds. */
    fun parseNptMs(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val parts = trimmed.split(":")
            val seconds = when (parts.size) {
                3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toDouble()
                2 -> parts[0].toLong() * 60 + parts[1].toDouble()
                1 -> parts[0].toDouble()
                else -> return null
            }
            (seconds * 1000).toLong()
        } catch (e: NumberFormatException) {
            null
        }
    }
}
