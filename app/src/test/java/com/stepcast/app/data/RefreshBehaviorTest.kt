package com.stepcast.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PodcastRepository.refresh against a real (in-memory) Room database and a
 * fake HTTP server: conditional GET, moved feeds, identity churn, and the
 * backup-state restore that waits for a feed's rows.
 */
@RunWith(RobolectricTestRunner::class)
class RefreshBehaviorTest {

    private lateinit var server: MockWebServer
    private lateinit var db: StepcastDatabase
    private lateinit var repo: PodcastRepository
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        db = Room.inMemoryDatabaseBuilder(context, StepcastDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = PodcastRepository(db, context, OkHttpClient())
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun rss(vararg items: Pair<String, String>) = """
        <rss version="2.0"><channel><title>Show</title>
        ${items.joinToString("") { (guid, url) ->
            "<item><title>T $guid</title><guid>$guid</guid>" +
                "<pubDate>Mon, 27 Jul 2026 10:00:00 +0000</pubDate>" +
                "<enclosure url=\"$url\" type=\"audio/mpeg\"/></item>"
        }}
        </channel></rss>
    """.trimIndent()

    /** A stub (never refreshed) with auto-download off, like a restored show. */
    private fun stub(path: String = "/feed"): Long = runBlocking {
        repo.importPodcastStub(server.url(path).toString(), "Show", null, null, 0, 0)
    }

    @Test
    fun unchangedFeedAnswers304AndSkipsTheParse() = runBlocking {
        val id = stub()
        server.enqueue(MockResponse().setBody(rss("g1" to "https://x/1.mp3")).setHeader("ETag", "\"v1\""))
        assertEquals(1, repo.refresh(id))
        server.takeRequest()

        server.enqueue(MockResponse().setResponseCode(304))
        assertEquals(0, repo.refresh(id))
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
        assertEquals(1, db.episodeDao().listForPodcast(id).size)
    }

    @Test
    fun permanentRedirectIsAdopted() = runBlocking {
        val id = stub("/old")
        server.enqueue(
            MockResponse().setResponseCode(301).setHeader("Location", server.url("/new").toString())
        )
        server.enqueue(MockResponse().setBody(rss("g1" to "https://x/1.mp3")))
        repo.refresh(id)
        assertEquals(server.url("/new").toString(), db.podcastDao().get(id)!!.feedUrl)
    }

    @Test
    fun temporaryRedirectIsNotAdopted() = runBlocking {
        val id = stub("/old")
        server.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", server.url("/cdn").toString())
        )
        server.enqueue(MockResponse().setBody(rss("g1" to "https://x/1.mp3")))
        repo.refresh(id)
        assertEquals(server.url("/old").toString(), db.podcastDao().get(id)!!.feedUrl)
    }

    @Test
    fun rotatedGuidKeepsListeningPosition() = runBlocking {
        val id = stub()
        server.enqueue(MockResponse().setBody(rss("g1" to "https://x/1.mp3")))
        repo.refresh(id)
        val ep = db.episodeDao().listForPodcast(id).single()
        repo.savePosition(ep.id, 90_000, 0)

        // same enclosure, new guid (CMS migration): rekeyed, not duplicated
        server.enqueue(MockResponse().setBody(rss("g1-new" to "https://x/1.mp3")))
        assertEquals(0, repo.refresh(id))
        val after = db.episodeDao().listForPodcast(id).single()
        assertEquals(ep.id, after.id)
        assertEquals("g1-new", after.guid)
        assertEquals(90_000L, after.positionMs)
    }

    @Test
    fun restoredStateLandsWhenTheFeedFirstRefreshes() = runBlocking {
        val id = stub()
        val url = db.podcastDao().get(id)!!.feedUrl
        EpisodeStateRestore.stage(
            context,
            listOf(
                EpisodeStateRestore.Entry(url, "g1", "", true, 1234L, 0, false),
                EpisodeStateRestore.Entry(url, "", "https://x/2.mp3", false, 0, 42_000, true)
            ),
            listOf(EpisodeStateRestore.QueueRef(url, "g2", "https://x/2.mp3"))
        )
        server.enqueue(
            MockResponse().setBody(rss("g1" to "https://x/1.mp3", "g2" to "https://x/2.mp3"))
        )
        repo.refresh(id)
        val byGuid = db.episodeDao().listForPodcast(id).associateBy { it.guid }
        assertTrue(byGuid.getValue("g1").played)
        assertEquals(1234L, byGuid.getValue("g1").playedAtMs)
        assertFalse(byGuid.getValue("g2").played)
        assertEquals(42_000L, byGuid.getValue("g2").positionMs)
        assertTrue(byGuid.getValue("g2").favorite)
        assertEquals(listOf(byGuid.getValue("g2").id), repo.queueSnapshot().map { it.id })
    }

    @Test
    fun restoreNeverOverwritesALocalPosition() = runBlocking {
        val id = stub()
        server.enqueue(MockResponse().setBody(rss("g1" to "https://x/1.mp3")))
        repo.refresh(id)
        val ep = db.episodeDao().listForPodcast(id).single()
        repo.savePosition(ep.id, 5_000, 0)
        repo.applyEpisodeStates(
            id,
            listOf(EpisodeStateRestore.Entry("u", "g1", "", false, 0, 99_000, false))
        )
        assertEquals(5_000L, db.episodeDao().get(ep.id)!!.positionMs)
    }
}
