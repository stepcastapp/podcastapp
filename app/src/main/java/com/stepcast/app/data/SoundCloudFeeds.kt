package com.stepcast.app.data

/**
 * SoundCloud profile links → the artist's PUBLIC RSS feed.
 *
 * Every SoundCloud account has a feed at
 * `feeds.soundcloud.com/users/soundcloud:users:<id>/sounds.rss`, keyed by a
 * numeric id the profile page carries as `soundcloud://users:<id>` (its
 * app-link meta tag). The feed holds only the tracks the artist chose to
 * "include in RSS" — often their mixes/shows rather than every release,
 * and sometimes nothing. That official feed is the only route used: no
 * scraped API keys, nothing outside what SoundCloud publishes for podcast
 * apps.
 *
 * The string logic here is pure (JVM-tested); the fetching lives in
 * [PodcastRepository.resolveFeedUrl].
 */
object SoundCloudFeeds {

    const val FEED_HOST = "feeds.soundcloud.com"

    /** First path segments that are SoundCloud's own pages, not artists. */
    private val RESERVED = setOf(
        "discover", "search", "stream", "upload", "you", "pages", "charts",
        "settings", "notifications", "messages", "people", "terms-of-use",
        "imprint", "jobs", "pro", "popular", "tags", "mobile", "signin",
        "logout", "home", "feed", "creators", "artists", "connect"
    )

    private fun hostOf(url: String): String? =
        Regex("^https?://([^/?#]+)", RegexOption.IGNORE_CASE).find(url.trim())
            ?.groupValues?.get(1)?.lowercase()?.substringBefore(':')

    /** soundcloud.com / www. / m. — the pages that name an artist in their path. */
    fun isSoundCloudPage(url: String): Boolean =
        hostOf(url) in setOf("soundcloud.com", "www.soundcloud.com", "m.soundcloud.com")

    /** on.soundcloud.com/… share links: a redirect to the real page. */
    fun isShortLink(url: String): Boolean = hostOf(url) == "on.soundcloud.com"

    fun isFeedUrl(url: String): Boolean = hostOf(url) == FEED_HOST

    /**
     * The artist's profile URL for any page of theirs — profile, a track,
     * a playlist, /tracks — or null when the path isn't an artist.
     * `soundcloud.com/lane8/fall-mixtape?si=…` → `https://soundcloud.com/lane8`.
     */
    fun profileUrl(url: String): String? {
        if (!isSoundCloudPage(url)) return null
        val path = url.trim()
            .replaceFirst(Regex("^https?://[^/?#]+", RegexOption.IGNORE_CASE), "")
            .substringBefore('?').substringBefore('#')
        val artist = path.split('/').firstOrNull { it.isNotBlank() } ?: return null
        if (artist.lowercase() in RESERVED) return null
        if (!artist.matches(Regex("[A-Za-z0-9_-]+"))) return null
        return "https://soundcloud.com/$artist"
    }

    /** The numeric user id from a profile page's HTML. */
    fun userIdIn(html: String): Long? =
        Regex("soundcloud://users:(\\d+)").find(html)?.groupValues?.get(1)?.toLongOrNull()

    fun feedUrlFor(userId: Long): String =
        "https://$FEED_HOST/users/soundcloud:users:$userId/sounds.rss"
}
