package com.nsavage.stepcast.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.nsavage.stepcast.R
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nsavage.stepcast.data.AppSettings
import com.nsavage.stepcast.ui.PlayerConnection
import com.nsavage.stepcast.ui.PlayerUiState
import com.nsavage.stepcast.ui.progressBorder
import com.nsavage.stepcast.ui.theme.StepMark
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.launch

private val speedChoices = listOf(1.0f, 1.2f, 1.5f, 1.8f, 2.0f, 2.5f, 3.0f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerSheet(
    state: PlayerUiState,
    player: PlayerConnection,
    repository: com.nsavage.stepcast.data.PodcastRepository,
    onOpenPodcast: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val progress by player.progress.collectAsState()
    val view = androidx.compose.ui.platform.LocalView.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var sleepDialogOpen by remember { mutableStateOf(false) }
    var chapterListOpen by remember { mutableStateOf(false) }
    var notesOpen by remember { mutableStateOf(false) }
    var skipsOpen by remember { mutableStateOf(false) }
    var transcriptOpen by remember { mutableStateOf(false) }

    // the playing episode + its podcast, for notes / feed / skip settings
    var episode by remember {
        mutableStateOf<com.nsavage.stepcast.data.Episode?>(null)
    }
    var podcast by remember {
        mutableStateOf<com.nsavage.stepcast.data.Podcast?>(null)
    }
    androidx.compose.runtime.LaunchedEffect(state.episodeId) {
        episode = state.episodeId?.let { repository.episode(it) }
        podcast = episode?.let { repository.podcast(it.podcastId) }
    }
    // While the user drags the slider, show the drag position, not playback.
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    // an in-scaffold overlay, NOT a ModalBottomSheet: the bottom nav bar
    // stays visible (and tappable) underneath while the player is open
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    val currentOnDismiss by androidx.compose.runtime.rememberUpdatedState(onDismiss)
    // Swipe-down-to-close from anywhere on the sheet. The content column is
    // a verticalScroll container, so a plain drag detector would fight it;
    // a nested-scroll connection instead watches the delta the scroller
    // could NOT consume — leftover downward drag means the user is pulling
    // down while already at the top, i.e. a dismiss gesture.
    val dismissPullPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        72.dp.toPx()
    }
    val dismissConnection = remember(dismissPullPx) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            private var pulled = 0f
            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource
            ): androidx.compose.ui.geometry.Offset {
                if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) {
                    if (available.y > 0f && consumed.y == 0f) {
                        pulled += available.y
                        if (pulled > dismissPullPx) {
                            pulled = 0f
                            currentOnDismiss()
                        }
                    } else {
                        // scrolled or moved upward — not a pull-down
                        pulled = 0f
                    }
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override suspend fun onPostFling(
                consumed: androidx.compose.ui.unit.Velocity,
                available: androidx.compose.ui.unit.Velocity
            ): androidx.compose.ui.unit.Velocity {
                // gesture ended — don't let short pulls accumulate across
                // separate swipes
                pulled = 0f
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.layout.Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount > 14f) currentOnDismiss()
                    }
                }
                .clickable { currentOnDismiss() }
                .padding(vertical = 10.dp)
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // Center, not Top: on most phones this content is shorter than
            // the available height, and top-aligning inside a weight(1f)
            // scroll area left a large dead gap below the utility row. A
            // bounded-height Column (via weight) + verticalScroll measures
            // children with a fixed outer height but unbounded inner
            // height for the scroll — arrangement still applies to the
            // leftover space when content fits, and simply has no visible
            // effect once content overflows and scrolling takes over.
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .nestedScroll(dismissConnection)
                .background(
                    Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        0.55f to Color.Transparent
                    )
                )
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(vertical = 16.dp)
        ) {
            AsyncImage(
                model = state.artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth(0.78f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .progressBorder(
                        fraction = dragFraction ?: progress.fraction,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                        strokeWidth = 4.5.dp,
                        cornerRadius = 20.dp
                    )
            )
            Spacer(Modifier.height(20.dp))
            StepMark()
            Spacer(Modifier.height(12.dp))
            // tap the episode title for its show notes
            Text(
                state.title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(enabled = episode != null) {
                    notesOpen = true
                }
            )
            // the show name reads as a link and jumps to its feed
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = podcast != null) {
                        podcast?.let { onOpenPodcast(it.id) }
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    state.podcastTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = stringResource(
                        R.string.open_show_cd, state.podcastTitle
                    ),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }

            if (state.chapters.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(onClick = player::seekToPreviousChapter) {
                        Icon(
                            Icons.Rounded.ChevronLeft,
                            contentDescription = stringResource(R.string.previous_chapter)
                        )
                    }
                    val currentChapter = state.chapters
                        .lastOrNull { it.startMs <= progress.positionMs }
                    // tap the title for the full chapter list
                    Text(
                        currentChapter?.title.orEmpty(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { chapterListOpen = true }
                    )
                    IconButton(onClick = player::seekToNextChapter) {
                        Icon(
                            Icons.Rounded.ChevronRight,
                            contentDescription = stringResource(R.string.next_chapter)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Slider(
                value = dragFraction ?: progress.fraction,
                onValueChange = { dragFraction = it },
                onValueChangeFinished = {
                    dragFraction?.let { f ->
                        if (progress.durationMs > 0) {
                            player.seekTo((f * progress.durationMs).roundToLong())
                        }
                    }
                    dragFraction = null
                },
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth()) {
                val shownPosition = dragFraction
                    ?.let { (it * progress.durationMs).roundToLong() }
                    ?: progress.positionMs
                Text(
                    formatTime(shownPosition),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "-" + formatTime((progress.durationMs - shownPosition).coerceAtLeast(0)),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = player::skipToPrevious, enabled = state.hasPrevious) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = stringResource(R.string.previous_episode))
                }
                FilledTonalIconButton(
                    onClick = player::seekBack,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        seekBackIcon(AppSettings.seekBackSeconds),
                        contentDescription = stringResource(
                            R.string.back_seconds_cd, AppSettings.seekBackSeconds
                        ),
                        modifier = Modifier.size(28.dp)
                    )
                }
                FilledIconButton(
                    onClick = {
                        view.performHapticFeedback(
                            android.view.HapticFeedbackConstants.KEYBOARD_TAP
                        )
                        player.togglePlayPause()
                    },
                    modifier = Modifier.size(76.dp)
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.play_pause),
                        modifier = Modifier.size(42.dp)
                    )
                }
                FilledTonalIconButton(
                    onClick = player::seekForward,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        seekForwardIcon(AppSettings.seekForwardSeconds),
                        contentDescription = stringResource(
                            R.string.forward_seconds_cd, AppSettings.seekForwardSeconds
                        ),
                        modifier = Modifier.size(28.dp)
                    )
                }
                IconButton(onClick = player::skipToNext, enabled = state.hasNext) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.next_episode))
                }
                IconButton(onClick = {
                    view.performHapticFeedback(
                        android.view.HapticFeedbackConstants.CONTEXT_CLICK
                    )
                    player.skipToNextAndDelete()
                }) {
                    Icon(
                        Icons.Rounded.DeleteSweep,
                        contentDescription = stringResource(R.string.done_mark_played_delete_next)
                    )
                }
            }

            // per-show mid-roll jump: a SECOND forward sized to this show's
            // ad breaks (configured in the skips dialog), separate from the
            // global seek-forward
            val adJumpSec = podcast?.adJumpSec ?: 0
            if (adJumpSec > 0) {
                androidx.compose.material3.AssistChip(
                    onClick = {
                        view.performHapticFeedback(
                            android.view.HapticFeedbackConstants.KEYBOARD_TAP
                        )
                        player.seekBy(adJumpSec * 1000L)
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.FastForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    label = {
                        Text(
                            stringResource(R.string.ad_jump_chip, adJumpSec),
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Spacer(Modifier.height(14.dp))
            // word-free utility row: speed, intro/outro trims, sleep timer,
            // share (notes live on the episode title, the show link on its
            // name; the speed chips fold into a dialog behind the readout)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                var speedOpen by remember { mutableStateOf(false) }
                IconButton(onClick = { speedOpen = true }) {
                    val offDefault = abs(state.speed - 1.0f) > 0.05f
                    Text(
                        "${trimSpeed(state.speed)}×",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (offDefault) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                if (speedOpen) {
                    AlertDialog(
                        onDismissRequest = { speedOpen = false },
                        title = { Text(stringResource(R.string.playback_speed)) },
                        text = {
                            Column {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.horizontalScroll(rememberScrollState())
                                ) {
                                    for (choice in speedChoices) {
                                        FilterChip(
                                            selected = abs(state.speed - choice) < 0.05f,
                                            onClick = {
                                                player.setSpeed(choice)
                                                // like the skips dialog: the
                                                // choice sticks to this feed
                                                podcast?.let { p ->
                                                    scope.launch {
                                                        repository
                                                            .setPlaybackSpeed(p.id, choice)
                                                    }
                                                }
                                                speedOpen = false
                                            },
                                            label = {
                                                Text(
                                                    "${trimSpeed(choice)}x",
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                        )
                                    }
                                }
                                podcast?.let { p ->
                                    Text(
                                        stringResource(
                                            R.string.applies_to_every_episode_of, p.title
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { speedOpen = false }) { Text(stringResource(R.string.close)) }
                        }
                    )
                }
                Spacer(Modifier.width(20.dp))
                IconButton(
                    onClick = { skipsOpen = true },
                    enabled = podcast != null
                ) {
                    val hasSkips = (podcast?.introSkipSec ?: 0) > 0 ||
                        (podcast?.outroSkipSec ?: 0) > 0 ||
                        (podcast?.adJumpSec ?: 0) > 0
                    Icon(
                        Icons.Rounded.ContentCut,
                        contentDescription = stringResource(R.string.intro_and_outro_skips),
                        tint = if (hasSkips) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Spacer(Modifier.width(20.dp))
                IconButton(onClick = { sleepDialogOpen = true }) {
                    val sleepArmed = state.sleepEndsAtMs != null || state.sleepAtEpisodeEnd
                    androidx.compose.material3.BadgedBox(badge = {
                        state.sleepEndsAtMs?.let {
                            val minutesLeft =
                                ((it - System.currentTimeMillis()) / 60_000L + 1)
                                    .coerceAtLeast(1)
                            androidx.compose.material3.Badge {
                                Text(stringResource(R.string.minutes_compact, minutesLeft))
                            }
                        }
                    }) {
                        Icon(
                            if (sleepArmed) {
                                Icons.Rounded.HourglassTop
                            } else {
                                Icons.Rounded.HourglassEmpty
                            },
                            contentDescription = sleepLabel(state),
                            tint = if (sleepArmed) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                if (episode?.transcriptUrl != null) {
                    Spacer(Modifier.width(20.dp))
                    IconButton(onClick = { transcriptOpen = true }) {
                        Icon(
                            Icons.Rounded.Subtitles,
                            contentDescription = stringResource(R.string.transcript),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.width(20.dp))
                val context = androidx.compose.ui.platform.LocalContext.current
                IconButton(
                    onClick = {
                        val ep = episode
                        if (ep != null) {
                            val stamp = formatTime(progress.positionMs)
                            val text = buildString {
                                append("“${ep.title}”")
                                podcast?.let { append(" — ${it.title}") }
                                append(
                                    "\n" + context.getString(
                                        R.string.listen_from_stamp, stamp, ep.audioUrl
                                    )
                                )
                            }
                            val send = android.content.Intent(
                                android.content.Intent.ACTION_SEND
                            ).setType("text/plain").putExtra(
                                android.content.Intent.EXTRA_TEXT, text
                            )
                            context.startActivity(
                                android.content.Intent.createChooser(
                                    send, context.getString(R.string.share_episode)
                                )
                            )
                        }
                    },
                    enabled = episode != null
                ) {
                    Icon(
                        Icons.Rounded.Share,
                        contentDescription = stringResource(R.string.share_episode_with_timestamp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        }
    }

    if (notesOpen) {
        episode?.let {
            EpisodeDetailsDialog(episode = it, onDismiss = { notesOpen = false })
        }
    }

    if (skipsOpen) {
        podcast?.let { p ->
            var introText by remember(p.id) {
                mutableStateOf(if (p.introSkipSec > 0) p.introSkipSec.toString() else "")
            }
            var outroText by remember(p.id) {
                mutableStateOf(if (p.outroSkipSec > 0) p.outroSkipSec.toString() else "")
            }
            var adJumpText by remember(p.id) {
                mutableStateOf(if (p.adJumpSec > 0) p.adJumpSec.toString() else "")
            }
            AlertDialog(
                onDismissRequest = { skipsOpen = false },
                title = { Text(stringResource(R.string.skips_title, p.title)) },
                text = {
                    Column {
                        androidx.compose.material3.OutlinedTextField(
                            value = introText,
                            onValueChange = {
                                introText = it.filter(Char::isDigit).take(4)
                            },
                            label = { Text(stringResource(R.string.skip_intro_seconds)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        androidx.compose.material3.OutlinedTextField(
                            value = outroText,
                            onValueChange = {
                                outroText = it.filter(Char::isDigit).take(4)
                            },
                            label = { Text(stringResource(R.string.skip_outro_seconds)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        )
                        Text(
                            stringResource(R.string.skips_effect_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        androidx.compose.material3.OutlinedTextField(
                            value = adJumpText,
                            onValueChange = {
                                adJumpText = it.filter(Char::isDigit).take(3)
                            },
                            label = { Text(stringResource(R.string.ad_jump_seconds)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        )
                        Text(
                            stringResource(R.string.ad_jump_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                },
                confirmButton = {
                    val dialogContext = androidx.compose.ui.platform.LocalContext.current
                    TextButton(onClick = {
                        skipsOpen = false
                        scope.launch {
                            repository.setSkips(
                                p.id,
                                introText.toIntOrNull() ?: 0,
                                outroText.toIntOrNull() ?: 0
                            )
                            repository.setAdJump(p.id, adJumpText.toIntOrNull() ?: 0)
                            podcast = repository.podcast(p.id)
                            // the notification's ad-jump button follows suit
                            dialogContext.sendBroadcast(
                                android.content.Intent(
                                    dialogContext,
                                    com.nsavage.stepcast.playback.CommandReceiver::class.java
                                ).setAction(
                                    com.nsavage.stepcast.playback.CommandReceiver
                                        .ACTION_REFRESH_NOTIF_BUTTONS
                                )
                            )
                        }
                    }) { Text(stringResource(R.string.save)) }
                },
                dismissButton = {
                    TextButton(onClick = { skipsOpen = false }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }
    }

    if (transcriptOpen) {
        val ep = episode
        val transcriptUrl = ep?.transcriptUrl
        if (ep != null && transcriptUrl != null) {
            TranscriptDialog(
                url = transcriptUrl,
                type = ep.transcriptType,
                repository = repository,
                positionMs = progress.positionMs,
                onSeek = { player.seekTo(it) },
                onDismiss = { transcriptOpen = false }
            )
        }
    }

    if (sleepDialogOpen) {
        SleepTimerDialog(
            onDismiss = { sleepDialogOpen = false },
            onPick = { minutes, endOfEpisode ->
                sleepDialogOpen = false
                player.setSleepTimer(minutes, endOfEpisode)
            }
        )
    }

    if (chapterListOpen) {
        val current = state.chapters.lastOrNull { it.startMs <= progress.positionMs }
        AlertDialog(
            onDismissRequest = { chapterListOpen = false },
            title = { Text(stringResource(R.string.chapters)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (chapter in state.chapters) {
                        val isCurrentChapter = chapter == current
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    player.seekTo(chapter.startMs)
                                    chapterListOpen = false
                                }
                                .background(
                                    if (isCurrentChapter) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        Color.Transparent
                                    }
                                )
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(
                                formatTime(chapter.startMs),
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                chapter.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 10.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { chapterListOpen = false }) { Text(stringResource(R.string.close)) }
            }
        )
    }
}

/**
 * Scrollable transcript with follow-along: the cue containing the playhead
 * highlights and stays in view (unless the user is scrolling), and tapping
 * a timed cue seeks there. Untimed transcripts (plain text) just display.
 */
@Composable
private fun TranscriptDialog(
    url: String,
    type: String?,
    repository: com.nsavage.stepcast.data.PodcastRepository,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var cues by remember {
        mutableStateOf<List<com.nsavage.stepcast.data.TranscriptCue>?>(null)
    }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(url) {
        runCatching { repository.fetchTranscript(url, type) }
            .onSuccess { cues = it }
            .onFailure { error = it.message }
    }
    val listState = rememberLazyListState()
    val loaded = cues
    val timed = loaded?.firstOrNull()?.let { it.startMs >= 0 } == true
    val activeIndex = if (timed && loaded != null) {
        loaded.indexOfLast { it.startMs in 0..positionMs }
    } else {
        -1
    }
    androidx.compose.runtime.LaunchedEffect(activeIndex) {
        if (activeIndex >= 0 && !listState.isScrollInProgress) {
            runCatching {
                listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.transcript)) },
        text = {
            when {
                error != null -> Text(
                    stringResource(R.string.transcript_failed, error.orEmpty())
                )
                loaded == null -> Text(stringResource(R.string.transcript_loading))
                loaded.isEmpty() -> Text(
                    stringResource(R.string.transcript_failed, "")
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = 440.dp)
                ) {
                    itemsIndexed(loaded) { index, cue ->
                        val active = index == activeIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = cue.startMs >= 0) {
                                    onSeek(cue.startMs)
                                }
                                .padding(vertical = 4.dp, horizontal = 2.dp)
                        ) {
                            if (cue.startMs >= 0) {
                                Text(
                                    formatTime(cue.startMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (active) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(end = 8.dp, top = 2.dp)
                                )
                            }
                            Text(
                                cue.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (active) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}

@Composable
private fun SleepTimerDialog(
    onDismiss: () -> Unit,
    onPick: (minutes: Int, endOfEpisode: Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sleep_timer)) },
        text = {
            Column {
                TextButton(onClick = { onPick(0, false) }) { Text(stringResource(R.string.off)) }
                for (m in listOf(15, 30, 45, 60, 90)) {
                    TextButton(onClick = { onPick(m, false) }) {
                        Text(pluralStringResource(R.plurals.minutes_count, m, m))
                    }
                }
                TextButton(onClick = { onPick(0, true) }) { Text(stringResource(R.string.end_of_episode)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun sleepLabel(state: PlayerUiState): String {
    state.sleepEndsAtMs?.let {
        val minutesLeft = ((it - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(1)
        return stringResource(R.string.sleep_in_min, minutesLeft)
    }
    if (state.sleepAtEpisodeEnd) return stringResource(R.string.sleep_at_episode_end)
    return stringResource(R.string.sleep_timer)
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun trimSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()
