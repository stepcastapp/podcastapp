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
class ItunesSearch(
    private val http: OkHttpClient = Http.api,
    private val podcastIndex: PodcastIndexSearch = PodcastIndexSearch(http)
) {

    /**
     * Apple first, then Podcast Index results Apple didn't have (when a key
     * is configured). Either directory failing alone still returns the
     * other's results; both failing throws.
     */
    suspend fun search(term: String, limit: Int = 30): List<SearchResult> {
        val apple = runCatching { searchApple(term, limit) }
        val index = if (podcastIndex.enabled) {
            runCatching { podcastIndex.search(term, limit) }
        } else {
            Result.success(emptyList())
        }
        if (apple.isFailure && (index.isFailure || !podcastIndex.enabled)) {
            throw apple.exceptionOrNull()!!
        }
        val merged = apple.getOrDefault(emptyList()).toMutableList()
        val seen = merged.mapTo(HashSet()) { normalize(it.feedUrl) }
        index.getOrDefault(emptyList()).filterTo(merged) { seen.add(normalize(it.feedUrl)) }
        return merged
    }

    private fun normalize(url: String) =
        url.trim().substringAfter("://").removeSuffix("/").lowercase()

    /**
     * Apple's top-podcasts chart. The chart API doesn't include feed URLs,
     * so the chart ids are chained into a lookup call; chart order is kept.
     */
    suspend fun trending(limit: Int = 25): List<SearchResult> =
        withContext(Dispatchers.IO) {
            // the listener's own country's chart (a UK listener was shown US
            // shows); storefronts Apple doesn't chart fall back to US
            val chart = fetchChart(storefront(), limit)
                ?: fetchChart("us", limit)
                ?: throw IOException("Charts failed")
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
                .addQueryParameter("country", storefront())
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

    /** Two-letter storefront from the device region; "us" when unknown. */
    private fun storefront(): String =
        java.util.Locale.getDefault().country.lowercase(java.util.Locale.ROOT)
            .takeIf { it.length == 2 } ?: "us"

    private fun fetchChart(country: String, limit: Int): JSONObject? {
        val url = "https://rss.applemarketingtools.com/api/v2/$country/" +
            "podcasts/top/$limit/podcasts.json"
        return runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) null
                else JSONObject(response.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    private suspend fun searchApple(term: String, limit: Int): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
                .addQueryParameter("term", term)
                .addQueryParameter("media", "podcast")
                .addQueryParameter("entity", "podcast")
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("country", storefront())
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
