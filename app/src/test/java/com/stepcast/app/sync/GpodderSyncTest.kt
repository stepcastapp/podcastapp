package com.stepcast.app.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.stepcast.app.data.PodcastRepository
import com.stepcast.app.data.StepcastDatabase
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** One sync round against a fake Nextcloud gPodder Sync server. */
@RunWith(RobolectricTestRunner::class)
class GpodderSyncTest {

    private lateinit var server: MockWebServer
    private lateinit var db: StepcastDatabase
    private lateinit var repo: PodcastRepository
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        server = MockWebServer().apply { start() }
        db = Room.inMemoryDatabaseBuilder(context, StepcastDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = PodcastRepository(db, context, OkHttpClient())
        GpodderSync.saveConfig(
            context,
            GpodderSync.Config(
                GpodderSync.PROVIDER_NEXTCLOUD,
                server.url("/").toString(), "me", "secret"
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    @Test
    fun firstSyncPushesLocalShowsAndAppliesRemoteProgress() = runBlocking {
        // a local show with one episode (the feed server is the same mock)
        val feedUrl = server.url("/feed").toString()
        val id = repo.importPodcastStub(feedUrl, "Show", null, null, 0, 0)
        server.enqueue(
            MockResponse().setBody(
                "<rss><channel><title>Show</title><item><title>E1</title><guid>g1</guid>" +
                    "<enclosure url=\"https://x/1.mp3\" type=\"audio/mpeg\"/></item></channel></rss>"
            )
        )
        repo.refresh(id)
        server.takeRequest()

        // 1. GET subscriptions: nothing remote yet
        server.enqueue(MockResponse().setBody("""{"add":[],"remove":[],"timestamp":100}"""))
        // 2. POST subscriptions (our show)
        server.enqueue(MockResponse().setBody("""{"timestamp":101}"""))
        // 3. GET episode actions: another device listened to 10 minutes
        server.enqueue(
            MockResponse().setBody(
                JSONObject()
                    .put(
                        "actions",
                        JSONArray().put(
                            JSONObject()
                                .put("podcast", feedUrl).put("episode", "https://x/1.mp3")
                                .put("guid", "g1").put("action", "play")
                                .put("timestamp", "2026-07-27T10:00:00")
                                .put("position", 600).put("total", 3600)
                        )
                    )
                    .put("timestamp", 200)
                    .toString()
            )
        )

        val error = GpodderSync.syncNow(context, repo)
        assertNull(error)

        val getSubs = server.takeRequest()
        assertTrue(getSubs.path!!.startsWith("/index.php/apps/gpoddersync/subscriptions?since=0"))
        assertTrue(getSubs.getHeader("Authorization")!!.startsWith("Basic "))
        val postSubs = server.takeRequest()
        assertEquals("POST", postSubs.method)
        val pushed = JSONObject(postSubs.body.readUtf8())
        assertEquals(feedUrl, pushed.getJSONArray("add").getString(0))
        assertEquals(0, pushed.getJSONArray("remove").length())
        server.takeRequest() // GET actions

        val ep = db.episodeDao().listForPodcast(id).single()
        assertEquals(600_000L, ep.positionMs)
        // applied with the REMOTE time — not echoed as new local listening
        assertEquals(1785146400000L, ep.lastPlayedMs)
        assertEquals(0, server.requestCount - 4) // nothing pushed back
    }
}
