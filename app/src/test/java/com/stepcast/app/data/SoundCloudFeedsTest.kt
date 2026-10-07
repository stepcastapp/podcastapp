package com.stepcast.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundCloudFeedsTest {

    @Test
    fun anyArtistPageReducesToTheProfile() {
        val profile = "https://soundcloud.com/thisneverhappenedlabel"
        assertEquals(profile, SoundCloudFeeds.profileUrl(profile))
        assertEquals(profile, SoundCloudFeeds.profileUrl("$profile/"))
        assertEquals(profile, SoundCloudFeeds.profileUrl("$profile/tracks"))
        assertEquals(
            profile,
            SoundCloudFeeds.profileUrl("https://m.soundcloud.com/thisneverhappenedlabel/fall2026?si=abc#t=1")
        )
        assertEquals(profile, SoundCloudFeeds.profileUrl("http://www.SoundCloud.com/thisneverhappenedlabel"))
    }

    @Test
    fun soundCloudsOwnPagesAreNotArtists() {
        assertNull(SoundCloudFeeds.profileUrl("https://soundcloud.com/discover"))
        assertNull(SoundCloudFeeds.profileUrl("https://soundcloud.com/search?q=lane8"))
        assertNull(SoundCloudFeeds.profileUrl("https://soundcloud.com/"))
        assertNull(SoundCloudFeeds.profileUrl("https://example.com/thisneverhappenedlabel"))
    }

    @Test
    fun hostsAreRecognised() {
        assertTrue(SoundCloudFeeds.isShortLink("https://on.soundcloud.com/AbC123"))
        assertTrue(SoundCloudFeeds.isSoundCloudPage("https://soundcloud.com/x"))
        assertFalse(SoundCloudFeeds.isSoundCloudPage("https://feeds.soundcloud.com/users/x/sounds.rss"))
        assertTrue(SoundCloudFeeds.isFeedUrl("https://feeds.soundcloud.com/users/x/sounds.rss"))
        // an ordinary feed URL is left alone
        assertFalse(SoundCloudFeeds.isSoundCloudPage("https://feeds.megaphone.fm/abc"))
    }

    @Test
    fun userIdComesFromTheAppLinkTag() {
        val html = """<meta property="al:android:url" content="soundcloud://users:16059310">"""
        assertEquals(16059310L, SoundCloudFeeds.userIdIn(html))
        assertNull(SoundCloudFeeds.userIdIn("<html>nothing here</html>"))
        assertEquals(
            "https://feeds.soundcloud.com/users/soundcloud:users:16059310/sounds.rss",
            SoundCloudFeeds.feedUrlFor(16059310L)
        )
    }
}
