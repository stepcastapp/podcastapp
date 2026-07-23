package com.stepcast.app.ui

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.stepcast.app.StepcastApplication
import com.stepcast.app.data.Chapter
import com.stepcast.app.data.Episode
import com.stepcast.app.data.Podcast
import com.stepcast.app.playback.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Rarely-changing player state. The ticking position lives in
 * [PlayerConnection.progress] instead, so list screens collecting this
 * don't recompose twice a second while something plays.
 */
data class PlayerUiState(
    val episodeId: Long? = null,
    val title: String = "",
    val podcastTitle: String = "",
    val artworkUrl: String? = null,
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val sleepEndsAtMs: Long? = null,
    val sleepAtEpisodeEnd: Boolean = false,
    val chapters: List<Chapter> = emptyList()
)

/** The fast-moving part: playback position, updated every 500ms. */
data class PlayerProgress(
    val positionMs: Long = 0,
    val durationMs: Long = 0
) {
    val fraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** Wraps a MediaController and exposes player state as a StateFlow for Compose. */
class PlayerConnection(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val repository =
        (context.applicationContext as StepcastApplication).repository

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    private val _progress = MutableStateFlow(PlayerProgress())
    val progress: StateFlow<PlayerProgress> = _progress

    private var controller: MediaController? = null
    private var sleepEndsAtMs: Long? = null
    private var sleepAtEpisodeEnd: Boolean = false
    private var currentChapters: List<Chapter> = emptyList()
    private var chaptersForEpisodeId: Long? = null

    // the episode that was playing when the process last died — shown as a
    // paused "continue listening" state until real playback replaces it
    private var ghostEpisode: Episode? = null
    private var ghostPodcast: Podcast? = null

    init {
        scope.launch {
            val token = SessionToken(
                context,
                ComponentName(context, PlaybackService::class.java)
            )
            controller = MediaController.Builder(context, token).buildAsync().await()
            controller?.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    refreshChaptersIfNeeded()
                    pushState()
                }
            })
            refreshChaptersIfNeeded()
            pushState()
            startQueueSync()
            maybeRestoreInterrupted()
            // position ticker while the UI is alive — only touches progress,
            // never the main state, so lists stay recomposition-quiet
            while (isActive) {
                delay(500)
                if (_state.value.isPlaying) pushProgress()
            }
        }
    }

    /**
     * Keeps the player timeline's tail (everything after the current item) in
     * sync with the queue table, so queue edits are reflected in what plays
     * next — and next/prev media buttons work everywhere.
     */
    private fun startQueueSync() {
        scope.launch {
            repository.queue.collect { queue ->
                val c = controller ?: return@collect
                if (c.mediaItemCount == 0) return@collect
                val currentId = c.currentMediaItem?.mediaId
                val desired = queue.filter { it.id.toString() != currentId }
                val curIdx = c.currentMediaItemIndex
                val existingTail = buildList {
                    for (i in curIdx + 1 until c.mediaItemCount) {
                        add(c.getMediaItemAt(i).mediaId)
                    }
                }
                if (existingTail == desired.map { it.id.toString() }) return@collect
                val items = desired.map { mediaItemFor(it, repository.podcast(it.podcastId)) }
                if (c.mediaItemCount > curIdx + 1) {
                    c.removeMediaItems(curIdx + 1, c.mediaItemCount)
                }
                c.addMediaItems(items)
            }
        }
    }

    /**
     * A force-close (or system kill) mid-episode leaves the controller
     * empty even though the listener was mid-way through something. The
     * widget prefs remember the last current episode; surface it as a
     * paused pill so one tap picks up where playback died.
     */
    private suspend fun maybeRestoreInterrupted() {
        val c = controller ?: return
        if (c.currentMediaItem != null) return
        val prefs = appContext.getSharedPreferences(
            com.stepcast.app.widget.StepcastWidget.PREFS, Context.MODE_PRIVATE
        )
        val lastId = prefs.getLong(
            com.stepcast.app.widget.StepcastWidget.KEY_EPISODE_ID, -1L
        )
        if (lastId <= 0) return
        val episode = repository.episode(lastId)?.takeIf { !it.played } ?: return
        if (controller?.currentMediaItem != null) return // playback beat us to it
        val podcast = repository.podcast(episode.podcastId)
        ghostEpisode = episode
        ghostPodcast = podcast
        _state.value = PlayerUiState(
            episodeId = episode.id,
            title = episode.title,
            podcastTitle = podcast?.title.orEmpty(),
            artworkUrl = episode.imageUrl ?: podcast?.imageUrl,
            isPlaying = false
        )
        _progress.value = PlayerProgress(
            positionMs = episode.positionMs,
            durationMs = episode.durationMs
        )
    }

    private fun pushState() {
        val c = controller ?: return
        if (c.currentMediaItem == null) {
            // nothing loaded: keep the interrupted-episode state visible
            if (ghostEpisode != null) return
        } else {
            ghostEpisode = null
            ghostPodcast = null
        }
        val metadata = c.mediaMetadata
        _state.value = PlayerUiState(
            episodeId = c.currentMediaItem?.mediaId?.toLongOrNull(),
            title = metadata.title?.toString().orEmpty(),
            podcastTitle = metadata.artist?.toString().orEmpty(),
            artworkUrl = metadata.artworkUri?.toString(),
            isPlaying = c.isPlaying,
            speed = c.playbackParameters.speed,
            hasNext = c.hasNextMediaItem(),
            hasPrevious = c.hasPreviousMediaItem(),
            sleepEndsAtMs = sleepEndsAtMs?.takeIf { it > System.currentTimeMillis() },
            sleepAtEpisodeEnd = sleepAtEpisodeEnd,
            chapters = if (c.currentMediaItem?.mediaId?.toLongOrNull() == chaptersForEpisodeId) {
                currentChapters
            } else {
                emptyList()
            }
        )
        pushProgress()
    }

    private fun pushProgress() {
        val c = controller ?: return
        _progress.value = PlayerProgress(
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it > 0 } ?: 0
        )
    }

    private fun refreshChaptersIfNeeded() {
        val episodeId = controller?.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        if (episodeId == chaptersForEpisodeId) return
        chaptersForEpisodeId = episodeId
        currentChapters = emptyList()
        scope.launch {
            val chapters = repository.chaptersFor(episodeId)
            if (chaptersForEpisodeId == episodeId) {
                currentChapters = chapters
                pushState()
            }
        }
    }

    private fun mediaItemFor(episode: Episode, podcast: Podcast?): MediaItem {
        val localUri = episode.localFilePath
            ?.let { java.io.File(it) }
            ?.takeIf { it.exists() }
            ?.let { android.net.Uri.fromFile(it).toString() }
        return MediaItem.Builder()
            .setMediaId(episode.id.toString())
            .setUri(localUri ?: episode.audioUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.title)
                    .setArtist(podcast?.title ?: "")
                    .setArtworkUri(
                        (episode.imageUrl ?: podcast?.imageUrl)?.let(android.net.Uri::parse)
                    )
                    .build()
            )
            .build()
    }

    /**
     * Plays the episode now, at position 0 of the timeline. If a different,
     * unfinished episode was playing, it becomes the FRONT of the up-next
     * queue (so "play this now" never loses your place in the interrupted
     * one). The rest of the queue follows. [fromStationId] marks a
     * continuous-SmartPlay start; any other play deliberately ends the
     * active station.
     */
    fun play(
        episode: Episode,
        podcast: Podcast?,
        fromStationId: Long = 0L,
        // SmartPlay starts pass false: the user chose a fresh playlist, so
        // the previously-playing episode must not be spliced into it (its
        // position is saved; switching back resumes it)
        preserveInterrupted: Boolean = true
    ) {
        scope.launch {
            // A tap immediately after cold launch can arrive before the
            // controller finishes connecting (set async in init). Rather than
            // silently drop the play — leaving a just-filled SmartPlay queue
            // with nothing playing — wait briefly for the connection.
            var c = controller
            var waitedMs = 0
            while (c == null && waitedMs < 2_000) {
                delay(50); waitedMs += 50; c = controller
            }
            if (c == null) return@launch
            if (com.stepcast.app.data.AppSettings.activeStationId != fromStationId) {
                com.stepcast.app.data.AppSettings
                    .setActiveStationId(appContext, fromStationId)
            }
            // with streaming off, an undownloaded episode downloads instead
            if (!com.stepcast.app.data.AppSettings.streamWhenNotDownloaded &&
                !episode.audioUrl.startsWith("content:") &&
                episode.localFilePath?.let { java.io.File(it).exists() } != true
            ) {
                com.stepcast.app.download.DownloadWorker.start(appContext, episode.id)
                android.widget.Toast.makeText(
                    appContext,
                    appContext.getString(
                        com.stepcast.app.R.string.streaming_off_downloading_instead
                    ),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val interrupted = if (preserveInterrupted) {
                c.currentMediaItem?.mediaId?.toLongOrNull()
                    ?.takeIf { it != episode.id }
                    ?.let { repository.episode(it) }
                    ?.takeIf { !it.played }
            } else {
                null
            }
            val upNext = buildList {
                interrupted?.let { add(it) }
                addAll(
                    repository.queueSnapshot()
                        .filter { it.id != episode.id && it.id != interrupted?.id }
                )
            }
            repository.replaceQueue(upNext.map { it.id })
            val items = mutableListOf(mediaItemFor(episode, podcast))
            for (ep in upNext) items += mediaItemFor(ep, repository.podcast(ep.podcastId))
            // a saved position within the last 15s would "complete" instantly
            // and auto-advance — which reads as a random other episode starting
            val nearEnd = episode.durationMs > 0 &&
                episode.positionMs >= episode.durationMs - 15_000
            val startMs = if (episode.played || nearEnd) 0L else episode.positionMs
            c.setMediaItems(items, 0, startMs)
            c.prepare()
            c.play()
        }
    }

    /**
     * Streams an episode that is NOT in the library — the Discover preview.
     * Uses a numeric sentinel media id so the pill/full player render from
     * the item's own metadata while every DB lookup (position persistence,
     * chapters, restore) quietly no-ops on the missing row. If a queue
     * exists, queueSync appends it behind the preview, so playback simply
     * continues with the user's own episodes afterwards.
     */
    fun playPreview(
        title: String,
        podcastTitle: String,
        artworkUrl: String?,
        audioUrl: String
    ) {
        val c = controller ?: return
        val item = MediaItem.Builder()
            .setMediaId(PREVIEW_MEDIA_ID)
            .setUri(audioUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(podcastTitle)
                    .setArtworkUri(artworkUrl?.let(android.net.Uri::parse))
                    .build()
            )
            .build()
        c.setMediaItems(listOf(item), 0, 0L)
        c.prepare()
        c.play()
    }

    /**
     * The "done with this one" button: marks the current episode played,
     * deletes its download, and moves on to the next queued episode.
     */
    fun skipToNextAndDelete() {
        val c = controller ?: return
        val episodeId = c.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        if (c.hasNextMediaItem()) c.seekToNextMediaItem() else c.pause()
        scope.launch {
            repository.markPlayed(episodeId)
            repository.deleteDownload(episodeId)
            pushState()
        }
    }

    fun togglePlayPause() {
        val c = controller ?: return
        val ghost = ghostEpisode
        if (c.currentMediaItem == null && ghost != null) {
            // resuming the episode the force-close interrupted
            play(ghost, ghostPodcast)
            return
        }
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekBack() = controller?.seekBack()
    fun seekForward() = controller?.seekForward()

    /** Relative jump — the per-show ad-jump chip. */
    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        c.seekTo((c.currentPosition + deltaMs).coerceAtLeast(0))
    }
    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs)
    fun skipToNext() = controller?.seekToNextMediaItem()
    fun skipToPrevious() = controller?.seekToPreviousMediaItem()

    fun seekToNextChapter() {
        val c = controller ?: return
        val next = currentChapters.firstOrNull { it.startMs > c.currentPosition } ?: return
        c.seekTo(next.startMs)
    }

    /** Within the first 3s of a chapter, jumps to the previous one instead. */
    fun seekToPreviousChapter() {
        val c = controller ?: return
        val target = currentChapters.lastOrNull { it.startMs < c.currentPosition - 3_000 }
        c.seekTo(target?.startMs ?: 0L)
    }

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
        pushState()
    }

    /** minutes > 0 arms a countdown; endOfEpisode stops after the current one; both off cancels. */
    fun setSleepTimer(minutes: Int, endOfEpisode: Boolean) {
        val c = controller ?: return
        val args = android.os.Bundle().apply {
            putInt(PlaybackService.KEY_SLEEP_MINUTES, minutes)
            putBoolean(PlaybackService.KEY_SLEEP_END_OF_EPISODE, endOfEpisode)
        }
        c.sendCustomCommand(
            SessionCommand(PlaybackService.ACTION_SLEEP_TIMER, android.os.Bundle.EMPTY),
            args
        )
        sleepEndsAtMs =
            if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else null
        sleepAtEpisodeEnd = endOfEpisode
        pushState()
    }

    fun release() {
        controller?.release()
        controller = null
    }

    companion object {
        /** Sentinel media id for preview playback; no episode row exists. */
        const val PREVIEW_MEDIA_ID = "-1"
    }
}
