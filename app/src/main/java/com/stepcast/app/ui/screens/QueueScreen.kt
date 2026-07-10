package com.stepcast.app.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
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
import com.stepcast.app.ui.PlayerConnection
import com.stepcast.app.ui.progressBorder
import com.stepcast.app.ui.theme.EmptyState
import com.stepcast.app.ui.theme.ScreenTitle
import kotlinx.coroutines.isActive
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
    val queue by repository.queue.collectAsState(initial = emptyList())
    val podcasts by repository.podcasts.collectAsState(initial = emptyList())
    val podcastsById = podcasts.associateBy { it.id }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        val downloadActivity by repository.downloadActivity
            .collectAsState(initial = emptyList())
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
            val hours = remainMs / 3_600_000
            val minutes = (remainMs % 3_600_000) / 60_000
            val remainLabel = if (hours > 0) {
                stringResource(R.string.hours_minutes_compact, hours, minutes)
            } else {
                stringResource(R.string.minutes_compact, minutes)
            }
            Text(
                pluralStringResource(
                    R.plurals.episodes_count, queue.size, queue.size
                ) + " · " + stringResource(R.string.time_to_go, remainLabel),
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

        val playerState by player.state.collectAsState()

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
    // handle-driven drag reorder. While dragging, swaps happen in a LOCAL
    // copy of the list (no DB churn fighting the gesture); the final order
    // is persisted once on drag end, and the local copy is dropped when the
    // matching Room emission arrives. The list is ALWAYS in queue order
    // (index 0 = next to play); bottom-up display is purely reverseLayout.
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var working by remember {
        mutableStateOf<List<com.stepcast.app.data.Episode>?>(null)
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // pointerInput blocks only restart when their key changes, so the drag
    // handlers hold FIRST-composition captures forever. Anything they read
    // must go through live state — otherwise the second drag of a session
    // reorders against the stale pre-first-drag queue and the drop appears
    // to revert moments later.
    val latestQueue by androidx.compose.runtime.rememberUpdatedState(queue)
    val latestReversed by androidx.compose.runtime.rememberUpdatedState(reversed)
    val fallbackRowPx = with(LocalDensity.current) { 68.dp.toPx() }
    // how far a row may poke past the end of the list — just enough to feel
    // alive, not enough to ride over the now-playing strip
    val edgeSlopPx = with(LocalDensity.current) { 10.dp.toPx() }
    // real slot heights, measured — hardcoded guesses made each swap
    // over/under-compensate the drag offset, which read as stutter
    val rowHeights = remember { mutableMapOf<Long, Int>() }
    val view = androidx.compose.ui.platform.LocalView.current
    val display = working ?: queue

    LaunchedEffect(queue) {
        if (draggingId == null) working = null
    }

    // auto-scroll while a drag holds near the top/bottom of the visible
    // list — without this, a drag can't reach past whatever happens to be
    // on-screen when the gesture starts. Sign is purely "reveal content
    // above" (negative) vs "reveal content below" (positive): that scroll
    // convention is independent of reverseLayout, so no special-casing
    // for the bottom-anchored queue mode is needed here.
    val edgeThresholdPx = with(LocalDensity.current) { 64.dp.toPx() }
    val maxAutoScrollPx = with(LocalDensity.current) { 16.dp.toPx() }
    LaunchedEffect(draggingId) {
        if (draggingId == null) return@LaunchedEffect
        while (isActive) {
            androidx.compose.runtime.withFrameNanos { }
            val id = draggingId ?: break
            val info = listState.layoutInfo
            val itemInfo = info.visibleItemsInfo.firstOrNull { it.key == id } ?: continue
            val viewportHeight = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
            val currentTop = itemInfo.offset + dragOffset
            val currentBottom = currentTop + itemInfo.size
            val distanceFromTop = currentTop
            val distanceFromBottom = viewportHeight - currentBottom
            when {
                distanceFromTop < edgeThresholdPx -> {
                    val strength = (1f - (distanceFromTop / edgeThresholdPx)).coerceIn(0.15f, 1f)
                    listState.scrollBy(-maxAutoScrollPx * strength)
                }
                distanceFromBottom < edgeThresholdPx -> {
                    val strength =
                        (1f - (distanceFromBottom / edgeThresholdPx)).coerceIn(0.15f, 1f)
                    listState.scrollBy(maxAutoScrollPx * strength)
                }
            }
        }
    }

    /**
     * Swaps the dragged row with its on-screen neighbor. [screenDown] is the
     * visual direction; with reverseLayout the index direction flips.
     * Returns the passed neighbor's measured height, or null if at the edge.
     */
    fun swapNeighbor(episodeId: Long, screenDown: Boolean): Float? {
        val list = (working ?: latestQueue).toMutableList()
        val i = list.indexOfFirst { it.id == episodeId }
        val step = if (screenDown != latestReversed) 1 else -1
        val j = i + step
        if (i < 0 || j !in list.indices) return null
        val neighbor = list[j]
        list[j] = list[i]
        list[i] = neighbor
        working = list
        view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        return (rowHeights[neighbor.id] ?: rowHeights[episodeId])?.toFloat()
            ?: fallbackRowPx
    }

    /**
     * One-step move in PLAY order, for TalkBack users who can't drag.
     * Persists immediately since there's no gesture end to hook.
     */
    fun moveByOne(episodeId: Long, later: Boolean): Boolean {
        // play-order direction → screen direction through the reversed flag
        val screenDown = if (later) !latestReversed else latestReversed
        val moved = swapNeighbor(episodeId, screenDown) != null
        if (moved) {
            val snapshot = working
            if (snapshot != null) {
                scope.launch { repository.replaceQueue(snapshot.map { it.id }) }
            }
        }
        return moved
    }

    // stiffer than the default so a displaced row clears the dragged one's
    // path quickly — the slow spring left rows overlapping mid-shuffle
    val placementSpec = androidx.compose.animation.core.spring(
        stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
        visibilityThreshold = androidx.compose.ui.unit.IntOffset(1, 1)
    )
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
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                // lifted rows cast a shadow so the reorder reads as a pick-up
                shadowElevation = if (draggingId == episode.id) 6.dp else 0.dp,
                modifier = Modifier
                    // the dragged row is positioned by translationY alone; a
                    // placement animation on top of it fights the finger
                    .animateItem(
                        placementSpec = if (draggingId == episode.id) {
                            null
                        } else {
                            placementSpec
                        }
                    )
                    .onSizeChanged { rowHeights[episode.id] = it.height }
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
                    .zIndex(if (draggingId == episode.id) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (draggingId == episode.id) dragOffset else 0f
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
                        .pointerInput(episode.id) {
                            fun finishDrag() {
                                val snapshot = working
                                settleJob = scope.launch {
                                    if (snapshot != null) {
                                        repository.replaceQueue(snapshot.map { it.id })
                                    }
                                    // ease the row into its slot instead of
                                    // snapping the leftover offset to zero
                                    androidx.compose.animation.core.animate(
                                        initialValue = dragOffset,
                                        targetValue = 0f,
                                        animationSpec =
                                            androidx.compose.animation.core.tween(120)
                                    ) { value, _ -> dragOffset = value }
                                    draggingId = null
                                }
                            }
                            detectDragGestures(
                                onDragStart = {
                                    settleJob?.cancel()
                                    draggingId = episode.id
                                    dragOffset = 0f
                                    view.performHapticFeedback(
                                        android.view.HapticFeedbackConstants.LONG_PRESS
                                    )
                                },
                                onDragEnd = { finishDrag() },
                                onDragCancel = { finishDrag() }
                            ) { change, dragAmount ->
                                change.consume()
                                dragOffset += dragAmount.y
                                // hard stop at the ends of the list: without
                                // this the row rides over the now-playing strip
                                val list = working ?: latestQueue
                                val i = list.indexOfFirst { it.id == episode.id }
                                val downLimit = if (latestReversed) 0 else list.lastIndex
                                val upLimit = if (latestReversed) list.lastIndex else 0
                                if (i == downLimit) {
                                    dragOffset = dragOffset.coerceAtMost(edgeSlopPx)
                                }
                                if (i == upLimit) {
                                    dragOffset = dragOffset.coerceAtLeast(-edgeSlopPx)
                                }
                                val rowPx =
                                    rowHeights[episode.id]?.toFloat() ?: fallbackRowPx
                                if (dragOffset > rowPx * 0.55f) {
                                    val passed = swapNeighbor(episode.id, screenDown = true)
                                    if (passed != null) dragOffset -= passed
                                } else if (dragOffset < -rowPx * 0.55f) {
                                    val passed = swapNeighbor(episode.id, screenDown = false)
                                    if (passed != null) dragOffset += passed
                                }
                            }
                        }
                )
                AsyncImage(
                    model = episode.imageUrl ?: podcast?.imageUrl,
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
                    Text(
                        podcast?.title.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
    val progress by player.progress.collectAsState()
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
