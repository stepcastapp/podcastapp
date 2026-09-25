package com.stepcast.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Runs the real android.util.Xml parser under Robolectric. */
@RunWith(RobolectricTestRunner::class)
class RssParserTest {

    private fun parse(xml: String) = RssParser.parse(xml.byteInputStream())

    private fun feed(items: String, channelExtra: String = "") = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
             xmlns:content="http://purl.org/rss/1.0/modules/content/"
             xmlns:podcast="https://podcastindex.org/namespace/1.0">
        <channel>
          <title>Show &amp; Tell</title>
          <description>About</description>
          <itunes:author>Host</itunes:author>
          <itunes:image href="https://example.com/art.jpg"/>
          $channelExtra
          $items
        </channel>
        </rss>
    """.trimIndent()

    @Test
    fun parsesChannelAndBasicItem() {
        val f = parse(
            feed(
                """
                <item>
                  <title>Episode 1</title>
                  <guid>g-1</guid>
                  <pubDate>Mon, 27 Jul 2026 10:00:00 +0000</pubDate>
                  <itunes:duration>1:02:03</itunes:duration>
                  <enclosure url="https://example.com/1.mp3" type="audio/mpeg" length="1"/>
                </item>
                """
            )
        )
        assertEquals("Show & Tell", f.title)
        assertEquals("Host", f.author)
        assertEquals("https://example.com/art.jpg", f.imageUrl)
        val ep = f.episodes.single()
        assertEquals("g-1", ep.guid)
        assertEquals("Episode 1", ep.title)
        assertEquals(1785146400000L, ep.pubDateMs)
        assertEquals((3600 + 120 + 3) * 1000L, ep.durationMs)
    }

    @Test
    fun htmlEntitiesInBareXmlDoNotKillTheFeed() {
        val f = parse(
            feed(
                """
                <item>
                  <title>Caf&eacute;&nbsp;talk &mdash; part 1</title>
                  <enclosure url="https://example.com/1.mp3" type="audio/mpeg"/>
                </item>
                """
            )
        )
        assertEquals("Café\u00A0talk — part 1", f.episodes.single().title)
    }

    @Test
    fun contentEncodedBeatsTeaserDescription() {
        val f = parse(
            feed(
                """
                <item>
                  <title>E</title>
                  <description>Short teaser</description>
                  <content:encoded><![CDATA[<p>Full <b>notes</b></p>]]></content:encoded>
                  <enclosure url="https://example.com/1.mp3" type="audio/mpeg"/>
                </item>
                """
            )
        )
        assertEquals("<p>Full <b>notes</b></p>", f.episodes.single().description)
    }

    @Test
    fun isoDatesAndDecimalDurations() {
        val f = parse(
            feed(
                """
                <item>
                  <title>E</title>
                  <pubDate>2026-07-27T10:00:00Z</pubDate>
                  <itunes:duration>3600.5</itunes:duration>
                  <enclosure url="https://example.com/1.mp3" type="audio/mpeg"/>
                </item>
                """
            )
        )
        val ep = f.episodes.single()
        assertEquals(1785146400000L, ep.pubDateMs)
        assertEquals(3_600_500L, ep.durationMs)
    }

    @Test
    fun zoneIsNeverDroppedByTheCachedFormat() {
        // prime the per-thread "last good" format with a zone-less date
        RssParser.parseDate("Mon, 27 Jul 2026 10:00:00")
        val withZone = RssParser.parseDate("Mon, 27 Jul 2026 10:00:00 +0500")
        assertEquals(1785146400000L - 5 * 3_600_000L, withZone)
    }

    @Test
    fun nonAudioEnclosuresAreSkippedAndFirstTitleWins() {
        val f = parse(
            feed(
                """
                <item>
                  <title>Video only</title>
                  <enclosure url="https://example.com/v.mp4" type="video/mp4"/>
                </item>
                <item>
                  <title>Real</title>
                  <source><title>Other</title></source>
                  <enclosure url="https://example.com/a.m4a" type=""/>
                </item>
                """
            )
        )
        val ep = f.episodes.single()
        assertEquals("Real", ep.title)
        // no <guid>: the enclosure URL is the identity
        assertEquals("https://example.com/a.m4a", ep.guid)
    }

    @Test
    fun podcasting20ChaptersTranscriptsAndNewFeedUrl() {
        val f = parse(
            feed(
                """
                <item>
                  <title>E</title>
                  <podcast:chapters url="https://example.com/c.json" type="application/json+chapters"/>
                  <podcast:transcript url="https://example.com/t.txt" type="text/plain"/>
                  <podcast:transcript url="https://example.com/t.vtt" type="text/vtt"/>
                  <enclosure url="https://example.com/1.mp3" type="audio/mpeg"/>
                </item>
                """,
                channelExtra = "<itunes:new-feed-url>https://new.example.com/feed</itunes:new-feed-url>"
            )
        )
        val ep = f.episodes.single()
        assertEquals(Chapters.JSON_PREFIX + "https://example.com/c.json", ep.chapters)
        assertEquals("https://example.com/t.vtt", ep.transcriptUrl)
        assertEquals("https://new.example.com/feed", f.newFeedUrl)
    }

    @Test
    fun brokenTailKeepsTheEpisodesThatParsed() {
        val xml = feed(
            """
            <item>
              <title>Good</title>
              <enclosure url="https://example.com/1.mp3" type="audio/mpeg"/>
            </item>
            <item><title>Broken &unknownentity; here</title>
            """
        ).substringBefore("</channel>")
        val f = parse(xml)
        assertEquals(listOf("Good"), f.episodes.map { it.title })
    }

    @Test
    fun missingDateIsZeroNotACrash() {
        assertEquals(0L, RssParser.parseDate(""))
        assertEquals(0L, RssParser.parseDate("not a date"))
        assertEquals(0L, RssParser.parseDuration("abc"))
        assertTrue(RssParser.parseDuration("45:00") == 2_700_000L)
        assertNull(parse(feed("")).newFeedUrl)
    }
}
