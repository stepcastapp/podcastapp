package com.stepcast.app.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.stepcast.app.data.Http
import java.io.File

/**
 * Streaming plumbing for the player:
 *  - bytes already streamed are kept in a small LRU disk cache, so seeking
 *    back (or resuming a half-streamed episode later) doesn't re-download
 *  - HTTP goes through the app's OkHttp client (shared pool, the Stepcast
 *    User-Agent — hosts and podcast analytics see which app is playing
 *    instead of a generic ExoPlayer string)
 *  - file:// and content:// (downloads, local folders) bypass the cache
 *    entirely: DefaultDataSource only hands http(s) to the cached factory.
 */
@OptIn(UnstableApi::class)
object StreamCache {

    private const val MAX_BYTES = 150L * 1024 * 1024

    @Volatile
    private var cache: SimpleCache? = null

    /** One instance per process — SimpleCache locks its folder. */
    fun cache(context: Context): SimpleCache =
        cache ?: synchronized(this) {
            cache ?: SimpleCache(
                File(context.cacheDir, "stream_cache"),
                LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                StandaloneDatabaseProvider(context.applicationContext)
            ).also { cache = it }
        }

    fun mediaSourceFactory(context: Context): DefaultMediaSourceFactory {
        val http = OkHttpDataSource.Factory(Http.downloads)
            .setUserAgent(Http.USER_AGENT)
        val cached = CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(http)
            // a cache error must never stop playback — just stream
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(context, cached))
    }

    /**
     * Podcasts are long, low-bitrate, and listened to on flaky mobile links:
     * buffer up to three minutes ahead (a few MB at podcast bitrates) instead
     * of ExoPlayer's 50-second default, so a tunnel or a dead zone doesn't
     * stall the episode.
     */
    fun loadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 60_000,
            /* maxBufferMs = */ 180_000,
            /* bufferForPlaybackMs = */ 2_500,
            /* bufferForPlaybackAfterRebufferMs = */ 5_000
        )
        .build()

    /** Bytes currently held (Settings storage view). */
    fun sizeBytes(context: Context): Long = runCatching { cache(context).cacheSpace }.getOrDefault(0L)

    fun clear(context: Context) {
        val c = cache(context)
        runCatching { c.keys.toList().forEach { c.removeResource(it) } }
    }
}
