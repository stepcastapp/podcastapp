package com.stepcast.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.stepcast.app.R
import com.stepcast.app.StepcastApplication
import com.stepcast.app.data.AppSettings
import com.stepcast.app.data.ListenStats
import com.stepcast.app.ui.MainActivity
import com.stepcast.app.data.Chapter
import com.stepcast.app.data.Episode
import com.stepcast.app.data.Podcast
import com.stepcast.app.widget.StepcastWidget
import com.stepcast.app.widget.updateAllStepcastWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Media3 playback service. MediaSessionService gives us the media
 * notification, lock-screen card, QS output/media carousel and
 * Bluetooth/headset routing for free.
 *
 * Stepcast-specific behavior:
 *  - episode id travels in each MediaItem's mediaId
 *  - playback position is persisted every few seconds and on pause
 *  - per-podcast intro skip: applied when an episode starts below the mark
 *  - per-podcast outro skip: watched each tick; seeks to the end so the
 *    normal completion flow runs (mark played, advance)
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private var exoPlayer: ExoPlayer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickerJob: Job? = null
    private var activeEpisodeId: Long? = null
    private var sleepJob: Job? = null
    private var sleepAtEpisodeEnd = false
    private var sleepEndsAtMs = 0L
    private var shakeListener: android.hardware.SensorEventListener? = null
    private var lastShakeMs = 0L
    private var currentChapters: List<Chapter> = emptyList()
    private var lastAdSkipStartMs = -1L

    /** The current show's manual ad-jump length; drives the extra
     * notification button and the AD_JUMP command. 0 = none. */
    private var currentAdJumpSec = 0
    private var lastWidgetArtUri: String? = null

    // listening-stats accumulators, flushed alongside position persists
    private var statsLastPositionMs = -1L
    private var statsPendingWallMs = 0L
    private var statsPendingContentMs = 0L
    private var activePodcastId: Long? = null

    private val app get() = application as StepcastApplication

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(AppSettings.seekBackSeconds * 1_000L)
            .setSeekForwardIncrementMs(AppSettings.seekForwardSeconds * 1_000L)
            .build()
        exoPlayer = player
        player.skipSilenceEnabled = AppSettings.skipSilence

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                publishWidgetState()
                mediaItem ?: return
                serviceScope.launch { onEpisodeStarted(mediaItem, reason) }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startTicker() else {
                    stopTicker()
                    persistPosition()
                }
                publishWidgetState()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    currentEpisodeId()?.let { id ->
                        serviceScope.launch {
                            withContext(Dispatchers.IO) {
                                app.repository.markPlayed(id)
                            }
                            maybeContinueCurrentShow(id)
                        }
                    }
                }
            }
        })

        // tapping the media notification opens the app
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(sessionActivity)
            .build()

        // brand the status-bar icon with the stairstep silhouette
        setMediaNotificationProvider(
            PillNotificationProvider().apply {
                setSmallIcon(R.drawable.ic_notification_steps)
            }
        )
    }

    /**
     * Media notification whose transport buttons mirror the in-app pill:
     * seek back / play-pause / seek forward / done-and-delete — instead of
     * the default previous / play-pause / next.
     */
    private inner class PillNotificationProvider :
        DefaultMediaNotificationProvider(this@PlaybackService) {

        override fun getMediaButtons(
            session: MediaSession,
            playerCommands: Player.Commands,
            customLayout: ImmutableList<CommandButton>,
            showPauseButton: Boolean
        ): ImmutableList<CommandButton> {
            fun compact(index: Int) = Bundle().apply {
                putInt(DefaultMediaNotificationProvider.COMMAND_KEY_COMPACT_VIEW_INDEX, index)
            }
            val buttons = buildList {
                add(
                    CommandButton.Builder()
                        .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                        .setIconResId(R.drawable.ic_notif_replay)
                        .setDisplayName("Seek back")
                        .setExtras(compact(0))
                        .build()
                )
                add(
                    CommandButton.Builder()
                        .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                        .setIconResId(
                            if (showPauseButton) {
                                R.drawable.ic_notif_pause
                            } else {
                                R.drawable.ic_notif_play
                            }
                        )
                        .setDisplayName(if (showPauseButton) "Pause" else "Play")
                        .setExtras(compact(1))
                        .build()
                )
                add(
                    CommandButton.Builder()
                        .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                        .setIconResId(R.drawable.ic_notif_forward)
                        .setDisplayName("Seek forward")
                        .setExtras(compact(2))
                        .build()
                )
                if (AppSettings.notificationDoneButton) {
                    add(
                        CommandButton.Builder()
                            .setSessionCommand(
                                SessionCommand(ACTION_DONE_DELETE, Bundle.EMPTY)
                            )
                            .setIconResId(R.drawable.ic_notif_done)
                            .setDisplayName("Done: mark played, delete, next")
                            .build()
                    )
                }
            }
            return ImmutableList.copyOf(buttons)
        }
    }

    /**
     * The Android 13+ system media controls render from THESE session-level
     * button preferences, not notification actions: back left of play,
     * forward right of play, Done (if enabled) in the extra slot.
     */
    private fun mediaNotificationButtons(): ImmutableList<CommandButton> {
        val buttons = buildList {
            add(
                CommandButton.Builder()
                    .setSessionCommand(SessionCommand(ACTION_SEEK_BACK, Bundle.EMPTY))
                    .setIconResId(R.drawable.ic_notif_replay)
                    .setDisplayName(getString(R.string.seek_back))
                    .setSlots(CommandButton.SLOT_BACK)
                    .build()
            )
            add(
                CommandButton.Builder()
                    .setSessionCommand(SessionCommand(ACTION_SEEK_FORWARD, Bundle.EMPTY))
                    .setIconResId(R.drawable.ic_notif_forward)
                    .setDisplayName(getString(R.string.seek_forward))
                    .setSlots(CommandButton.SLOT_FORWARD)
                    .build()
            )
            // One UI ignores slot hints and renders extra buttons in LIST
            // order (first extra lands far left) — so Done must stay ahead
            // of the ad-jump button or it hops from left to right whenever
            // a show has an ad jump configured.
            if (AppSettings.notificationDoneButton) {
                add(
                    CommandButton.Builder()
                        .setSessionCommand(
                            SessionCommand(ACTION_DONE_DELETE, Bundle.EMPTY)
                        )
                        .setIconResId(R.drawable.ic_notif_done)
                        .setDisplayName(
                            getString(R.string.done_mark_played_delete_next)
                        )
                        .setSlots(
                            CommandButton.SLOT_FORWARD_SECONDARY,
                            CommandButton.SLOT_OVERFLOW
                        )
                        .build()
                )
            }
            // per-show mid-roll jump; refreshed on every episode start so it
            // appears only for shows that configured one. On stock Android
            // this falls to the overflow menu when Done already took the
            // visible secondary slot; One UI shows both inline.
            if (currentAdJumpSec > 0) {
                add(
                    CommandButton.Builder()
                        .setSessionCommand(SessionCommand(ACTION_AD_JUMP, Bundle.EMPTY))
                        .setIconResId(R.drawable.ic_notif_ad_jump)
                        .setDisplayName(
                            getString(R.string.ad_jump_cd, currentAdJumpSec)
                        )
                        .setSlots(
                            CommandButton.SLOT_FORWARD_SECONDARY,
                            CommandButton.SLOT_OVERFLOW
                        )
                        .build()
                )
            }
        }
        return ImmutableList.copyOf(buttons)
    }

    /**
     * onConnect preferences stick for the life of the controller connection
     * — which is the life of the service PROCESS, not of playback. Re-apply
     * on every episode start so toggling the Done-button setting actually
     * takes effect without force-stopping the app.
     */
    private fun refreshMediaButtonPreferences() {
        val session = mediaSession ?: return
        val controller = session.connectedControllers.firstOrNull {
            session.isMediaNotificationController(it)
        } ?: return
        session.setMediaButtonPreferences(controller, mediaNotificationButtons())
    }

    /** Marks the current episode played, deletes its download, moves on. */
    private fun doneAndDeleteCurrent() {
        val player = mediaSession?.player ?: return
        val episodeId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.pause()
        serviceScope.launch(Dispatchers.IO) {
            app.repository.markPlayed(episodeId)
            app.repository.deleteDownload(episodeId)
        }
    }

    /**
     * Session + browse-tree callback. The tree (root → Up Next / podcasts →
     * episodes) is what Android Auto and other MediaBrowsers show, and
     * [onAddMediaItems] resolves bare mediaIds back to playable URIs.
     */
    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                    .add(SessionCommand(ACTION_SLEEP_TIMER, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_DONE_DELETE, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_SEEK_BACK, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_SEEK_FORWARD, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_AD_JUMP, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_START_SMARTPLAY, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_REFRESH_NOTIF_BUTTONS, Bundle.EMPTY))
                    .build()
            // On Android 13+ the SYSTEM renders media controls straight from
            // the session, ignoring notification actions. To make them match
            // the in-app pill we hand the media-notification controller
            // button preferences with EXPLICIT slots — back left of play,
            // forward right of play, done right of forward — and hide
            // prev/next so nothing competes for those slots. Other
            // controllers (app UI, Bluetooth, Auto) keep full commands.
            if (session.isMediaNotificationController(controller)) {
                val playerCommands =
                    MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                        .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                        .remove(Player.COMMAND_SEEK_TO_NEXT)
                        .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(sessionCommands)
                    .setAvailablePlayerCommands(playerCommands)
                    .setMediaButtonPreferences(mediaNotificationButtons())
                    .build()
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        /**
         * System media resumption (QS "resume" card after reboot / process
         * death): rebuild the last-playing episode + the saved queue, with
         * the persisted position.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            serviceScope.future {
                val prefs = getSharedPreferences(StepcastWidget.PREFS, MODE_PRIVATE)
                val lastId = prefs.getLong(StepcastWidget.KEY_EPISODE_ID, -1L)
                val episode = lastId.takeIf { it > 0 }
                    ?.let { app.repository.episode(it) }
                    ?: app.repository.queueSnapshot().firstOrNull()
                    ?: throw UnsupportedOperationException("nothing to resume")
                val tail = app.repository.queueSnapshot().filter { it.id != episode.id }
                val items = buildList {
                    add(episodeToItem(episode))
                    tail.forEach { add(episodeToItem(it)) }
                }
                MediaSession.MediaItemsWithStartPosition(
                    items, 0, episode.positionMs.coerceAtLeast(0)
                )
            }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_SLEEP_TIMER) {
                setSleepTimer(
                    args.getInt(KEY_SLEEP_MINUTES, 0),
                    args.getBoolean(KEY_SLEEP_END_OF_EPISODE, false)
                )
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == ACTION_DONE_DELETE) {
                doneAndDeleteCurrent()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == ACTION_SEEK_BACK) {
                session.player.seekBack()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == ACTION_SEEK_FORWARD) {
                session.player.seekForward()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == ACTION_AD_JUMP) {
                if (currentAdJumpSec > 0) {
                    val target = session.player.currentPosition +
                        currentAdJumpSec * 1000L
                    session.player.seekTo(target.coerceAtLeast(0))
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == ACTION_START_SMARTPLAY) {
                val name = args.getString(KEY_SMARTPLAY_NAME)
                    ?: return Futures.immediateFuture(
                        SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)
                    )
                // hand back the in-flight future so the caller stays connected
                // until playback actually begins (see startSmartPlayByName)
                return startSmartPlayByName(name)
            }
            if (customCommand.customAction == ACTION_REFRESH_NOTIF_BUTTONS) {
                // re-resolve per-show values too (ad jump edited mid-episode)
                serviceScope.launch {
                    currentEpisodeId()?.let {
                        currentAdJumpSec = app.repository.adJumpSecFor(it)
                    }
                    refreshMediaButtonPreferences()
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(
                LibraryResult.ofItem(browsableItem(ROOT_ID, "Stepcast"), params)
            )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            serviceScope.future {
                // driving-first hierarchy: SmartPlays (one tap = a whole
                // queue), then categories, then the full flat list — a
                // 300-podcast flat root is unusable in a car
                val children: List<MediaItem> = when {
                    parentId == ROOT_ID -> buildList {
                        add(browsableItem(QUEUE_ID, "Up Next"))
                        add(browsableItem(SMARTPLAYS_ID, "SmartPlays"))
                        for (meta in app.repository.categoryMetaList()) {
                            add(
                                browsableItem(
                                    "$CATEGORY_PREFIX${meta.name}",
                                    meta.name
                                )
                            )
                        }
                        add(browsableItem(ALL_PODCASTS_ID, "All podcasts"))
                    }
                    parentId == QUEUE_ID ->
                        app.repository.queueSnapshot().map { episodeToItem(it) }
                    parentId == SMARTPLAYS_ID ->
                        app.repository.smartPlayList().map { smartPlay ->
                            browsableItem("$SMARTPLAY_PREFIX${smartPlay.id}", smartPlay.name)
                        }
                    parentId.startsWith(SMARTPLAY_PREFIX) -> {
                        val id = parentId.removePrefix(SMARTPLAY_PREFIX).toLongOrNull()
                        val smartPlay = app.repository.smartPlayList()
                            .firstOrNull { it.id == id }
                        if (smartPlay == null) {
                            emptyList()
                        } else {
                            app.repository.episodesFor(smartPlay).map { episodeToItem(it) }
                        }
                    }
                    parentId.startsWith(CATEGORY_PREFIX) -> {
                        val folder = parentId.removePrefix(CATEGORY_PREFIX)
                        val memberIds = app.repository
                            .categoryMemberIds(folder).toHashSet()
                        app.repository.allPodcasts()
                            .filter { it.id in memberIds }
                            .map {
                                browsableItem(
                                    "$PODCAST_PREFIX${it.id}", it.title, it.imageUrl
                                )
                            }
                    }
                    parentId == ALL_PODCASTS_ID ->
                        app.repository.allPodcasts().map {
                            browsableItem("$PODCAST_PREFIX${it.id}", it.title, it.imageUrl)
                        }
                    parentId.startsWith(PODCAST_PREFIX) -> {
                        val podcastId =
                            parentId.removePrefix(PODCAST_PREFIX).toLongOrNull()
                        val podcast = podcastId?.let { app.repository.podcast(it) }
                        if (podcast == null) emptyList() else episodesForBrowse(podcast)
                    }
                    else -> emptyList()
                }
                LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
            }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> =
            serviceScope.future {
                val episode = mediaId.toLongOrNull()?.let { app.repository.episode(it) }
                if (episode == null) {
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                } else {
                    LibraryResult.ofItem(episodeToItem(episode), null)
                }
            }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> =
            serviceScope.future {
                mediaItems.map { item ->
                    if (item.localConfiguration != null) {
                        item
                    } else {
                        item.mediaId.toLongOrNull()
                            ?.let { app.repository.episode(it) }
                            ?.let { episodeToItem(it) }
                            ?: item
                    }
                }
            }
    }

    private fun browsableItem(id: String, title: String, artworkUrl: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtworkUri(artworkUrl?.let(Uri::parse))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()

    private suspend fun episodesForBrowse(podcast: Podcast): List<MediaItem> =
        app.repository.episodesNewestFirst(podcast.id, limit = 100)
            .map { episodeToItem(it, podcast) }

    /** Playable item with the same mediaId convention (plain episode id). */
    private suspend fun episodeToItem(episode: Episode, podcast: Podcast? = null): MediaItem {
        val owner = podcast ?: app.repository.podcast(episode.podcastId)
        val localUri = episode.localFilePath
            ?.let { File(it) }
            ?.takeIf { it.exists() }
            ?.let { Uri.fromFile(it).toString() }
        return MediaItem.Builder()
            .setMediaId(episode.id.toString())
            .setUri(localUri ?: episode.audioUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.title)
                    .setArtist(owner?.title ?: "")
                    .setArtworkUri(
                        (episode.imageUrl ?: owner?.imageUrl)?.let(Uri::parse)
                    )
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }

    /** minutes > 0 arms a countdown; endOfEpisode pauses after the current one; both zero/false cancels. */
    private fun setSleepTimer(minutes: Int, endOfEpisode: Boolean) {
        sleepAtEpisodeEnd = endOfEpisode
        if (minutes > 0) {
            armSleepMillis(minutes * 60_000L)
        } else {
            cancelSleepCountdown()
        }
    }

    /**
     * The countdown itself. The last stretch fades volume out instead of a
     * hard stop, and while armed a firm shake of the phone adds ten minutes
     * — no need to unlock a bright screen half-asleep.
     */
    private fun armSleepMillis(totalMs: Long) {
        sleepJob?.cancel()
        mediaSession?.player?.volume = 1f // reset a fade a shake interrupted
        sleepEndsAtMs = System.currentTimeMillis() + totalMs
        startShakeListener()
        sleepJob = serviceScope.launch {
            val fadeMs = minOf(SLEEP_FADE_MS, totalMs / 3)
            delay(totalMs - fadeMs)
            val player = mediaSession?.player
            val steps = 20
            for (i in 1..steps) {
                delay(fadeMs / steps)
                player?.volume = 1f - i.toFloat() / steps
            }
            player?.pause()
            player?.volume = 1f
            sleepJob = null
            sleepEndsAtMs = 0
            stopShakeListener()
        }
    }

    private fun cancelSleepCountdown() {
        sleepJob?.cancel()
        sleepJob = null
        sleepEndsAtMs = 0
        stopShakeListener()
        mediaSession?.player?.volume = 1f
    }

    private fun startShakeListener() {
        if (shakeListener != null) return
        val sensorManager =
            getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager
        val accelerometer = sensorManager
            .getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: return
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val gForce = kotlin.math.sqrt(x * x + y * y + z * z) /
                    android.hardware.SensorManager.GRAVITY_EARTH
                if (gForce > SHAKE_G_FORCE) {
                    val now = System.currentTimeMillis()
                    if (now - lastShakeMs > 2_000) {
                        lastShakeMs = now
                        onSleepShake()
                    }
                }
            }

            override fun onAccuracyChanged(
                sensor: android.hardware.Sensor?,
                accuracy: Int
            ) = Unit
        }
        shakeListener = listener
        sensorManager.registerListener(
            listener, accelerometer, android.hardware.SensorManager.SENSOR_DELAY_UI
        )
    }

    private fun stopShakeListener() {
        val listener = shakeListener ?: return
        shakeListener = null
        val sensorManager =
            getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager
        sensorManager.unregisterListener(listener)
    }

    private fun onSleepShake() {
        if (sleepEndsAtMs <= 0) return
        val remaining = (sleepEndsAtMs - System.currentTimeMillis()).coerceAtLeast(0)
        armSleepMillis(remaining + SLEEP_EXTEND_MS)
        android.widget.Toast.makeText(
            this,
            getString(com.stepcast.app.R.string.sleep_timer_plus_10),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        persistPosition()
        stopTicker()
        stopShakeListener()
        sleepJob?.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    // ---- position + skip machinery -------------------------------------

    private fun currentEpisodeId(): Long? =
        mediaSession?.player?.currentMediaItem?.mediaId?.toLongOrNull()

    /** Mirrors now-playing state into prefs and refreshes the home widget. */
    private fun publishWidgetState() {
        val player = mediaSession?.player ?: return
        val metadata = player.mediaMetadata
        val duration = player.duration
        val fraction = if (duration > 0) {
            (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
        } else {
            0f
        }
        getSharedPreferences(StepcastWidget.PREFS, MODE_PRIVATE).edit()
            .putString(StepcastWidget.KEY_TITLE, metadata.title?.toString())
            .putString(StepcastWidget.KEY_PODCAST, metadata.artist?.toString())
            .putBoolean(StepcastWidget.KEY_PLAYING, player.isPlaying)
            .putFloat(StepcastWidget.KEY_PROGRESS, fraction)
            .putLong(
                StepcastWidget.KEY_EPISODE_ID,
                player.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L
            )
            .apply()
        val artUri = metadata.artworkUri?.toString()
        if (artUri != lastWidgetArtUri) {
            lastWidgetArtUri = artUri
            cacheWidgetArt(artUri)
        }
        serviceScope.launch {
            runCatching { updateAllStepcastWidgets(this@PlaybackService) }
        }
    }

    /**
     * Downloads (or reads) the current episode artwork, scales it down, and
     * caches it for the widgets — RemoteViews can't load from a URL.
     */
    private fun cacheWidgetArt(uri: String?) {
        serviceScope.launch(Dispatchers.IO) {
            val prefs = getSharedPreferences(StepcastWidget.PREFS, MODE_PRIVATE)
            val artFile = File(cacheDir, "widget_art.png")
            val ok = uri != null && runCatching {
                val input = if (uri.startsWith("content:") || uri.startsWith("file:")) {
                    contentResolver.openInputStream(Uri.parse(uri))
                } else {
                    java.net.URL(uri).openStream()
                } ?: return@runCatching false
                val raw = input.use { it.readBytes() }
                val bounds = android.graphics.BitmapFactory.Options()
                    .apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 512) sample *= 2
                val opts = android.graphics.BitmapFactory.Options()
                    .apply { inSampleSize = sample }
                val bitmap =
                    android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
                        ?: return@runCatching false
                artFile.outputStream().use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
                }
                true
            }.getOrDefault(false)
            prefs.edit()
                .putString(StepcastWidget.KEY_ART_PATH, if (ok) artFile.absolutePath else null)
                .apply()
            runCatching { updateAllStepcastWidgets(this@PlaybackService) }
        }
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = serviceScope.launch {
            var saveCountdown = SAVE_EVERY_TICKS
            var widgetCountdown = WIDGET_EVERY_TICKS
            while (isActive) {
                delay(TICK_MS)
                checkOutroSkip()
                checkAdChapterSkip()
                accumulateStatsTick()
                if (--saveCountdown <= 0) {
                    saveCountdown = SAVE_EVERY_TICKS
                    persistPosition()
                    flushStats()
                }
                if (--widgetCountdown <= 0) {
                    widgetCountdown = WIDGET_EVERY_TICKS
                    publishWidgetState() // keeps the widget progress bar honest
                }
            }
        }
    }

    /**
     * Counts one wall-clock tick and the media position actually consumed.
     * Big jumps (seeks, outro/ad skips) and rewinds don't count as
     * listening; speed and silence trimming naturally show up as content
     * advancing faster than the clock.
     */
    private fun accumulateStatsTick() {
        val pos = mediaSession?.player?.currentPosition ?: return
        val last = statsLastPositionMs
        statsLastPositionMs = pos
        if (last < 0) return
        val delta = pos - last
        if (delta in 1..15_000) {
            statsPendingWallMs += TICK_MS
            statsPendingContentMs += delta
        }
    }

    private fun flushStats() {
        if (statsPendingWallMs > 0 || statsPendingContentMs > 0) {
            ListenStats.addListening(this, statsPendingWallMs, statsPendingContentMs)
            val podcastId = activePodcastId
            val wallMs = statsPendingWallMs
            val contentMs = statsPendingContentMs
            if (podcastId != null) {
                serviceScope.launch(Dispatchers.IO) {
                    runCatching {
                        app.repository.addPodcastListening(podcastId, wallMs, contentMs)
                    }
                }
            }
            statsPendingWallMs = 0
            statsPendingContentMs = 0
        }
    }

    private fun stopTicker() {
        flushStats()
        statsLastPositionMs = -1
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun persistPosition() {
        val player = mediaSession?.player ?: return
        val episodeId = currentEpisodeId() ?: return
        val position = player.currentPosition
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0
        serviceScope.launch(Dispatchers.IO) {
            app.repository.savePosition(episodeId, position, duration)
        }
    }

    /**
     * Runs whenever a new media item becomes current. Removes it from the
     * up-next queue, and raises the start position to the saved resume point
     * (auto-advance/skip starts at 0) and past any per-podcast intro skip.
     */
    private suspend fun onEpisodeStarted(mediaItem: MediaItem, reason: Int) {
        val episodeId = mediaItem.mediaId.toLongOrNull() ?: return

        // settle pending listening time against the podcast that earned it
        // before the attribution target changes
        flushStats()
        statsLastPositionMs = -1
        activePodcastId = app.repository.episode(episodeId)?.podcastId

        // per-show ad-jump length feeds the notification button below
        currentAdJumpSec = app.repository.adJumpSecFor(episodeId)

        // settings like the Done button apply from the next episode start
        refreshMediaButtonPreferences()

        // With a playlist, STATE_ENDED only fires after the LAST item; an
        // episode that finished mid-playlist completes via an AUTO transition.
        val previousId = activeEpisodeId
        activeEpisodeId = episodeId
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
            previousId != null && previousId != episodeId
        ) {
            app.repository.markPlayed(previousId)
            if (sleepAtEpisodeEnd) {
                sleepAtEpisodeEnd = false
                mediaSession?.player?.pause()
            }
        }

        app.repository.removeFromQueue(episodeId)

        val player = mediaSession?.player ?: return

        // per-podcast playback speed, falling back to the user's default
        // (a mid-episode manual tweak lasts until the next episode starts)
        val podcastSpeed = app.repository.speedFor(episodeId)
        player.setPlaybackSpeed(
            if (podcastSpeed > 0f) podcastSpeed else AppSettings.defaultPlaybackSpeed
        )
        // settings toggle applies from the next episode start
        exoPlayer?.skipSilenceEnabled = AppSettings.skipSilence

        currentChapters = emptyList()
        lastAdSkipStartMs = -1L
        currentChapters = try {
            app.repository.chaptersFor(episodeId)
        } catch (e: Exception) {
            emptyList()
        }
        val introMs = app.repository.introSkipMsFor(episodeId)
        val resumeMs = when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> {
                val episode = app.repository.episode(episodeId)
                val nearEnd = episode != null && episode.durationMs > 0 &&
                    episode.positionMs >= episode.durationMs - 15_000
                if (episode != null && !episode.played && !nearEnd) episode.positionMs else 0L
            }
            else -> 0L // explicit play() already passed the start position
        }
        val target = maxOf(introMs, resumeMs)
        if (target > 0 && player.currentPosition < target &&
            player.currentMediaItem?.mediaId == mediaItem.mediaId
        ) {
            player.seekTo(target)
        }

        maybeRefillStation(episodeId)
    }

    /**
     * Station mode: while a continuous SmartPlay is the active station, its
     * rules re-evaluate and top the queue back up whenever it runs low.
     * Fresh items are appended to the PLAYER timeline here too — the UI's
     * queueSync only runs while the app process is alive, and a station has
     * to keep going from the lock screen; when the UI is up its sync sees a
     * matching tail and no-ops.
     */
    private suspend fun maybeRefillStation(currentEpisodeId: Long?) {
        val stationId = AppSettings.activeStationId
        if (stationId <= 0) return
        val station = app.repository.smartPlay(stationId)
        if (station == null || !station.continuous) {
            AppSettings.setActiveStationId(this, 0L)
            return
        }
        val queued = app.repository.queueSnapshot()
        if (queued.size > 1) return
        val queuedIds = queued.mapTo(HashSet()) { it.id }
        val player = mediaSession?.player ?: return
        val tailIds = buildSet {
            for (i in player.currentMediaItemIndex + 1 until player.mediaItemCount) {
                add(player.getMediaItemAt(i).mediaId)
            }
        }
        val fresh = app.repository.episodesFor(station).filter {
            it.id != currentEpisodeId && it.id !in queuedIds &&
                it.id.toString() !in tailIds
        }
        if (fresh.isEmpty()) return
        for (ep in fresh) app.repository.addToQueueLast(ep.id)
        player.addMediaItems(fresh.map { episodeToItem(it) })
    }

    /**
     * Deliberate queue-end behavior: by default playback stops when Up Next
     * runs dry; with the setting on, the same show's next unplayed episode
     * (honoring its sort preference) keeps things going.
     */
    private suspend fun maybeContinueCurrentShow(finishedEpisodeId: Long) {
        if (!AppSettings.continueCurrentShow) return
        if (sleepAtEpisodeEnd) return // the sleep timer asked for this stop
        val player = mediaSession?.player ?: return
        if (player.hasNextMediaItem()) return // STATE_ENDED means it didn't, but be safe
        val next = try {
            app.repository.nextUnplayedAfter(finishedEpisodeId)
        } catch (e: Exception) {
            null
        } ?: return
        player.setMediaItem(episodeToItem(next), next.positionMs.coerceAtLeast(0))
        player.prepare()
        player.play()
    }

    /**
     * When the current chapter looks like an ad ("sponsor"/"advertis"/
     * "promo" in the title), jump to the next chapter. Each ad chapter is
     * skipped at most once so listeners can scrub back into it on purpose.
     */
    private fun checkAdChapterSkip() {
        if (!AppSettings.adChapterAutoSkip) return
        if (currentChapters.isEmpty()) return
        val player = mediaSession?.player ?: return
        if (!player.isPlaying) return
        val position = player.currentPosition
        val index = currentChapters.indexOfLast { it.startMs <= position }
        if (index < 0) return
        val chapter = currentChapters[index]
        if (chapter.startMs == lastAdSkipStartMs) return
        if (!AD_CHAPTER_REGEX.containsMatchIn(chapter.title)) return
        lastAdSkipStartMs = chapter.startMs
        val nextStartMs = currentChapters.getOrNull(index + 1)?.startMs
        if (nextStartMs != null) {
            player.seekTo(nextStartMs)
        } else {
            val duration = player.duration
            if (duration != C.TIME_UNSET && duration > 0) player.seekTo(duration)
        }
    }

    private fun checkOutroSkip() {
        val player = mediaSession?.player ?: return
        if (!player.isPlaying) return
        val episodeId = currentEpisodeId() ?: return
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        val position = player.currentPosition
        serviceScope.launch {
            val outroMs = app.repository.outroSkipMsFor(episodeId)
            if (outroMs > 0 && duration - outroMs > 0 && position >= duration - outroMs) {
                // jump to the end; STATE_ENDED handling marks played
                mediaSession?.player?.seekTo(duration)
            }
        }
    }

    /**
     * The automation/widget SmartPlay start, run entirely service-side:
     * building items here (full URIs, no async id resolution) and driving
     * the session player directly means playback genuinely starts even
     * when the caller is a broadcast that disconnects immediately.
     */
    // Returns a future that completes only AFTER the queue is filled and
    // play() has run. The caller (CommandReceiver's throwaway controller) awaits
    // it, so on a cold start the service keeps a bound client for the whole
    // start — releasing it early (the old fixed 300ms grace) could let the OS
    // destroy the service mid-start, filling the queue but never starting play.
    private fun startSmartPlayByName(name: String): ListenableFuture<SessionResult> =
        serviceScope.future {
            var started: com.stepcast.app.data.SmartPlay? = null
            val episodes = withContext(Dispatchers.IO) {
                val smartPlay = app.repository.smartPlayList()
                    .firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: return@withContext emptyList()
                started = smartPlay
                app.repository.episodesFor(smartPlay).also { episodes ->
                    if (episodes.isNotEmpty()) {
                        app.repository.replaceQueue(episodes.drop(1).map { it.id })
                    }
                }
            }
            if (episodes.isEmpty()) {
                return@future SessionResult(SessionResult.RESULT_SUCCESS)
            }
            AppSettings.setActiveStationId(
                this@PlaybackService,
                started?.takeIf { it.continuous }?.id ?: 0L
            )
            val player = mediaSession?.player
                ?: return@future SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE)
            // resume a half-listened head where it left off (C.TIME_UNSET
            // restarted it from zero — "why did my episode start over?");
            // same near-end guard as PlayerConnection so a nearly-finished
            // episode doesn't instant-complete
            val head = episodes.first()
            val nearEnd = head.durationMs > 0 &&
                head.positionMs >= head.durationMs - 15_000
            val startMs = if (head.played || nearEnd) 0L else head.positionMs
            player.setMediaItems(
                episodes.map { episodeToItem(it) }, 0, startMs
            )
            player.prepare()
            player.play()
            SessionResult(SessionResult.RESULT_SUCCESS)
        }

    companion object {
        private const val TICK_MS = 1_000L
        private const val SAVE_EVERY_TICKS = 5
        private const val WIDGET_EVERY_TICKS = 30

        const val ACTION_SLEEP_TIMER = "com.stepcast.app.SLEEP_TIMER"
        const val ACTION_DONE_DELETE = "com.stepcast.app.DONE_DELETE"
        const val ACTION_SEEK_BACK = "com.stepcast.app.SEEK_BACK"
        const val ACTION_SEEK_FORWARD = "com.stepcast.app.SEEK_FORWARD"
        const val ACTION_AD_JUMP = "com.stepcast.app.AD_JUMP"
        const val ACTION_START_SMARTPLAY = "com.stepcast.app.START_SMARTPLAY"
        const val ACTION_REFRESH_NOTIF_BUTTONS =
            "com.stepcast.app.REFRESH_NOTIF_BUTTONS"
        const val KEY_SMARTPLAY_NAME = "smartplayName"
        const val KEY_SLEEP_MINUTES = "minutes"
        const val KEY_SLEEP_END_OF_EPISODE = "endOfEpisode"

        private const val SLEEP_FADE_MS = 20_000L
        private const val SLEEP_EXTEND_MS = 10 * 60_000L
        private const val SHAKE_G_FORCE = 2.2f

        private const val ROOT_ID = "root"
        private const val QUEUE_ID = "queue"
        private const val SMARTPLAYS_ID = "smartplays"
        private const val ALL_PODCASTS_ID = "podcasts"
        private const val SMARTPLAY_PREFIX = "smartplay-"
        private const val CATEGORY_PREFIX = "category-"
        private const val PODCAST_PREFIX = "podcast-"

        private val AD_CHAPTER_REGEX =
            Regex("sponsor|advertis|promo", RegexOption.IGNORE_CASE)
    }
}
