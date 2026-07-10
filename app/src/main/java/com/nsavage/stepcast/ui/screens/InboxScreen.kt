package com.nsavage.stepcast.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nsavage.stepcast.R
import com.nsavage.stepcast.data.PodcastRepository
import com.nsavage.stepcast.download.DownloadWorker
import com.nsavage.stepcast.ui.PlayerConnection
import com.nsavage.stepcast.ui.PlayerUiState
import com.nsavage.stepcast.ui.theme.EmptyState
import com.nsavage.stepcast.ui.theme.ScreenTitle
import kotlinx.coroutines.launch

/**
 * The New-episodes inbox: everything published in the last two weeks that
 * hasn't been played or cleared, across every subscription — the daily
 * "what's new?" triage surface. Queue it, play it, or clear it; playing or
 * marking played removes it naturally, and Clear all (undoable) empties it.
 */
@Composable
fun InboxScreen(
    repository: PodcastRepository,
    player: PlayerConnection,
    playerState: PlayerUiState
) {
    val episodes by repository.inbox().collectAsState(initial = emptyList())
    val podcasts by repository.podcasts.collectAsState(initial = emptyList())
    val queued by repository.queue.collectAsState(initial = emptyList())
    val byId = podcasts.associateBy { it.id }
    val queuedIds = queued.mapTo(HashSet()) { it.id }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    ScreenTitle(stringResource(R.string.inbox_title))
                    Text(
                        pluralStringResource(
                            R.plurals.episodes_count, episodes.size, episodes.size
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (episodes.isNotEmpty()) {
                    TextButton(onClick = {
                        val ids = episodes.map { it.id }
                        scope.launch {
                            repository.dismissFromInbox(ids)
                            val result = snackbar.showSnackbar(
                                message = context.resources.getQuantityString(
                                    R.plurals.n_cleared_from_new, ids.size, ids.size
                                ),
                                actionLabel = context.getString(R.string.undo),
                                withDismissAction = true
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                repository.restoreToInbox(ids)
                            }
                        }
                    }) {
                        Text(stringResource(R.string.clear_all))
                    }
                }
            }

            if (episodes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = Icons.Rounded.Inbox,
                        title = stringResource(R.string.inbox_empty_title),
                        hint = stringResource(R.string.inbox_empty_hint)
                    )
                }
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(episodes, key = { it.id }) { episode ->
                    val podcast = byId[episode.podcastId]
                    val removedMsg = stringResource(R.string.removed_from_new)
                    val undoLabel = stringResource(R.string.undo)
                    EpisodeRow(
                        episode = episode,
                        fallbackArt = podcast?.imageUrl,
                        isCurrent = playerState.episodeId == episode.id,
                        liveFraction = if (playerState.episodeId == episode.id) {
                            rememberLiveFraction(player)
                        } else {
                            null
                        },
                        podcastTitle = podcast?.title,
                        inQueue = episode.id in queuedIds,
                        onClick = { player.play(episode, podcast) },
                        onPlayNext = {
                            scope.launch {
                                if (episode.id in queuedIds) {
                                    repository.removeFromQueue(episode.id)
                                } else {
                                    repository.addToQueueNext(episode.id)
                                }
                            }
                        },
                        onAddToQueue = {
                            scope.launch { repository.addToQueueLast(episode.id) }
                        },
                        onTogglePlayed = {
                            scope.launch {
                                repository.setPlayed(episode.id, !episode.played)
                            }
                        },
                        onDownload = { DownloadWorker.start(context, episode.id) },
                        onCancelDownload = {
                            DownloadWorker.cancel(context, episode.id)
                        },
                        onDeleteDownload = {
                            scope.launch { repository.deleteDownload(episode.id) }
                        },
                        onSwipeAction = { action ->
                            scope.launch {
                                performSwipeAction(
                                    action, episode, repository, context, snackbar
                                )
                            }
                        },
                        onRemoveFromInbox = {
                            scope.launch {
                                repository.dismissFromInbox(listOf(episode.id))
                                val result = snackbar.showSnackbar(
                                    message = removedMsg,
                                    actionLabel = undoLabel,
                                    withDismissAction = true
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    repository.restoreToInbox(listOf(episode.id))
                                }
                            }
                        }
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
