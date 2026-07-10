package com.stepcast.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DescriptionChaptersTest {

    @Test
    fun parsesDjMixTracklist() {
        // the Radio Wonderland shape: prose, then "Title MM:SS" lines
        val notes = """
            Alison drops new music from Skrillex and more!
            Don't forget to rate & review on all of your favorite podcast apps!
            Tracklist:
            RADIO WONDERLAND OPENER 00:00
            Peggy Gou & Ayra Starr - Wo, Man 00:42
            Austin Millz & Snakehips ft. Tkay Maidza - PLZ HOLD 04:25
            Noer the Boy - Stress Test 06:43
            Skrillex, Naisha & BEAM - Diwali 09:41
        """.trimIndent()
        val chapters = DescriptionChapters.parse(notes)
        assertEquals(5, chapters.size)
        assertEquals(Chapter(0, "RADIO WONDERLAND OPENER"), chapters[0])
        assertEquals(Chapter(42_000, "Peggy Gou & Ayra Starr - Wo, Man"), chapters[1])
        assertEquals(
            Chapter(4 * 60_000L + 25_000, "Austin Millz & Snakehips ft. Tkay Maidza - PLZ HOLD"),
            chapters[2]
        )
        assertEquals(Chapter(9 * 60_000L + 41_000, "Skrillex, Naisha & BEAM - Diwali"), chapters[4])
    }

    @Test
    fun parsesLeadingTimestampsAndHours() {
        val notes = """
            00:00 - Intro
            12:30 - Interview begins
            1:02:03 - Listener questions
        """.trimIndent()
        val chapters = DescriptionChapters.parse(notes)
        assertEquals(3, chapters.size)
        assertEquals(Chapter(0, "Intro"), chapters[0])
        assertEquals(Chapter(12 * 60_000L + 30_000, "Interview begins"), chapters[1])
        assertEquals(Chapter(3_723_000, "Listener questions"), chapters[2])
    }

    @Test
    fun htmlBreaksBecomeLineBoundaries() {
        val notes = "Tracklist:<br>Intro 00:00<br/>Track One 03:15<br>" +
            "Track Two &amp; Friends 07:40"
        val chapters = DescriptionChapters.parse(notes)
        assertEquals(3, chapters.size)
        assertEquals("Track Two & Friends", chapters[2].title)
    }

    @Test
    fun proseWithScatteredTimesIsNotAChapterList() {
        // fewer than three anchored stamps → no synthetic chapters
        assertTrue(
            DescriptionChapters.parse(
                "We recorded this at 10:30 in the morning. Great chat!"
            ).isEmpty()
        )
        // non-increasing sequences are rejected
        assertTrue(
            DescriptionChapters.parse(
                "a 10:00\nb 05:00\nc 20:00"
            ).isEmpty()
        )
        assertTrue(DescriptionChapters.parse(null).isEmpty())
        assertTrue(DescriptionChapters.parse("").isEmpty())
    }
}
