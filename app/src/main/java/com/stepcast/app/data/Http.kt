package com.stepcast.app.data

import com.stepcast.app.BuildConfig
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The app's HTTP clients. One connection pool and dispatcher shared by
 * feeds, chapters, transcripts, directory search and downloads — separate
 * clients each kept their own sockets and threads.
 */
object Http {

    /** Versioned, so hosts/analytics can tell Stepcast builds apart. */
    val USER_AGENT = "Stepcast/${BuildConfig.VERSION_NAME} (Android)"

    private val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            chain.proceed(
                if (request.header("User-Agent") == null) {
                    request.newBuilder().header("User-Agent", USER_AGENT).build()
                } else {
                    request
                }
            )
        }
        .build()

    /**
     * Feeds, chapters, transcripts, search. readTimeout is per READ — a host
     * trickling a byte every 29 s held a refresh slot indefinitely — so the
     * whole call is capped too.
     */
    val api: OkHttpClient = base.newBuilder()
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    /** Audio downloads: long by nature, so no whole-call cap; patient reads. */
    val downloads: OkHttpClient = base.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
}
