package com.stepcast.app.ui.screens

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlaylistAddCheck
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import com.stepcast.app.R
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.stepcast.app.data.AppSettings
import com.stepcast.app.data.Episode
import com.stepcast.app.data.PodcastRepository
import com.stepcast.app.download.DownloadWorker
import com.stepcast.app.ui.Formatters
import com.stepcast.app.ui.PlayerConnection
import com.stepcast.app.ui.progressBorder

/**
 * Live progress for the one row that is playing. Call it only for the
 * current row — that scopes the 500ms ticking recomposition to that row
 * instead of the whole list.
 */
@Composable
fun rememberLiveFraction(player: PlayerConnection): Float {
    val progress by player.progress.collectAsState()
    return progress.fraction
}

private fun swipeActionIcon(action: String) = when (action) {
    AppSettings.SWIPE_QUEUE -> Icons.AutoMirrored.Rounded.PlaylistAdd
    AppSettings.SWIPE_DOWNLOAD -> Icons.Rounded.Download
    AppSettings.SWIPE_DONE -> Icons.Rounded.DeleteSweep
    AppSettings.SWIPE_FAVORITE -> Icons.Rounded.Star
    else -> Icons.Rounded.Check
}

/**
 * Runs a configured swipe gesture against an episode, with an undo
 * snackbar where undo makes sense. Shared by every episode list.
 */
suspend fun performSwipeAction(
    action: String,
    episode: Episode,
    repository: PodcastRepository,
    context: Context,
    snackbar: SnackbarHostState
) {
    when (action) {
        AppSettings.SWIPE_QUEUE -> {
            repository.addToQueueBySwipe(episode.id)
            val result = snackbar.showSnackbar(
                message = context.getString(R.string.added_to_queue),
                actionLabel = context.getString(R.string.undo),
                withDismissAction = true
            )
            if (result == SnackbarResult.ActionPerformed) {
                repository.removeFromQueue(episode.id)
            }
        }
        AppSettings.SWIPE_DOWNLOAD -> {
            when {
                episode.audioUrl.startsWith("content:") ->
                    snackbar.showSnackbar(
                        context.getString(R.string.local_episode_already_on_device)
                    )
                episode.isDownloaded ->
                    snackbar.showSnackbar(context.getString(R.string.already_downloaded))
                episode.isDownloading ->
                    snackbar.showSnackbar(context.getString(R.string.already_downloading))
                else -> {
                    DownloadWorker.start(context, episode.id)
                    val result = snackbar.showSnackbar(
                        message = context.getString(R.string.downloading),
                        actionLabel = context.getString(R.string.cancel_caps),
                        withDismissAction = true
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        DownloadWorker.cancel(context, episode.id)
                    }
                }
            }
        }
        AppSettings.SWIPE_FAVORITE -> {
            val newFavorite = !episode.favorite
            repository.setFavorite(episode.id, newFavorite)
            val result = snackbar.showSnackbar(
                message = context.getString(
                    if (newFavorite) R.string.added_to_favorites else R.string.removed_from_favorites
                ),
                actionLabel = context.getString(R.string.undo),
                withDismissAction = true
            )
            if (result == SnackbarResult.ActionPerformed) {
                repository.setFavorite(episode.id, !newFavorite)
            }
        }
        AppSettings.SWIPE_DONE -> {
            repository.markPlayed(episode.id)
            repository.deleteDownload(episode.id)
            val result = snackbar.showSnackbar(
                // local files have no download to delete — say what happened
                message = context.getString(
                    if (episode.isLocalFile) R.string.mark_played
                    else R.string.done_played_download_deleted
                ),
                actionLabel = context.getString(R.string.undo),
                withDismissAction = true
            )
            if (result == SnackbarResult.ActionPerformed) {
                repository.setPlayed(episode.id, false)
            }
        }
        else -> { // SWIPE_PLAYED
            val newPlayed = !episode.played
            repository.setPlayed(episode.id, newPlayed)
            val result = snackbar.showSnackbar(
                message = context.getString(
                    if (newPlayed) R.string.marked_played else R.string.marked_unplayed
                ),
                actionLabel = context.getString(R.string.undo),
                withDismissAction = true
            )
            if (result == SnackbarResult.ActionPerformed) {
                repository.setPlayed(episode.id, !newPlayed)
            }
        }
    }
}

/**
 * One episode in a list. Swipe right = mark played/unplayed, swipe left =
 * add to queue (rows snap back; the swipe is an action, not a dismissal).
 */
