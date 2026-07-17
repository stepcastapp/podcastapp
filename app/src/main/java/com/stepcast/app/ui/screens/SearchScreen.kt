package com.stepcast.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import com.stepcast.app.R
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.stepcast.app.data.Episode
import com.stepcast.app.data.ItunesSearch
import com.stepcast.app.data.Podcast
import com.stepcast.app.data.PodcastRepository
import com.stepcast.app.data.SearchResult
import com.stepcast.app.download.DownloadWorker
import com.stepcast.app.ui.PlayerConnection
import com.stepcast.app.ui.PlayerUiState
import com.stepcast.app.ui.theme.EmptyState
import com.stepcast.app.ui.theme.ScreenTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The ONE place to find things: your library, Apple Podcasts, a pasted RSS
 * URL, or a local folder. One field — typing searches your library live
 * (debounced, local, cheap); submitting (IME/search icon) queries Apple;
 * an http(s) query goes straight to the feed preview. Until you search,
 * the Apple top charts fill the screen and the add-local-folder action
 * sits under the field.
 */
@Composable
fun SearchScreen(
    search: ItunesSearch,
    repository: PodcastRepository,
    player: PlayerConnection,
    playerState: PlayerUiState,
    onOpenPreview: (String) -> Unit,
    onPodcastClick: (Long) -> Unit,
    initialQuery: String = ""
) {
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val focusRequester = remember { FocusRequester() }

    // library matches track the field live; Apple only on explicit submit
    var libShows by remember { mutableStateOf<List<Podcast>>(emptyList()) }
    var libEpisodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    val podcasts by repository.podcasts.collectAsState(initial = emptyList())
    val queued by repository.queue.collectAsState(initial = emptyList())
    val byId = podcasts.associateBy { it.id }
    val queuedIds = queued.map { it.id }.toSet()

    // debounce keystrokes so the DB isn't queried per character
    LaunchedEffect(query) {
        delay(250)
        val (s, e) = repository.searchLibrary(query)
        libShows = s
        libEpisodes = e
        // clearing the field returns to browse mode, not stale results
        if (query.trim().length < 2) results = emptyList()
    }

    // the charts fill the screen until the user actually searches
    var trending by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var trendingBusy by remember { mutableStateOf(false) }
    val searchFailedMsg = stringResource(R.string.search_failed)
    LaunchedEffect(Unit) {
        if (trending.isEmpty()) {
            trendingBusy = true
            trending = runCatching { search.trending() }.getOrDefault(emptyList())
            trendingBusy = false
        }
    }

    // a search-first surface pops the keyboard — but not over a shared-in
    // URL, where the prefilled query is meant to be read, then submitted
    LaunchedEffect(Unit) {
        if (initialQuery.isEmpty()) focusRequester.requestFocus()
    }

    val localFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(
            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        scope.launch {
            try {
                repository.addLocalFolder(uri)
                Toast.makeText(
                    context,
                    context.getString(R.string.local_folder_added),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    context.getString(R.string.couldnt_read_folder, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun runSearch() {
        val term = query.trim()
        if (term.isEmpty()) return
        if (term.startsWith("http://") || term.startsWith("https://")) {
            // direct RSS URL: straight to its preview
            onOpenPreview(term)
            return
        }
        scope.launch {
            busy = true; error = null
            try {
                results = search.search(term)
            } catch (e: Exception) {
                error = e.message ?: searchFailedMsg
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            ScreenTitle(
                stringResource(R.string.search),
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                placeholder = { Text(stringResource(R.string.search_everything_placeholder)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                trailingIcon = {
                    IconButton(onClick = ::runSearch, enabled = !busy) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = stringResource(R.string.search)
                        )
                    }
                }
            )

            if (busy) {
                CircularProgressIndicator(Modifier.padding(24.dp))
            }
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }

            val q = query.trim()
            val searchingLib = q.length >= 2
            val hasLib = libShows.isNotEmpty() || libEpisodes.isNotEmpty()
            val showTrending =
                !searchingLib && results.isEmpty() && trending.isNotEmpty()

            // nothing anywhere for this query: one empty state, with the
            // Apple search still one tap away
            if (searchingLib && !hasLib && results.isEmpty() &&
                !busy && error == null
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyState(
                            icon = Icons.Rounded.SearchOff,
                            title = stringResource(R.string.no_matches),
                            hint = stringResource(
                                R.string.library_search_no_match_hint, q
                            )
                        )
                        Button(
                            onClick = ::runSearch,
                            modifier = Modifier.padding(top = 20.dp)
                        ) {
                            Text(stringResource(R.string.search_apple_podcasts))
                        }
                    }
                }
                return@Column
            }

            if (!searchingLib && results.isEmpty() && trending.isEmpty() &&
                trendingBusy && !busy
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            stringResource(R.string.loading_top_podcasts),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize()) {
                if (!searchingLib) {
                    item(key = "local-folder") { AddLocalFolderRow { localFolderLauncher.launch(null) } }
                }
                // charts unavailable and nothing searched: keep the browse
                // state friendly rather than a bare folder row
                if (!searchingLib && results.isEmpty() && trending.isEmpty() &&
                    !trendingBusy && !busy && error == null
                ) {
                    item(key = "discover-empty") {
                        Box(
                            Modifier.fillMaxWidth().padding(top = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            EmptyState(
                                icon = Icons.Rounded.Explore,
                                title = stringResource(R.string.find_your_next_show),
                                hint = stringResource(R.string.discover_empty_hint)
                            )
                        }
                    }
                }

                if (searchingLib && hasLib) {
                    item(key = "lib-header") {
                        SectionLabel(stringResource(R.string.in_your_library))
                    }
                    items(libShows, key = { "p${it.id}" }) { podcast ->
                        LibraryShowRow(podcast) { onPodcastClick(podcast.id) }
                    }
                    items(libEpisodes, key = { "e${it.id}" }) { episode ->
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

                if (showTrending && !busy) {
                    item(key = "trending-header") {
                        SectionLabel(stringResource(R.string.top_podcasts_right_now))
                    }
                }
                if (results.isNotEmpty() && hasLib) {
                    item(key = "apple-header") {
                        SectionLabel(stringResource(R.string.discover))
                    }
                }
                val appleItems = if (results.isNotEmpty()) results else {
                    if (showTrending) trending else emptyList()
                }
                items(appleItems, key = { it.feedUrl }) { result ->
                    AppleResultRow(result) { onOpenPreview(result.feedUrl) }
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp)
    )
}

@Composable
private fun AddLocalFolderRow(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(12.dp)
        ) {
            Icon(
                Icons.Rounded.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text(
                    stringResource(R.string.add_local_folder),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    stringResource(R.string.turn_a_folder_of_audio_files_into_a_virtua),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LibraryShowRow(podcast: Podcast, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp)
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

@Composable
private fun AppleResultRow(result: SearchResult, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                // tap = look, not commit: the preview screen owns Subscribe
                .clickable(onClick = onClick)
                .padding(8.dp)
        ) {
            AsyncImage(
                model = result.imageUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text(
                    result.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    result.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
