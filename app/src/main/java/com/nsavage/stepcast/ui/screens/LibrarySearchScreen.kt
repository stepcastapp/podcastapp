package com.nsavage.stepcast.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import com.nsavage.stepcast.R
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nsavage.stepcast.data.Episode
import com.nsavage.stepcast.data.Podcast
import com.nsavage.stepcast.data.PodcastRepository
import com.nsavage.stepcast.download.DownloadWorker
import com.nsavage.stepcast.ui.PlayerConnection
import com.nsavage.stepcast.ui.PlayerUiState
import com.nsavage.stepcast.ui.theme.EmptyState
import com.nsavage.stepcast.ui.theme.ScreenTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Search over what you're already subscribed to — shows by title, episodes
 * by title. With a few hundred feeds, scrolling to a known episode stopped
 * being viable long ago.
 */
@Composable
fun LibrarySearchScreen(
    repository: PodcastRepository,
    player: PlayerConnection,
    playerState: PlayerUiState,
    onPodcastClick: (Long) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var shows by remember { mutableStateOf<List<Podcast>>(emptyList()) }
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    val podcasts by repository.podcasts.collectAsState(initial = emptyList())
    val queued by repository.queue.collectAsState(initial = emptyList())
    val byId = podcasts.associateBy { it.id }
    val queuedIds = queued.map { it.id }.toSet()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // debounce keystrokes so the DB isn't queried per character
    LaunchedEffect(query) {
        delay(250)
        val (s, e) = repository.searchLibrary(query)
        shows = s
        episodes = e
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(
                stringResource(R.string.search_library),
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                placeholder = { Text(stringResource(R.string.show_or_episode_title)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focusRequester)
            )

            if (query.trim().length >= 2 && shows.isEmpty() && episodes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = Icons.Rounded.SearchOff,
                        title = stringResource(R.string.no_matches),
                        hint = stringResource(
                            R.string.library_search_no_match_hint, query.trim()
                        )
                    )
                }
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize()) {
                if (shows.isNotEmpty()) {
                    item(key = "shows-header") {
                        Text(
                            stringResource(R.string.shows),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp)
                        )
                    }
                    items(shows, key = { "p${it.id}" }) { podcast ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPodcastClick(podcast.id) }
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            AsyncImage(
                                model = podcast.imageUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                            Text(
                                podcast.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                }
                if (episodes.isNotEmpty()) {
                    item(key = "episodes-header") {
                        Text(
                            stringResource(R.string.episodes),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp)
                        )
                    }
                    items(episodes, key = { "e${it.id}" }) { episode ->
                        val podcast = byId[episode.podcastId]
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
                            }
                        )
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
