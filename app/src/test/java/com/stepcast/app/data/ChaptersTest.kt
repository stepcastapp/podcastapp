package com.stepcast.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaptersTest {

    @Test
    fun serializeAndParseRoundTrip() {
        val chapters = listOf(
            Chapter(0, "Intro"),
            Chapter(95_500, "Sponsor: Widgets Inc"),
            Chapter(180_000, "Main topic | part two")
        )
        val parsed = Chapters.parse(Chapters.serialize(chapters))
        assertEquals(chapters, parsed)
    }

    @Test
    fun parseSortsByStart() {
        val parsed = Chapters.parse("60000|Later\n0|First")
        assertEquals(listOf(Chapter(0, "First"), Chapter(60_000, "Later")), parsed)
    }

    @Test
    fun parseIgnoresJsonMarkerAndGarbage() {
        assertTrue(Chapters.parse("json:https://example.com/c.json").isEmpty())
        assertTrue(Chapters.parse(null).isEmpty())
        assertEquals(
            listOf(Chapter(5_000, "ok")),
            Chapters.parse("notanumber|bad\n5000|ok\n|empty")
        )
    }

    @Test
    fun nptFormats() {
        assertEquals(3_723_500L, Chapters.parseNptMs("1:02:03.5"))
        assertEquals(125_000L, Chapters.parseNptMs("2:05"))
        assertEquals(90_500L, Chapters.parseNptMs("90.5"))
        assertEquals(0L, Chapters.parseNptMs("0"))
        assertNull(Chapters.parseNptMs(""))
        assertNull(Chapters.parseNptMs("abc"))
    }
}
