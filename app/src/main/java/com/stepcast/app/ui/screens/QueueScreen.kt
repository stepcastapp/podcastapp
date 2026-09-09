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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.verticalDrag
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
import androidx.compose.ui.input.pointer.positionChange
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
import com.stepcast.app.download.DownloadWorker
import com.stepcast.app.ui.Formatters
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
    // Viewport-space top edge of the dragged row: where it sat when the
    // gesture began, plus every finger delta since. This — not a delta from
    // the row's slot — is the anchor, because translationY is then derived
    // as (floatTop - the slot's CURRENT layout offset). Any move of the slot
    // (a reorder, an auto-scroll) is absorbed on the very next frame with no
    // hand-rolled compensation. The old code adjusted a running offset by a
    // REMEMBERED row height on each swap; whenever the passed row wasn't the
    // height that guess assumed — which is most of the time in a queue that
    // mixes one- and two-line titles — the error stuck, and rows drew
    // overlapped and squashed.
    var floatTop by remember { mutableStateOf(0f) }
    var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var working by remember {
        mutableStateOf<List<com.stepcast.app.data.Episode>?>(null)
    }
    var fingerDown by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // pointerInput blocks only restart when their key changes, so the drag
    // handlers hold FIRST-composition captures forever. Anything they read
    // must go through live state — otherwise the second drag of a session
    // reorders against the stale pre-first-drag queue and the drop appears
    // to revert moments later.
    val latestQueue by androidx.compose.runtime.rememberUpdatedState(queue)
    val latestReversed by androidx.compose.runtime.rememberUpdatedState(reversed)
    val context = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val display = working ?: queue

    LaunchedEffect(queue, draggingId) {
        if (draggingId == null) working = null
    }

    // how close to a viewport edge the dragged row gets before the list
    // starts scrolling itself, and how fast it may do so
    val edgeThresholdPx = with(LocalDensity.current) { 64.dp.toPx() }
    val maxAutoScrollPx = with(LocalDensity.current) { 16.dp.toPx() }

    /**
     * The dragged row's current slot offset, straight from the list's own
     * layout. Null when the row has been scrolled out of the visible window.
     */
    fun slotOffsetOf(episodeId: Long): Float? =
        listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == episodeId }?.offset?.toFloat()

    /**
     * Drops the dragged row into whichever slot its midpoint now sits over,
     * moving it there in the local copy of the list.
     *
     * Geometric containment rather than a swap-past-a-threshold: the slots
     * come from the real layout, so rows of any height land correctly, and
     * hysteresis is free — after a move the midpoint sits inside the row's
     * OWN slot, and slots are disjoint, so nothing else can match until the
     * finger has travelled a further full row. That also makes the whole
     * thing reverseLayout-agnostic: offsets are always visual top-down, and
     * the target is mapped back to a list index by key, so bottom-up mode
     * needs no direction flipping at all.
     */
    fun reorderToPointer(episodeId: Long) {
        val info = listState.layoutInfo
        val self = info.visibleItemsInfo
            .firstOrNull { it.key == episodeId } ?: return
        val midpoint = floatTop + self.size / 2f
        // the now-playing header carries a String key, so it can never be
        // picked as a drop target
        val targetId = info.visibleItemsInfo.firstOrNull {
            val k = it.key
            k is Long && k != episodeId &&
                midpoint >= it.offset && midpoint < it.offset + it.size
        }?.key as? Long ?: return
        val list = working ?: latestQueue
        val from = list.indexOfFirst { it.id == episodeId }
        val to = list.indexOfFirst { it.id == targetId }
        if (from < 0 || to < 0 || from == to) return
        val moved = list.toMutableList()
        moved.add(to, moved.removeAt(from))
        working = moved
        view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }

    // Auto-scroll while a drag holds near the top/bottom of the visible list
    // — without this, a drag can't reach past whatever happens to be
    // on-screen when the gesture starts.
    //
    // Nothing is fed back into the drag here: floatTop is anchored to the
    // finger, which is stationary while the list auto-scrolls, so the slots
    // simply slide underneath it and translationY re-derives itself. The
    // scroll only has to happen and the row re-home itself against the new
    // layout.
    LaunchedEffect(draggingId) {
        if (draggingId == null) return@LaunchedEffect
        while (isActive) {
            androidx.compose.runtime.withFrameNanos { }
            val id = draggingId ?: break
            if (!fingerDown) break
            val info = listState.layoutInfo
            val self = info.visibleItemsInfo.firstOrNull { it.key == id } ?: continue
            val distanceFromTop = floatTop - info.viewportStartOffset
            val distanceFromBottom = info.viewportEndOffset - (floatTop + self.size)
            // A positive scroll always walks toward HIGHER indices, and those
            // render above in a reversed list but below in a normal one — so
            // the sign that reveals content above genuinely does depend on
            // reverseLayout, contrary to what this comment used to claim.
            val towardAbove = if (latestReversed) 1f else -1f
            val delta = when {
                distanceFromTop < edgeThresholdPx -> {
                    val strength =
                        (1f - (distanceFromTop / edgeThresholdPx)).coerceIn(0.15f, 1f)
                    towardAbove * maxAutoScrollPx * strength
                }
                distanceFromBottom < edgeThresholdPx -> {
                    val strength =
                        (1f - (distanceFromBottom / edgeThresholdPx)).coerceIn(0.15f, 1f)
                    -towardAbove * maxAutoScrollPx * strength
                }
                else -> 0f
            }
            if (delta != 0f) {
                listState.scrollBy(delta)
                reorderToPointer(id)
            }
        }
    }

    /**
     * One-step move in PLAY order, for TalkBack users who can't drag.
     * Persists immediately since there's no gesture end to hook.
     */
    fun moveByOne(episodeId: Long, later: Boolean): Boolean {
        // the list is always in play order, so "later" is simply +1
        val list = latestQueue
        val from = list.indexOfFirst { it.id == episodeId }
        val to = if (later) from + 1 else from - 1
        if (from < 0 || to !in list.indices) return false
        val moved = list.toMutableList()
        moved.add(to, moved.removeAt(from))
        scope.launch { repository.replaceQueue(moved.map { it.id }) }
        return true
    }

    // Stiffer than the default so a displaced row clears quickly. Used for
    // ordinary list changes only — during a drag every row reflows instantly
    // instead (see animateItem below); stiffening the spring was the earlier
    // attempt at the same problem and it was not enough.
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
                    // NO placement animation for ANY row while a drag is live.
                    // The dragged row is positioned by translationY, which is
                    // instant; a displaced neighbour on a spring is not, and
                    // while the finger keeps moving each new swap restarts
                    // that spring before the last one settled. The neighbour
                    // is then permanently mid-flight, and since the dragged
                    // row sits above it on zIndex the pair renders as one
                    // squashed double-row with the lower one's title and ✕
                    // clipped (screen recording). Instant reflow is less
                    // decorative but it is always correct: every non-dragged
                    // row is exactly in its slot, so the only thing offset is
                    // the row under the finger. The spring is still used for
                    // ordinary list changes outside a drag.
                    .animateItem(
                        placementSpec = if (draggingId != null) null else placementSpec
                    )
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
                    // Read in the DRAW phase, after layout has settled, so a
                    // reorder and the offset that cancels it always land on
                    // the same frame — the row can never be drawn at a new
                    // slot with a stale offset, which is what made pairs of
                    // rows overlap. Only the dragged row reads layoutInfo;
                    // for every other row the check short-circuits.
                    .graphicsLayer {
                        translationY = if (draggingId == episode.id) {
                            slotOffsetOf(episode.id)?.let { floatTop - it } ?: 0f
                        } else {
                            0f
                        }
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
                        .pointerInput(episode.id) {
                            fun finishDrag() {
                                val snapshot = working
                                settleJob = scope.launch {
                                    if (snapshot != null) {
                                        repository.replaceQueue(snapshot.map { it.id })
                                    }
                                    // ease the row into its slot instead of
                                    // snapping the leftover offset to zero.
                                    // translationY is floatTop minus the slot,
                                    // so settling means walking floatTop onto
                                    // it; the layout is frozen on `working`
                                    // until draggingId clears, so the target
                                    // holds still for the whole animation.
                                    val slot = slotOffsetOf(episode.id)
                                    if (slot != null) {
                                        androidx.compose.animation.core.animate(
                                            initialValue = floatTop,
                                            targetValue = slot,
                                            animationSpec =
                                                androidx.compose.animation.core.tween(120)
                                        ) { value, _ -> floatTop = value }
                                    }
                                    draggingId = null
                                }
                            }
                            // Hand-rolled rather than detectDragGestures, for
                            // two reasons that both read as "the row doesn't
                            // follow my finger":
                            //  - detectDragGestures DISCARDS the touch-slop
                            //    distance. It reports only deltas AFTER slop
                            //    is exceeded, so the row starts a slop's worth
                            //    behind the finger and stays there for the
                            //    whole gesture. The await* form below hands
                            //    that overslop back, so the row starts under
                            //    the finger instead of trailing it.
                            //  - its slop phase doesn't consume, so the
                            //    LazyColumn underneath could win the gesture
                            //    and scroll instead of reordering.
                            // Vertical-only slop also stops a sloppy diagonal
                            // grab from needing a bigger 2D movement first.
                            awaitEachGesture {
                                val first = awaitFirstDown(requireUnconsumed = false)
                                var overSlop = 0f
                                val dragged = awaitVerticalTouchSlopOrCancellation(
                                    first.id
                                ) { change, over ->
                                    overSlop = over
                                    change.consume()
                                } ?: return@awaitEachGesture
                                val start = slotOffsetOf(episode.id)
                                    ?: return@awaitEachGesture
                                settleJob?.cancel()
                                draggingId = episode.id
                                // fold the slop back in so the row starts
                                // under the finger rather than a slop behind
                                floatTop = start + overSlop
                                fingerDown = true
                                view.performHapticFeedback(
                                    android.view.HapticFeedbackConstants.LONG_PRESS
                                )
                                reorderToPointer(episode.id)
                                try {
                                    verticalDrag(dragged.id) { change ->
                                        // READ BEFORE CONSUMING.
                                        // positionChange() returns
                                        // Offset.Zero once the change is
                                        // consumed, so consuming first fed
                                        // every delta in as 0 and the row
                                        // never moved at all.
                                        val deltaY = change.positionChange().y
                                        change.consume()
                                        floatTop += deltaY
                                        reorderToPointer(episode.id)
                                    }
                                } finally {
                                    fingerDown = false
                                }
                                finishDrag()
                            }
                        }
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
