package com.stepcast.app.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.stepcast.app.data.Episode

/**
 * Cast framework configuration (declared in the manifest). The default
 * media receiver plays plain audio URLs — all a podcast needs.
 * Stepcast's own media notification stays in charge, so the Cast
 * framework's notification is turned off.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(
                com.google.android.gms.cast.CastMediaControlIntent
                    .DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
            )
            .setCastMediaOptions(
                CastMediaOptions.Builder().setNotificationOptions(null).build()
            )
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

@OptIn(UnstableApi::class)
object CastSupport {

    /**
     * The shared CastContext, or null on devices without Google Play
     * services (or when it fails to init) — casting just isn't offered.
     */
    fun castContext(context: Context): CastContext? = runCatching {
        CastContext.getSharedInstance(context.applicationContext)
    }.getOrNull()

    /**
     * A receiver can only fetch from the internet: downloaded episodes cast
     * from their ORIGINAL enclosure URL; local-folder files can't be cast.
     */
    fun castItem(episode: Episode, podcastTitle: String?, artwork: String?): MediaItem? {
        if (episode.isLocalFile) return null
        val url = episode.audioUrl.takeIf { it.startsWith("http") } ?: return null
        return MediaItem.Builder()
            .setMediaId(episode.id.toString())
            .setUri(url)
            .setMimeType(mimeFor(url))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.title)
                    .setArtist(podcastTitle)
                    .setArtworkUri((episode.imageUrl ?: artwork)?.let(android.net.Uri::parse))
                    .build()
            )
            .build()
    }

    private fun mimeFor(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m4a") || path.endsWith(".mp4") || path.endsWith(".aac") ->
                MimeTypes.AUDIO_MP4
            path.endsWith(".ogg") || path.endsWith(".opus") -> MimeTypes.AUDIO_OGG
            else -> MimeTypes.AUDIO_MPEG
        }
    }
}
