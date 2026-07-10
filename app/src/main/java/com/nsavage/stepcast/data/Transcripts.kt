package com.nsavage.stepcast.data

import org.json.JSONObject

/** One transcript segment. [startMs] -1 means untimed (plain-text transcript). */
data class TranscriptCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/**
 * Parses Podcasting 2.0 transcripts: WebVTT, SRT, the Podcast Index JSON
 * format, and plain text as a last resort. Pure Kotlin/JVM (org.json aside)
 * so it stays unit-testable off-device. Untimed transcripts come back as
 * paragraph cues with startMs = -1: displayable, but no follow/seek.
 */
object Transcripts {

    /** Lower = better supported. Used to pick among an item's alternatives. */
    fun formatRank(type: String): Int = when {
        type.contains("vtt", ignoreCase = true) -> 0
        type.contains("srt", ignoreCase = true) ||
            type.contains("subrip", ignoreCase = true) -> 1
        type.contains("json", ignoreCase = true) -> 2
        type.contains("plain", ignoreCase = true) -> 3
        type.isEmpty() -> 8 // "nothing yet" placeholder must lose to anything real
        type.contains("html", ignoreCase = true) -> 7
        else -> 5
    }

    fun parse(body: String, type: String?): List<TranscriptCue> {
        val text = body.trim().removePrefix("﻿")
        if (text.isEmpty()) return emptyList()
        val t = type.orEmpty()
        return when {
            t.contains("json", ignoreCase = true) ||
                text.startsWith("{") -> parseJson(text)
            t.contains("vtt", ignoreCase = true) ||
                text.startsWith("WEBVTT") -> parseTimed(text)
            t.contains("srt", ignoreCase = true) ||
                t.contains("subrip", ignoreCase = true) ||
                SRT_FIRST_BLOCK.containsMatchIn(text) -> parseTimed(text)
            text.contains("-->") -> parseTimed(text)
            else -> parsePlain(text)
        }
    }

    // "HH:MM:SS.mmm --> HH:MM:SS.mmm" (VTT) or with commas (SRT); hours optional
    private val TIME_LINE = Regex(
        """(\d{1,2}:)?\d{1,2}:\d{2}[.,]\d{1,3}\s*-->\s*((\d{1,2}:)?\d{1,2}:\d{2}[.,]\d{1,3})"""
    )
    private val SRT_FIRST_BLOCK = Regex("""^\d+\s*\r?\n\d{1,2}:\d{2}:\d{2},""")
    private val TAGS = Regex("<[^>]*>")

    /** Handles both VTT and SRT — same cue shape, different time separator. */
    private fun parseTimed(text: String): List<TranscriptCue> {
        val cues = mutableListOf<TranscriptCue>()
        var start = -1L
        var end = -1L
        val lines = StringBuilder()
        fun flush() {
            val cueText = TAGS.replace(lines.toString(), "").trim()
            if (start >= 0 && cueText.isNotEmpty()) {
                cues += TranscriptCue(start, end, cueText)
            }
            start = -1; end = -1; lines.setLength(0)
        }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val time = TIME_LINE.find(line)
            when {
                time != null -> {
                    flush()
                    val parts = line.split("-->")
                    start = parseTimestampMs(parts[0].trim()) ?: -1
                    end = parts.getOrNull(1)
                        ?.trim()?.substringBefore(' ')
                        ?.let(::parseTimestampMs) ?: -1
                }
                line.isEmpty() -> flush()
                // headers, cue numbers, and NOTE blocks aren't cue text
                line == "WEBVTT" || line.startsWith("NOTE") ||
                    line.startsWith("STYLE") ||
                    (start < 0 && line.all(Char::isDigit)) -> Unit
                start >= 0 -> {
                    if (lines.isNotEmpty()) lines.append(' ')
                    lines.append(line)
                }
            }
        }
        flush()
        return cues
    }

    /** "HH:MM:SS.mmm", "MM:SS,mmm", or "SS.mmm" → milliseconds. */
    fun parseTimestampMs(text: String): Long? {
        val parts = text.trim().replace(',', '.').split(":")
        if (parts.isEmpty() || parts.size > 3) return null
        return try {
            var totalSec = 0.0
            for (part in parts) totalSec = totalSec * 60 + part.toDouble()
            (totalSec * 1000).toLong()
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** Podcast Index JSON: {"segments":[{"startTime":1.5,"body":"…"},…]} */
    private fun parseJson(text: String): List<TranscriptCue> = try {
        val segments = JSONObject(text).optJSONArray("segments")
            ?: return emptyList()
        buildList {
            for (i in 0 until segments.length()) {
                val seg = segments.optJSONObject(i) ?: continue
                val body = seg.optString("body").trim()
                if (body.isEmpty()) continue
                val startSec = seg.optDouble("startTime", -1.0)
                val endSec = seg.optDouble("endTime", -1.0)
                add(
                    TranscriptCue(
                        startMs = if (startSec >= 0) (startSec * 1000).toLong() else -1,
                        endMs = if (endSec >= 0) (endSec * 1000).toLong() else -1,
                        text = body
                    )
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun parsePlain(text: String): List<TranscriptCue> =
        text.split(Regex("\n\\s*\n"))
            .map { it.replace('\n', ' ').trim() }
            .filter { it.isNotEmpty() }
            .map { TranscriptCue(startMs = -1, endMs = -1, text = it) }
}
