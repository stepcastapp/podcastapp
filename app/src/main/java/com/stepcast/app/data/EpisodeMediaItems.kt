package com.stepcast.app.data

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/**
 * THE MediaItem builder. The UI (PlayerConnection) and the service used to
 * carry near-duplicate builders whose metadata drifted (browse flags,
 * artist fallback); every timeline item now comes from here. The playable
 * [uri] is resolved by the caller via PodcastRepository.playableUri so the
 * streaming-off policy stays in one place.
 */
object EpisodeMediaItems {

    fun build(
        episode: Episode,
        uri: String,
        podcastTitle: String?,
        artworkFallback: String? = null
    ): MediaItem = MediaItem.Builder()
        .setMediaId(episode.id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(episode.title)
                .setArtist(podcastTitle.orEmpty())
                .setArtworkUri(
                    (episode.imageUrl ?: artworkFallback)?.let(Uri::parse)
                )
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
        )
        .build()
}