@Composable
fun EpisodeRow(
    episode: Episode,
    fallbackArt: String?,
    isCurrent: Boolean,
    liveFraction: Float?,
    podcastTitle: String? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    inQueue: Boolean = false,
    onLongClick: () -> Unit = {},
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onTogglePlayed: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSwipeAction: (String) -> Unit,
    /** Inbox rows only: adds a "Remove from New" menu entry. */
    onRemoveFromInbox: (() -> Unit)? = null,
    /** Cross-show lists only: adds a "Go to <podcast>" menu entry. */
    onGoToPodcast: (() -> Unit)? = null
) {
    val view = androidx.compose.ui.platform.LocalView.current
    // the swipe state keeps the FIRST confirmValueChange it's given; route
    // through updated state so a recycled row never fires a stale callback
    val currentOnSwipeAction by androidx.compose.runtime.rememberUpdatedState(onSwipeAction)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    view.performHapticFeedback(
                        android.view.HapticFeedbackConstants.CONTEXT_CLICK
                    )
                    currentOnSwipeAction(AppSettings.swipeRightAction)
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    view.performHapticFeedback(
                        android.view.HapticFeedbackConstants.CONTEXT_CLICK
                    )
                    currentOnSwipeAction(AppSettings.swipeLeftAction)
                }
                else -> Unit
            }
            false // always snap back; swipes act, they don't dismiss
        }
    )

    // one card per episode — the now-playing one lights up, played
    // episodes visibly recede
    val containerColor = when {
        isCurrent -> MaterialTheme.colorScheme.primaryContainer
        episode.played -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        SwipeToDismissBox(
            state = dismissState,
            backgroundContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 20.dp)
                ) {
                    Icon(
                        swipeActionIcon(AppSettings.swipeRightAction),
                        contentDescription = com.stepcast.app.ui.theme.swipeActionLabel(AppSettings.swipeRightAction),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Box(Modifier.weight(1f))
                    Icon(
                        swipeActionIcon(AppSettings.swipeLeftAction),
                        contentDescription = com.stepcast.app.ui.theme.swipeActionLabel(AppSettings.swipeLeftAction),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        ) {
            EpisodeRowContent(
                episode = episode,
                fallbackArt = fallbackArt,
                isCurrent = isCurrent,
                liveFraction = liveFraction,
                podcastTitle = podcastTitle,
                containerColor = containerColor,
                selectionMode = selectionMode,
                inQueue = inQueue,
                onLongClick = onLongClick,
                onClick = onClick,
                onPlayNext = onPlayNext,
                onAddToQueue = onAddToQueue,
                onTogglePlayed = onTogglePlayed,
                onDownload = onDownload,
                onCancelDownload = onCancelDownload,
                onDeleteDownload = onDeleteDownload,
                onToggleFavorite = onToggleFavorite,
                onRemoveFromInbox = onRemoveFromInbox,
                onGoToPodcast = onGoToPodcast
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRowContent(
    episode: Episode,
    fallbackArt: String?,
    isCurrent: Boolean,
    liveFraction: Float?,
    podcastTitle: String?,
    containerColor: androidx.compose.ui.graphics.Color,
    selectionMode: Boolean,
    inQueue: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onTogglePlayed: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRemoveFromInbox: (() -> Unit)? = null,
    onGoToPodcast: (() -> Unit)? = null
) {
    var rowMenuOpen by remember { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    // for the per-episode "download over mobile data" override below
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(containerColor)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp)
    ) {
        // played episodes get grayed-out, faded artwork — unmistakable at a
        // glance next to the full-color unplayed rows
        val playedLook = episode.played && !isCurrent
        AsyncImage(
            model = remember(episode.imageUrl, fallbackArt) {
                ImageRequest.Builder(context)
                    .data(episode.imageUrl ?: fallbackArt)
                    // the show's own art is almost always already warm in
                    // Coil's memory cache (it's what the header/strip above
                    // this list just rendered) — showing it immediately
                    // while episode-specific art loads beats a blank
                    // square popping in a moment later
                    .apply {
                        if (episode.imageUrl != null && fallbackArt != null) {
                            placeholderMemoryCacheKey(fallbackArt)
                        }
                    }
                    .build()
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = if (playedLook) 0.45f else 1f,
            colorFilter = if (playedLook) {
                ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
            } else {
                null
            },
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .progressBorder(
                    fraction = liveFraction ?: episode.progressFraction,
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
                color = if (playedLook) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                if (episode.favorite) {
                    Icon(
                        Icons.Rounded.Star,
                        contentDescription = stringResource(R.string.favorite),
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (episode.played) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = stringResource(R.string.played),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
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
                    episode.downloadStatus == Episode.DOWNLOAD_FAILED -> {
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
                    listOf(
                        podcastTitle.orEmpty(),
                        Formatters.episodeMeta(episode.pubDateMs, episode.durationMs)
                    )
                        .filter { it.isNotEmpty() }
                        .joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (isCurrent) {
            Icon(
                Icons.Rounded.GraphicEq,
                contentDescription = stringResource(R.string.now_playing),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        if (!selectionMode && !isCurrent) {
            IconButton(onClick = onPlayNext) {
                Icon(
                    if (inQueue) {
                        Icons.Rounded.PlaylistAddCheck
                    } else {
                        Icons.AutoMirrored.Rounded.PlaylistPlay
                    },
                    contentDescription = stringResource(
                        if (inQueue) R.string.in_queue_tap_to_remove else R.string.play_next
                    ),
                    tint = if (inQueue) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
        }
        IconButton(onClick = { rowMenuOpen = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.episode_options))
            DropdownMenu(
                expanded = rowMenuOpen,
                onDismissRequest = { rowMenuOpen = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.episode_details)) },
                    onClick = { rowMenuOpen = false; detailsOpen = true }
                )
                if (onGoToPodcast != null) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    R.string.go_to_podcast, podcastTitle.orEmpty()
                                )
                            )
                        },
                        onClick = { rowMenuOpen = false; onGoToPodcast() }
                    )
                }
                if (onRemoveFromInbox != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remove_from_new)) },
                        onClick = { rowMenuOpen = false; onRemoveFromInbox() }
                    )
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (inQueue) {
                                    R.string.remove_from_up_next
                                } else {
                                    R.string.play_next
                                }
                            )
                        )
                    },
                    onClick = { rowMenuOpen = false; onPlayNext() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.add_to_queue)) },
                    onClick = { rowMenuOpen = false; onAddToQueue() }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (episode.played) {
                                    R.string.mark_unplayed
                                } else {
                                    R.string.mark_played
                                }
                            )
                        )
                    },
                    onClick = { rowMenuOpen = false; onTogglePlayed() }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (episode.favorite) {
                                    R.string.remove_from_favorites
                                } else {
                                    R.string.add_to_favorites
                                }
                            )
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (episode.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            contentDescription = null
                        )
                    },
                    onClick = { rowMenuOpen = false; onToggleFavorite() }
                )
                // virtual-feed episodes are already local files
                val isLocalFile = episode.audioUrl.startsWith("content:")
                when {
                    isLocalFile -> Unit
                    episode.isDownloading -> DropdownMenuItem(
                        text = { Text(stringResource(R.string.cancel_download)) },
                        onClick = { rowMenuOpen = false; onCancelDownload() }
                    )
                    episode.isDownloaded -> DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete_download)) },
                        onClick = { rowMenuOpen = false; onDeleteDownload() }
                    )
                    else -> {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (episode.downloadStatus ==
                                            Episode.DOWNLOAD_FAILED
                                        ) {
                                            R.string.retry_download
                                        } else {
                                            R.string.download
                                        }
                                    )
                                )
                            },
                            onClick = { rowMenuOpen = false; onDownload() }
                        )
                        // With Wi-Fi-only on, offer a one-shot override for THIS
                        // episode so a single download (or retry) can go over
                        // mobile data without touching the global setting.
                        if (AppSettings.wifiOnlyDownloads) {
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(R.string.download_now_mobile_data))
                                },
                                onClick = {
                                    rowMenuOpen = false
                                    DownloadWorker.start(
                                        context, episode.id, allowMetered = true
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (detailsOpen) {
        EpisodeDetailsDialog(
            episode = episode,
            onDismiss = { detailsOpen = false },
            onPlay = { detailsOpen = false; onClick() }
        )
    }
}

/** Show-notes dialog, shared by episode lists, the queue, and the player. */
@Composable
fun EpisodeDetailsDialog(
    episode: Episode,
    onDismiss: () -> Unit,
    onPlay: (() -> Unit)? = null
) {
    val noNotes = stringResource(R.string.no_show_notes)
    val notes = remember(episode.id) {
        // Many feeds (Mixcloud/SoundCloud-style tracklists especially) rely
        // on bare newlines for line breaks instead of <br>/<p> — but HTML
        // parsing correctly collapses raw \n into ordinary whitespace per
        // spec, so a plain fromHtml() call flattens the whole description
        // into one run-on paragraph. Promoting literal newlines to <br>
        // first means both real HTML breaks AND bare-newline breaks survive.
        val withExplicitBreaks = episode.description.replace(Regex("\r\n|\r|\n"), "<br>")
        HtmlCompat.fromHtml(withExplicitBreaks, HtmlCompat.FROM_HTML_MODE_COMPACT)
            .toString()
            // collapse the rare over-insertion (a source <br> immediately
            // followed by its own literal newline) into a normal paragraph gap
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .ifEmpty { noNotes }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(episode.title) },
        text = {
            Column {
                val meta = Formatters.episodeMeta(episode.pubDateMs, episode.durationMs)
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                // selectable so tracklists / links can be copied out
                androidx.compose.foundation.text.selection.SelectionContainer(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(notes, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            if (onPlay != null) {
                TextButton(onClick = onPlay) { Text(stringResource(R.string.play)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
        dismissButton = {
            if (onPlay != null) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        }
    )
}
