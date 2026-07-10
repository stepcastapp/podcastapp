package com.nsavage.stepcast.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptsTest {

    @Test
    fun `parses webvtt cues with tags stripped`() {
        val vtt = """
            WEBVTT

            NOTE this line is not a cue

            00:00:01.000 --> 00:00:04.200
            <v Speaker>Hello there.</v>

            00:01:00.500 --> 00:01:02.000
            Second cue
            spans two lines.
        """.trimIndent()
        val cues = Transcripts.parse(vtt, "text/vtt")
        assertEquals(2, cues.size)
        assertEquals(1_000, cues[0].startMs)
        assertEquals(4_200, cues[0].endMs)
        assertEquals("Hello there.", cues[0].text)
        assertEquals(60_500, cues[1].startMs)
        assertEquals("Second cue spans two lines.", cues[1].text)
    }

    @Test
    fun `parses srt with comma timestamps and cue numbers`() {
        val srt = """
            1
            00:00:05,000 --> 00:00:07,500
            First line.

            2
            01:02:03,250 --> 01:02:04,000
            After an hour.
        """.trimIndent()
        val cues = Transcripts.parse(srt, "application/srt")
        assertEquals(2, cues.size)
        assertEquals(5_000, cues[0].startMs)
        assertEquals("First line.", cues[0].text)
        assertEquals(3_723_250, cues[1].startMs)
    }

    @Test
    fun `format detection works without a type hint`() {
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi."
        assertEquals(1, Transcripts.parse(vtt, null).size)
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nHi."
        assertEquals(1, Transcripts.parse(srt, null).size)
    }

    @Test
    fun `plain text becomes untimed paragraph cues`() {
        val plain = "First paragraph\nstill first.\n\nSecond paragraph."
        val cues = Transcripts.parse(plain, "text/plain")
        assertEquals(2, cues.size)
        assertEquals(-1, cues[0].startMs)
        assertEquals("First paragraph still first.", cues[0].text)
        assertEquals("Second paragraph.", cues[1].text)
    }

    @Test
    fun `empty body yields no cues`() {
        assertTrue(Transcripts.parse("", "text/vtt").isEmpty())
        assertTrue(Transcripts.parse("   ", null).isEmpty())
    }

    @Test
    fun `timestamps parse all shapes`() {
        assertEquals(1_500L, Transcripts.parseTimestampMs("1.5"))
        assertEquals(61_000L, Transcripts.parseTimestampMs("01:01"))
        assertEquals(3_661_250L, Transcripts.parseTimestampMs("1:01:01.250"))
        assertEquals(3_661_250L, Transcripts.parseTimestampMs("1:01:01,250"))
        assertEquals(null, Transcripts.parseTimestampMs("nope"))
        assertEquals(null, Transcripts.parseTimestampMs("1:2:3:4"))
    }

    @Test
    fun `format rank prefers vtt then srt then json`() {
        assertTrue(
            Transcripts.formatRank("text/vtt") <
                Transcripts.formatRank("application/srt")
        )
        assertTrue(
            Transcripts.formatRank("application/srt") <
                Transcripts.formatRank("application/json")
        )
        assertTrue(
            Transcripts.formatRank("application/json") <
                Transcripts.formatRank("text/html")
        )
        // the "nothing chosen yet" empty placeholder loses to any real format
        assertTrue(
            Transcripts.formatRank("text/html") < Transcripts.formatRank("")
        )
    }
}
