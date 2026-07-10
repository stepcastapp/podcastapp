package com.nsavage.stepcast.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedUrlIntentsTest {

    @Test
    fun findsUrlInsideSharedText() {
        assertEquals(
            "https://example.com/feed.xml",
            FeedUrlIntents.firstUrlIn(
                "Check out this podcast! https://example.com/feed.xml great show"
            )
        )
        assertEquals(
            "http://old.example.com/rss",
            FeedUrlIntents.firstUrlIn("http://old.example.com/rss")
        )
        assertNull(FeedUrlIntents.firstUrlIn("no links here"))
        assertNull(FeedUrlIntents.firstUrlIn(null))
    }

    @Test
    fun rewritesClassicSchemes() {
        assertEquals(
            "https://example.com/rss",
            FeedUrlIntents.normalizeScheme("pcast://example.com/rss")
        )
        assertEquals(
            "https://example.com/rss",
            FeedUrlIntents.normalizeScheme("podcast://example.com/rss")
        )
        assertEquals(
            "https://example.com/rss",
            FeedUrlIntents.normalizeScheme("itpc://example.com/rss")
        )
        assertEquals(
            "https://example.com/rss",
            FeedUrlIntents.normalizeScheme("feed://example.com/rss")
        )
    }

    @Test
    fun stripsBareSchemePrefixes() {
        assertEquals(
            "https://example.com/rss",
            FeedUrlIntents.normalizeScheme("feed:https://example.com/rss")
        )
        assertEquals(
            "https://example.com/rss",
            FeedUrlIntents.normalizeScheme("pcast:https://example.com/rss")
        )
    }

    @Test
    fun passesPlainUrlsThrough() {
        assertEquals(
            "https://example.com/rss",
            FeedUrlIntents.normalizeScheme("https://example.com/rss")
        )
    }
}
