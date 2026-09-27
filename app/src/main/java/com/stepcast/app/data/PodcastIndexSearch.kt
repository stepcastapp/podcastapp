package com.stepcast.app.data

import com.stepcast.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

/**
 * Podcast Index (podcastindex.org): the open directory — independent
 * shows, Podcasting 2.0 feeds, and feeds Apple never listed. Search merges
 * its results after Apple's.
 *
 * Needs an API key + secret (free at api.podcastindex.org), supplied at
 * build time — Gradle properties `podcastIndexKey`/`podcastIndexSecret` or
 * env `PODCASTINDEX_KEY`/`PODCASTINDEX_SECRET` (GitHub secrets in CI).
 * Without them this is simply off.
 */
class PodcastIndexSearch(private val http: OkHttpClient = Http.api) {

    val enabled: Boolean
        get() = BuildConfig.PODCASTINDEX_KEY.isNotBlank() &&
            BuildConfig.PODCASTINDEX_SECRET.isNotBlank()

    suspend fun search(term: String, limit: Int = 30): List<SearchResult> =
        withContext(Dispatchers.IO) {
            if (!enabled) return@withContext emptyList()
            val url = "https://api.podcastindex.org/api/1.0/search/byterm".toHttpUrl()
                .newBuilder()
                .addQueryParameter("q", term)
                .addQueryParameter("max", limit.toString())
                .build()
            val now = (System.currentTimeMillis() / 1000).toString()
            val auth = sha1Hex(BuildConfig.PODCASTINDEX_KEY + BuildConfig.PODCASTINDEX_SECRET + now)
            val request = Request.Builder()
                .url(url)
                .header("X-Auth-Key", BuildConfig.PODCASTINDEX_KEY)
                .header("X-Auth-Date", now)
                .header("Authorization", auth)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Podcast Index: HTTP ${response.code}")
                val feeds = JSONObject(response.body?.string().orEmpty()).optJSONArray("feeds")
                    ?: return@withContext emptyList()
                buildList {
                    for (i in 0 until feeds.length()) {
                        val f = feeds.optJSONObject(i) ?: continue
                        // dead feeds (dead=1) are listed too; skip them
                        if (f.optInt("dead", 0) == 1) continue
                        val feedUrl = f.optString("url").takeIf { it.startsWith("http") } ?: continue
                        add(
                            SearchResult(
                                title = f.optString("title").ifBlank { "(untitled)" },
                                author = f.optString("author").ifBlank { f.optString("ownerName") },
                                feedUrl = feedUrl,
                                imageUrl = f.optString("artwork").ifBlank { f.optString("image") }
                                    .ifBlank { null }
                            )
                        )
                    }
                }
            }
        }

    private fun sha1Hex(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
