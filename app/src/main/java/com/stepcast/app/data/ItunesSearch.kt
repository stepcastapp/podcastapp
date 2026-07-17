package com.stepcast.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

data class SearchResult(
    val title: String,
    val author: String,
    val feedUrl: String,
    val imageUrl: String?
)

/** Podcast directory search backed by the iTunes Search API. */
class ItunesSearch(private val http: OkHttpClient = OkHttpClient()) {

    /**
     * Apple's top-podcasts chart. The chart API doesn't include feed URLs,
     * so the chart ids are chained into a lookup call; chart order is kept.
     */
    suspend fun trending(limit: Int = 25): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val chartsUrl = "https://rss.applemarketingtools.com/api/v2/us/" +
                "podcasts/top/$limit/podcasts.json"
            val chart = http.newCall(Request.Builder().url(chartsUrl).build())
                .execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Charts failed: HTTP ${response.code}")
                    }
                    JSONObject(response.body?.string().orEmpty())
                }
            val entries = chart.optJSONObject("feed")?.optJSONArray("results")
                ?: return@withContext emptyList()
            val ids = buildList {
                for (i in 0 until entries.length()) {
                    entries.optJSONObject(i)?.optString("id")
                        ?.takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
            if (ids.isEmpty()) return@withContext emptyList()

            val lookupUrl = "https://itunes.apple.com/lookup".toHttpUrl().newBuilder()
                .addQueryParameter("id", ids.joinToString(","))
                .addQueryParameter("entity", "podcast")
                .build()
            val byId = HashMap<String, SearchResult>()
            http.newCall(Request.Builder().url(lookupUrl).build())
                .execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Lookup failed: HTTP ${response.code}")
                    }
                    val json = JSONObject(response.body?.string().orEmpty())
                    val results = json.optJSONArray("results")
                        ?: return@withContext emptyList()
                    for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i) ?: continue
                        val feedUrl = item.optString("feedUrl")
                        if (feedUrl.isEmpty()) continue
                        byId[item.optLong("collectionId").toString()] = SearchResult(
                            title = item.optString("collectionName", "(untitled)"),
                            author = item.optString("artistName"),
                            feedUrl = feedUrl,
                            imageUrl = item.optString("artworkUrl600")
                                .ifEmpty { item.optString("artworkUrl100") }
                                .ifEmpty { null }
                        )
                    }
                }
            // distinct Apple listings can share one feed URL (Spreaker et al.
            // relist shows) — feedUrl keys the result rows, so dupes crash
            ids.mapNotNull { byId[it] }.distinctBy { it.feedUrl }
        }

    suspend fun search(term: String, limit: Int = 30): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
                .addQueryParameter("term", term)
                .addQueryParameter("media", "podcast")
                .addQueryParameter("entity", "podcast")
                .addQueryParameter("limit", limit.toString())
                .build()
            val request = Request.Builder().url(url).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Search failed: HTTP ${response.code}")
                val json = JSONObject(response.body?.string().orEmpty())
                val results = json.optJSONArray("results") ?: return@withContext emptyList()
                buildList {
                    for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i) ?: continue
                        val feedUrl = item.optString("feedUrl")
                        if (feedUrl.isEmpty()) continue
                        add(
                            SearchResult(
                                title = item.optString("collectionName", "(untitled)"),
                                author = item.optString("artistName"),
                                feedUrl = feedUrl,
                                imageUrl = item.optString("artworkUrl600")
                                    .ifEmpty { item.optString("artworkUrl100") }
                                    .ifEmpty { null }
                            )
                        )
                    }
                }.distinctBy { it.feedUrl } // same-feed relistings collapse
            }
        }
}
