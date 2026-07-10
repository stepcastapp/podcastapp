package com.stepcast.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpmlSerializeTest {

    private fun podcast(title: String, url: String, folder: String? = null) =
        Podcast(feedUrl = url, title = title, folder = folder)

    @Test
    fun `categories become nested outlines`() {
        val xml = Opml.serialize(
            listOf(
                podcast("A", "https://a.example/rss", "News"),
                podcast("B", "https://b.example/rss", "News"),
                podcast("C", "https://c.example/rss")
            )
        )
        assertTrue(xml.contains("<outline text=\"News\" title=\"News\">"))
        assertTrue(xml.contains("xmlUrl=\"https://a.example/rss\""))
        assertTrue(xml.contains("xmlUrl=\"https://c.example/rss\""))
        assertTrue(xml.indexOf("https://c.example/rss") > xml.indexOf("</outline>"))
    }

    @Test
    fun `xml special characters are escaped`() {
        val xml = Opml.serialize(
            listOf(podcast("Q&A \"show\" <live>", "https://x.example/rss?a=1&b=2"))
        )
        assertTrue(xml.contains("Q&amp;A &quot;show&quot; &lt;live&gt;"))
        assertTrue(xml.contains("https://x.example/rss?a=1&amp;b=2"))
        assertFalse(xml.contains("<live>"))
    }

    @Test
    fun `local folders are excluded`() {
        val xml = Opml.serialize(
            listOf(
                Podcast(
                    feedUrl = "content://tree/x",
                    title = "Local",
                    localFolderUri = "content://tree/x"
                ),
                podcast("A", "https://a.example/rss")
            )
        )
        assertFalse(xml.contains("content://tree/x"))
        assertTrue(xml.contains("https://a.example/rss"))
    }
}
