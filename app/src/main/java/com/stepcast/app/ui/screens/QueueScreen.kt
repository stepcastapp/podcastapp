package com.stepcast.app.ui.screens

import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.stepcast.app.R
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.stepcast.app.data.AppSettings
import com.stepcast.app.data.PodcastRepository
import com.stepcast.app.download.DownloadWorker
import com.stepcast.app.ui.Formatters
import com.stepcast.app.ui.PlayerConnection
import com.stepcast.app.ui.progressBorder
import com.stepcast.app.ui.theme.EmptyState
import com.stepcast.app.ui.theme.ScreenTitle
import kotlinx.coroutines.launch

@Composable
fun QueueScreen(
    repository: PodcastRepository,
    player: PlayerConnection,
    onEditSmartPlay: (Long) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onPodcastClick: (Long) -> Unit
) {
    val queue by repository.queue.collectAsStateWithLifecycle(initialValue = emptyList())
    val podcasts by repository.podcasts.collectAsStateWithLifecycle(initialValue = emptyList())
    val podcastsById = podcasts.associateBy { it.id }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        val downloadActivity by repository.downloadActivity
            .collectAsStateWithLifecycle(initialValue = emptyList())
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp)
        ) {
            ScreenTitle(stringResource(R.string.up_next), modifier = Modifier.weight(1f))
            if (downloadActivity.isNotEmpty()) {
                IconButton(onClick = onOpenDownloads) {
                    // hand-placed count chip: BadgedBox overflows the 48dp
                    // button and clips at triple digits
                    Box {
                        Icon(
                            Icons.Rounded.Downloading,
                            contentDescription = stringResource(
                                R.string.downloads_in_progress_cd, downloadActivity.size
                            )
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .graphicsLayer {
                                    translationX = 8.dp.toPx()
                                    translationY = (-6).dp.toPx()
                                }
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.error)
                                .padding(horizontal = 3.dp)
                        ) {
                            Text(
                                if (downloadActivity.size > 99) {
                                    "99+"
                                } else {
                                    downloadActivity.size.toString()
                                },
                                color = MaterialTheme.colorScheme.onError,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
            if (queue.isNotEmpty()) {
                val clearedMsg = stringResource(R.string.queue_cleared)
                val undoLabel = stringResource(R.string.undo)
                IconButton(onClick = {
                    val before = queue.map { it.id }
                    scope.launch {
                        repository.replaceQueue(emptyList())
                        val result = snackbar.showSnackbar(
                            message = clearedMsg,
                            actionLabel = undoLabel,
                            withDismissAction = true
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            repository.replaceQueue(before)
                        }
                    }
                }) {
                    Icon(
                        Icons.Rounded.ClearAll,
                        contentDescription = stringResource(R.string.clear_queue)
                    )
                }
            }
            IconButton(onClick = onOpenHistory) {
                Icon(
                    Icons.Rounded.History,
                    contentDescription = stringResource(R.string.playback_history)
                )
            }
        }
        if (queue.isNotEmpty()) {
            val remainMs = queue.sumOf { (it.durationMs - it.positionMs).coerceAtLeast(0L) }
            // unknown durations contribute 0 — a confident "41m to go" over
            // a queue that's half unmeasured is a lie; mark it a floor
            val anyUnknown = queue.any { it.durationMs <= 0 }
            val remainLabel = Formatters.duration(remainMs)
                .let { if (it.isNotEmpty() && anyUnknown) "$it+" else it }
            val countLabel = pluralStringResource(
                R.plurals.episodes_count, queue.size, queue.size
            )
            Text(
                if (remainLabel.isEmpty()) {
                    countLabel // durations all unknown — no "to go" estimate
                } else {
                    countLabel + " · " + stringResource(R.string.time_to_go, remainLabel)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp)
            )
        }
        // bottom-up ordering moves the SmartPlay strip to the bottom too —
        // everything "next" lives at the same end of the screen
        val nextAtBottom = AppSettings.queueNextAtBottom
        if (!nextAtBottom) {
            SmartPlayRow(
                repository = repository,
                player = player,
                onEdit = onEditSmartPlay,
                snackbar = snackbar
            )
        }

        val playerState by player.state.collectAsStateWithLifecycle()

        if (queue.isEmpty()) {
            if (playerState.episodeId != null) {
                NowPlayingCard(player = player, state = playerState)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(
                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                    title = stringResource(R.string.up_next_is_empty),
                    hint = stringResource(R.string.queue_empty_hint)
                )
            }
            if (nextAtBottom) {
                SmartPlayRow(
                repository = repository,
                player = player,
                onEdit = onEditSmartPlay,
                snackbar = snackbar
            )
            }
            return@Column
        }

        // optional bottom-up ordering: next-to-play sits at the BOTTOM; the
        // list itself is bottom-anchored so episodes fall to the bottom
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            QueueList(
                queue = queue,
                reversed = nextAtBottom,
                podcastsById = podcastsById,
                repository = repository,
                player = player,
                playerState = playerState,
                scope = scope,
                snackbar = snackbar,
                onPodcastClick = onPodcastClick
            )
        }
        if (nextAtBottom) {
            SmartPlayRow(
                repository = repository,
                player = player,
                onEdit = onEditSmartPlay,
                snackbar = snackbar
            )
        }
    }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun QueueList(
    queue: List<com.stepcast.app.data.Episode>,
    reversed: Boolean,
    podcastsById: Map<Long, com.stepcast.app.data.Podcast>,
    repository: PodcastRepository,
    player: PlayerConnection,
    playerState: com.stepcast.app.ui.PlayerUiState,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
    onPodcastClick: (Long) -> Unit
) {
    var detailsFor by remember {
        mutableStateOf<com.stepcast.app.data.Episode?>(null)
    }
    var menuForId by remember { mutableStateOf<Long?>(null) }
    // Drag reorder via the Reorderable library (sh.calvin.reorderable): it
    // owns the gesture, the row-follows-finger offset, neighbour placement
    // and edge auto-scroll — the parts four hand-rolled attempts kept
    // getting subtly wrong (slop lag, drifting offsets under mixed row
    // heights, auto-scroll coordinate spaces, squashed rows). While
    // dragging, moves happen in a LOCAL copy of the list (no DB churn
    // fighting the gesture); the final order is persisted once on drop, and
    // the local copy is dropped once Room has the new order. The list is
    // ALWAYS in queue order (index 0 = next to play); bottom-up display is
    // purely reverseLayout, which the library handles.
    var working by remember {
        mutableStateOf<List<com.stepcast.app.data.Episode>?>(null)
    }
    var persistJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val latestQueue by androidx.compose.runtime.rememberUpdatedState(queue)
    val context = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val display = working ?: queue

    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val list = (working ?: latestQueue).toMutableList()
        val fromIdx = list.indexOfFirst { it.id == from.key }
        val toIdx = list.indexOfFirst { it.id == to.key }
        if (fromIdx < 0 || toIdx < 0) return@rememberReorderableLazyListState
        list.add(toIdx, list.removeAt(fromIdx))
        working = list
        view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }

    // drop the local copy once the DB order is what we show — never while a
    // drag or its save is in flight (an emission from BEFORE the save
    // landed would snap every row back to the pre-drag order)
    LaunchedEffect(queue) {
        if (!reorderState.isAnyItemDragging && persistJob?.isActive != true) working = null
    }

    fun persistOrder() {
        val snapshot = working ?: return
        persistJob = scope.launch {
            repository.replaceQueue(snapshot.map { it.id })
            if (latestQueue.map { it.id } == snapshot.map { it.id } &&
                !reorderState.isAnyItemDragging
            ) {
                working = null
            }
        }
    }

    /**
     * One-step move in PLAY order, for TalkBack users who can't drag.
     * Persists immediately since there's no gesture end to hook.
     */
    fun moveByOne(episodeId: Long, later: Boolean): Boolean {
        val list = (working ?: latestQueue).toMutableList()
        val i = list.indexOfFirst { it.id == episodeId }
        val j = if (later) i + 1 else i - 1
        if (i < 0 || j !in list.indices) return false
        list.add(j, list.removeAt(i))
        working = list
        persistOrder()
        return true
    }

    // reverseLayout anchors the list to the bottom edge and renders the
    // first declared item there — exactly the "next episode at the bottom,
    // episodes fall to the bottom" shape
    LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = reversed) {
        // the current episode rides at the head of the list (top normally,
        // bottom edge when reversed) so it always sits next to what's next
        if (playerState.episodeId != null) {
            item(key = "now-playing") {
                Box(Modifier.animateItem()) {
                    NowPlayingCard(player = player, state = playerState)
                }
            }
        }
        itemsIndexed(display, key = { _, ep -> ep.id }) { index, episode ->
            val podcast = podcastsById[episode.podcastId]
            val moveEarlierLabel = stringResource(R.string.move_earlier_in_queue)
            val moveLaterLabel = stringResource(R.string.move_later_in_queue)
            ReorderableItem(reorderState, key = episode.id) { isDragging ->
            val handleModifier = Modifier.draggableHandle(
                onDragStarted = {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                },
                onDragStopped = { persistOrder() }
            )
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                // lifted rows cast a shadow so the reorder reads as a pick-up
                shadowElevation = if (isDragging) 6.dp else 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    // drag needs sight; these give TalkBack a way to reorder
                    .semantics {
                        customActions = listOf(
                            androidx.compose.ui.semantics.CustomAccessibilityAction(
                                moveEarlierLabel
                            ) { moveByOne(episode.id, later = false) },
                            androidx.compose.ui.semantics.CustomAccessibilityAction(
                                moveLaterLabel
                            ) { moveByOne(episode.id, later = true) }
                        )
                    }
            ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { player.play(episode, podcast) },
                        onLongClick = { menuForId = episode.id }
                    )
                    .padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
            ) {
                DropdownMenu(
                    expanded = menuForId == episode.id,
                    onDismissRequest = { menuForId = null }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.episode_details)) },
                        onClick = { menuForId = null; detailsFor = episode }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    R.string.go_to_podcast,
                                    podcast?.title
                                        ?: stringResource(R.string.podcast_generic)
                                )
                            )
                        },
                        onClick = {
                            menuForId = null
                            onPodcastClick(episode.podcastId)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.mark_played)) },
                        onClick = {
                            menuForId = null
                            // setPlayed also drops it from the queue
                            scope.launch { repository.setPlayed(episode.id, true) }
                        }
                    )
                    // virtual-feed episodes are already local files
                    val isLocalFile = episode.audioUrl.startsWith("content:")
                    when {
                        isLocalFile -> Unit
                        episode.isDownloading -> DropdownMenuItem(
                            text = { Text(stringResource(R.string.cancel_download)) },
                            onClick = {
                                menuForId = null
                                DownloadWorker.cancel(context, episode.id)
                            }
                        )
                        episode.isDownloaded -> DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete_download)) },
                            onClick = {
                                menuForId = null
                                scope.launch { repository.deleteDownload(episode.id) }
                            }
                        )
                        else -> {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (episode.downloadStatus ==
                                                com.stepcast.app.data.Episode.DOWNLOAD_FAILED
                                            ) {
                                                R.string.retry_download
                                            } else {
                                                R.string.download
                                            }
                                        )
                                    )
                                },
                                onClick = {
                                    menuForId = null
                                    DownloadWorker.start(context, episode.id)
                                }
                            )
                            // With Wi-Fi-only on, offer a one-shot override for
                            // THIS episode so a single download (or retry) can
                            // go over mobile data without touching the global
                            // setting — same escape hatch EpisodeRow offers.
                            if (AppSettings.wifiOnlyDownloads) {
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(R.string.download_now_mobile_data))
                                    },
                                    onClick = {
                                        menuForId = null
                                        DownloadWorker.start(
                                            context, episode.id, allowMetered = true
                                        )
                                    }
                                )
                            }
                        }
                    }
                    // triage in PLAY order ("before" plays sooner), so the
                    // wording holds in both normal and bottom-up layouts
                    val playIndex = display.indexOfFirst { it.id == episode.id }
                    val undoLabel = stringResource(R.string.undo)
                    val queueContext = LocalContext.current
                    if (playIndex > 0) {
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(R.string.move_to_front_of_queue))
                            },
                            onClick = {
                                menuForId = null
                                val ids = display.map { it.id }
                                scope.launch {
                                    repository.replaceQueue(
                                        listOf(episode.id) +
                                            ids.filter { it != episode.id }
                                    )
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.remove_episodes_before_this)
                                )
                            },
                            onClick = {
                                menuForId = null
                                val ids = display.map { it.id }
                                val removed = playIndex
                                scope.launch {
                                    repository.replaceQueue(ids.drop(playIndex))
                                    val result = snackbar.showSnackbar(
                                        message = queueContext.resources
                                            .getQuantityString(
                                                R.plurals.n_removed_from_queue,
                                                removed, removed
                                            ),
                                        actionLabel = undoLabel,
                                        withDismissAction = true
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        repository.replaceQueue(ids)
                                    }
                                }
                            }
                        )
                    }
                    if (playIndex >= 0 && playIndex < display.lastIndex) {
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(R.string.move_to_end_of_queue))
                            },
                            onClick = {
                                menuForId = null
                                val ids = display.map { it.id }
                                scope.launch {
                                    repository.replaceQueue(
                                        ids.filter { it != episode.id } +
                                            episode.id
                                    )
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.remove_episodes_after_this)
                                )
                            },
                            onClick = {
                                menuForId = null
                                val ids = display.map { it.id }
                                val removed = display.lastIndex - playIndex
                                scope.launch {
                                    repository.replaceQueue(ids.take(playIndex + 1))
                                    val result = snackbar.showSnackbar(
                                        message = queueContext.resources
                                            .getQuantityString(
                                                R.plurals.n_removed_from_queue,
                                                removed, removed
                                            ),
                                        actionLabel = undoLabel,
                                        withDismissAction = true
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        repository.replaceQueue(ids)
                                    }
                                }
                            }
                        )
                    }
                }
                Icon(
                    Icons.Rounded.DragHandle,
                    contentDescription = stringResource(R.string.reorder),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .then(handleModifier)
                )
                AsyncImage(
                    model = remember(episode.imageUrl, podcast?.imageUrl) {
                        coil.request.ImageRequest.Builder(context)
                            .data(episode.imageUrl ?: podcast?.imageUrl)
                            // the show's art is almost always already warm
                            // in Coil's memory cache from elsewhere on
                            // screen — show it immediately instead of a
                            // blank square while episode art loads
                            .apply {
                                val fallback = podcast?.imageUrl
                                if (episode.imageUrl != null && fallback != null) {
                                    placeholderMemoryCacheKey(fallback)
                                }
                            }
                            .build()
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .progressBorder(
                            fraction = episode.progressFraction,
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                            strokeWidth = 3.5.dp
                        )
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                ) {
                    Text(
                        episode.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when {
                            episode.isDownloading -> {
                                CircularProgressIndicator(
                                    progress = { episode.downloadProgress / 100f },
                                    strokeWidth = 1.5.dp,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            episode.isDownloaded -> {
                                Icon(
                                    Icons.Rounded.DownloadDone,
                                    contentDescription = stringResource(R.string.downloaded),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            episode.downloadStatus ==
                                com.stepcast.app.data.Episode.DOWNLOAD_FAILED -> {
                                Icon(
                                    Icons.Rounded.ErrorOutline,
                                    contentDescription = stringResource(R.string.download_failed),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                        }
                        Text(
                            podcast?.title.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                val removedMsg = stringResource(R.string.removed_from_queue)
                val undoLabel = stringResource(R.string.undo)
                IconButton(
                    onClick = {
                        // snapshot the order so UNDO restores the exact slot
                        val before = (working ?: queue).map { it.id }
                        scope.launch {
                            repository.removeFromQueue(episode.id)
                            val result = snackbar.showSnackbar(
                                message = removedMsg,
                                actionLabel = undoLabel,
                                withDismissAction = true
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                repository.replaceQueue(before)
                            }
                        }
                    }
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.remove_from_queue)
                    )
                }
            }
            }
            }
        }
    }

    detailsFor?.let { episode ->
        EpisodeDetailsDialog(
            episode = episode,
            onDismiss = { detailsFor = null },
            onPlay = {
                detailsFor = null
                player.play(episode, podcastsById[episode.podcastId])
            }
        )
    }
}

/**
 * Slim strip for the current episode — playing, paused, or interrupted by a
 * force-close. Deliberately smaller than a queue row (the pill below already
 * carries full transport); this just marks where "now" sits relative to the
 * queue and gives one tap to resume.
 */
@Composable
private fun NowPlayingCard(
    player: PlayerConnection,
    state: com.stepcast.app.ui.PlayerUiState
) {
    val progress by player.progress.collectAsStateWithLifecycle()
    Surface(
        onClick = { player.togglePlayPause() },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            AsyncImage(
                model = state.artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .progressBorder(
                        fraction = progress.fraction,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onPrimaryContainer
                            .copy(alpha = 0.35f),
                        strokeWidth = 2.5.dp
                    )
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 10.dp)
            ) {
                Text(
                    stringResource(
                        if (state.isPlaying) {
                            R.string.playing
                        } else {
                            R.string.paused_tap_to_continue
                        }
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    state.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(
                    if (state.isPlaying) R.string.pause else R.string.play
                ),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}
