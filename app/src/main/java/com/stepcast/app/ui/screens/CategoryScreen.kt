package com.stepcast.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.stepcast.app.R
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.stepcast.app.data.PodcastRepository
import com.stepcast.app.download.DownloadWorker
import com.stepcast.app.ui.PlayerConnection
import com.stepcast.app.ui.PlayerUiState
import com.stepcast.app.ui.theme.ScreenTitle
import kotlinx.coroutines.launch
import androidx.compose.ui.res.pluralStringResource

/**
 * A category as a virtual feed: its podcasts in a strip up top, and the
 * merged newest-first episode list of all of them below, with the full
 * set of episode actions.
 */
@Composable
fun CategoryScreen(
    category: String,
    repository: PodcastRepository,
    player: PlayerConnection,
    playerState: PlayerUiState,
    onPodcastClick: (Long) -> Unit,
    onRenamed: (String) -> Unit,
    onDeleted: () -> Unit
) {
    val episodes by repository.episodesForCategory(category)
        .collectAsState(initial = emptyList())
    val podcasts by repository.podcasts.collectAsState(initial = emptyList())
    val queueIds by repository.queue.collectAsState(initial = emptyList())
    val metas by repository.categoryMetas.collectAsState(initial = emptyList())
    val memberships by repository.podcastCategories.collectAsState(initial = emptyList())
    val queuedIds = queueIds.mapTo(HashSet()) { it.id }
    val memberIds = memberships
        .filter { it.category == category }
        .mapTo(HashSet()) { it.podcastId }
    val members = podcasts.filter { it.id in memberIds }
    val byId = podcasts.associateBy { it.id }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var editOpen by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var downloadedOnly by remember { mutableStateOf(false) }
    val shownEpisodes = if (downloadedOnly) episodes.filter { it.isDownloaded } else episodes

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    ScreenTitle(category)
                    Text(
                        stringResource(
                            R.string.category_podcast_count,
                            pluralStringResource(
                                R.plurals.podcasts_count, members.size, members.size
                            )
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = {
                        if (!refreshing) {
                            refreshing = true
                            scope.launch {
                                val added = repository.refreshCategory(category)
                                refreshing = false
                                snackbar.showSnackbar(
                                    if (added > 0) {
                                        context.resources.getQuantityString(
                                            R.plurals.new_episodes_count, added, added
                                        )
                                    } else {
                                        context.getString(R.string.no_new_episodes)
                                    }
                                )
                            }
                        }
                    },
                    enabled = !refreshing
                ) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.refresh_item_cd, category)
                    )
                }
                IconButton(onClick = { editOpen = true }) {
                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.edit_category))
                }
            }
            LazyRow(Modifier.fillMaxWidth()) {
                items(members, key = { it.id }) { podcast ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(84.dp)
                            .clickable { onPodcastClick(podcast.id) }
                            .padding(horizontal = 6.dp)
                    ) {
                        com.stepcast.app.ui.theme.ArtworkOrFolder(
                            imageUrl = podcast.imageUrl,
                            isLocalFolder = podcast.localFolderUri != null,
                            contentDescription = podcast.title,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        Text(
                            podcast.title,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            Row(modifier = Modifier.padding(start = 16.dp, top = 4.dp)) {
                FilterChip(
                    selected = !downloadedOnly,
                    onClick = { downloadedOnly = false },
                    label = { Text(stringResource(R.string.all)) }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = downloadedOnly,
                    onClick = { downloadedOnly = true },
                    label = { Text(stringResource(R.string.downloaded)) }
                )
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(shownEpisodes, key = { it.id }) { episode ->
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
                        onAddToQueue = { scope.launch { repository.addToQueueLast(episode.id) } },
                        onTogglePlayed = {
                            scope.launch { repository.setPlayed(episode.id, !episode.played) }
                        },
                        onDownload = { DownloadWorker.start(context, episode.id) },
                        onCancelDownload = { DownloadWorker.cancel(context, episode.id) },
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

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    if (editOpen) {
        var name by remember { mutableStateOf(category) }
        val meta = metas.firstOrNull { it.name == category }
        val currentHours = meta?.refreshHours ?: 0
        var hoursText by remember {
            mutableStateOf(if (currentHours > 0) currentHours.toString() else "")
        }
        val currentAnchor = meta?.anchorMinutes ?: -1
        var anchorText by remember {
            mutableStateOf(
                if (currentAnchor >= 0) {
                    com.stepcast.app.sync.RefreshSchedule.formatAnchor(currentAnchor)
                } else {
                    ""
                }
            )
        }
        var bulkKeepText by remember { mutableStateOf("") }
        var bulkAgeText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editOpen = false },
            title = { Text(stringResource(R.string.edit_category)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        label = { Text(stringResource(R.string.category_name)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            capitalization =
                                androidx.compose.ui.text.input.KeyboardCapitalization.Sentences
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.padding(top = 8.dp)) {
                        OutlinedTextField(
                            value = hoursText,
                            onValueChange = { hoursText = it.filter(Char::isDigit).take(3) },
                            label = { Text(stringResource(R.string.refresh_every_n_hours_empty_default)) },
                            singleLine = true,
                            modifier = Modifier.weight(1.2f)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = anchorText,
                            onValueChange = { text ->
                                anchorText = text.filter { it.isDigit() || it == ':' }.take(5)
                            },
                            label = { Text(stringResource(R.string.from_h_mm)) },
                            singleLine = true,
                            isError = anchorText.isNotBlank() &&
                                com.stepcast.app.sync.RefreshSchedule
                                    .parseAnchor(anchorText) == null,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        stringResource(R.string.anchor_time_explainer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        stringResource(R.string.bulk_apply_explainer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    Row(Modifier.padding(top = 4.dp)) {
                        OutlinedTextField(
                            value = bulkKeepText,
                            onValueChange = {
                                bulkKeepText = it.filter(Char::isDigit).take(2)
                            },
                            label = { Text(stringResource(R.string.auto_keep)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = bulkAgeText,
                            onValueChange = {
                                bulkAgeText = it.filter(Char::isDigit).take(4)
                            },
                            label = { Text(stringResource(R.string.max_age_days)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        stringResource(R.string.mark_played_when_older_than),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    Row {
                        for ((label, days) in listOf(
                            "1w" to 7, "1m" to 30, "3m" to 90, "1y" to 365
                        )) {
                            TextButton(onClick = {
                                editOpen = false
                                scope.launch {
                                    repository.markPlayedOlderThanInCategory(category, days)
                                    snackbar.showSnackbar(
                                        context.getString(
                                            R.string.episodes_older_marked_played, label
                                        )
                                    )
                                }
                            }) { Text(label) }
                        }
                    }
                    Text(
                        stringResource(R.string.removing_category_explainer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editOpen = false
                    val newName = name.trim()
                    scope.launch {
                        repository.setCategoryRefreshHours(
                            category,
                            hoursText.toIntOrNull() ?: 0,
                            com.stepcast.app.sync.RefreshSchedule
                                .parseAnchor(anchorText) ?: -1
                        )
                        // bulk retention only when the user typed something
                        val bulkKeep = bulkKeepText.toIntOrNull()
                        val bulkAge = bulkAgeText.toIntOrNull()
                        if (bulkKeep != null || bulkAge != null) {
                            for (member in members) {
                                repository.setRetention(
                                    member.id,
                                    bulkKeep ?: member.keepDownloads,
                                    bulkAge ?: member.maxAgeDays
                                )
                            }
                        }
                        if (newName.isNotEmpty() && newName != category) {
                            repository.renameCategory(category, newName)
                            onRenamed(newName)
                        }
                    }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        editOpen = false
                        scope.launch {
                            repository.deleteCategory(category)
                            onDeleted()
                        }
                    }) { Text(stringResource(R.string.remove_category)) }
                    TextButton(onClick = { editOpen = false }) { Text(stringResource(R.string.cancel)) }
                }
            }
        )
    }
}
