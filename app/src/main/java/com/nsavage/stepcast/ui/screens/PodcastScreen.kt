package com.nsavage.stepcast.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import com.nsavage.stepcast.R
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nsavage.stepcast.data.PodcastRepository
import com.nsavage.stepcast.download.DownloadWorker
import com.nsavage.stepcast.ui.PlayerConnection
import com.nsavage.stepcast.ui.PlayerUiState
import kotlinx.coroutines.launch
import androidx.compose.ui.res.pluralStringResource

@Composable
fun PodcastScreen(
    podcastId: Long,
    repository: PodcastRepository,
    search: com.nsavage.stepcast.data.ItunesSearch,
    player: PlayerConnection,
    playerState: PlayerUiState,
    onUnsubscribed: () -> Unit
) {
    val podcast by repository.observePodcast(podcastId).collectAsState(initial = null)
    // paged: a 2000-episode feed must not inflate 2000 rows at once
    var episodeLimit by remember { mutableStateOf(100) }
    val oldestFirst = podcast?.sortOldestFirst == true
    val episodes by remember(podcastId, episodeLimit, oldestFirst) {
        repository.episodesForPaged(podcastId, oldestFirst, episodeLimit)
    }.collectAsState(initial = emptyList())
    val queueIds by repository.queue.collectAsState(initial = emptyList())
    val queuedIds = queueIds.mapTo(HashSet()) { it.id }
    // header counts come straight from the DB — episodes above is paged
    val counts by remember(podcastId) { repository.episodeCounts(podcastId) }
        .collectAsState(initial = null)
    var downloadedOnly by remember { mutableStateOf(false) }
    val shownEpisodes = if (downloadedOnly) episodes.filter { it.isDownloaded } else episodes
    val allPodcasts by repository.podcasts.collectAsState(initial = emptyList())
    val categoryMetas by repository.categoryMetas.collectAsState(initial = emptyList())
    val categories = categoryMetas.map { it.name }
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
    val allMemberships by repository.podcastCategories
        .collectAsState(initial = emptyList())
    val myCategories = allMemberships
        .filter { it.podcastId == podcastId }
        .map { it.category }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var fixFeedOpen by remember { mutableStateOf(false) }
    var olderThanOpen by remember { mutableStateOf(false) }
    var settingsDialogOpen by remember { mutableStateOf(false) }

    // multi-select for bulk download / queue
    val selectedEpisodes = remember { mutableStateListOf<Long>() }
    fun toggleEpisode(id: Long) {
        if (!selectedEpisodes.remove(id)) selectedEpisodes.add(id)
    }
    BackHandler(enabled = selectedEpisodes.isNotEmpty()) { selectedEpisodes.clear() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // hero header — artwork banner in a large tinted card
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    com.nsavage.stepcast.ui.theme.ArtworkOrFolder(
                        imageUrl = podcast?.imageUrl,
                        isLocalFolder = podcast?.localFolderUri != null,
                        contentDescription = null,
                        modifier = Modifier
                            .size(88.dp)
                            .clip(RoundedCornerShape(20.dp))
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 14.dp)
                    ) {
                        Text(
                            podcast?.title.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            listOfNotNull(
                                podcast?.author?.takeIf { it.isNotEmpty() },
                                myCategories.joinToString(", ")
                                    .takeIf { it.isNotEmpty() }
                            ).joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val totalCount = counts?.total ?: episodes.size
                        Text(
                            stringResource(
                                R.string.episodes_with_unplayed,
                                pluralStringResource(
                                    R.plurals.episodes_count, totalCount, totalCount
                                ),
                                counts?.unplayed ?: episodes.count { !it.played }
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        val fails = podcast?.consecutiveFailures ?: 0
                        if (fails >= 3) {
                            Text(
                                stringResource(R.string.refresh_failing_warning, fails),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            TextButton(
                                onClick = { fixFeedOpen = true },
                                contentPadding = androidx.compose.foundation.layout
                                    .PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            ) {
                                Text(stringResource(R.string.find_replacement_feed))
                            }
                        }
                        podcast?.takeIf { it.localFolderUri == null }?.let { p ->
                            Text(
                                if (p.lastRefreshed > 0) {
                                    stringResource(
                                        R.string.last_refreshed,
                                        android.text.format.DateUtils
                                            .getRelativeTimeSpanString(p.lastRefreshed)
                                            .toString()
                                    )
                                } else {
                                    stringResource(R.string.never_refreshed)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    IconButton(onClick = {
                        scope.launch {
                            // refresh() also runs the auto-download rules
                            val added = runCatching { repository.refresh(podcastId) }
                                .getOrDefault(0)
                            snackbar.showSnackbar(
                                if (added > 0) {
                                    context.resources.getQuantityString(
                                        R.plurals.new_episodes_rules_applied,
                                        added, added
                                    )
                                } else {
                                    context.getString(
                                        R.string.no_new_episodes_rules_applied
                                    )
                                }
                            )
                        }
                    }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more))
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.podcast_settings)) },
                            onClick = { menuOpen = false; settingsDialogOpen = true }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.mark_older_than)) },
                            onClick = { menuOpen = false; olderThanOpen = true }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.mark_all_played)) },
                            onClick = {
                                menuOpen = false
                                scope.launch { repository.markAllPlayed(podcastId) }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.unsubscribe)) },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    repository.unsubscribe(podcastId)
                                    onUnsubscribed()
                                }
                            }
                        )
                        }
                    }
                }
            }

            if (selectedEpisodes.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                ) {
                    IconButton(onClick = { selectedEpisodes.clear() }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel_selection))
                    }
                    Text(
                        "${selectedEpisodes.size} selected",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        val targets = episodes.filter {
                            it.id in selectedEpisodes &&
                                !it.isDownloaded && !it.isDownloading &&
                                !it.audioUrl.startsWith("content:")
                        }
                        targets.forEach { DownloadWorker.start(context, it.id) }
                        selectedEpisodes.clear()
                    }) {
                        Icon(Icons.Rounded.Download, contentDescription = stringResource(R.string.download_selected))
                    }
                    IconButton(onClick = {
                        val ids = selectedEpisodes.toList()
                        selectedEpisodes.clear()
                        scope.launch { ids.forEach { repository.addToQueueLast(it) } }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.PlaylistAdd,
                            contentDescription = stringResource(R.string.queue_selected)
                        )
                    }
                }
            }

            Row(modifier = Modifier.padding(start = 12.dp)) {
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
                if (episodes.size >= episodeLimit) {
                    // there may be more beyond the page
                }
                items(shownEpisodes, key = { it.id }) { episode ->
                    EpisodeRow(
                        episode = episode,
                        fallbackArt = podcast?.imageUrl,
                        isCurrent = playerState.episodeId == episode.id,
                        liveFraction = if (playerState.episodeId == episode.id) {
                            rememberLiveFraction(player)
                        } else {
                            null
                        },
                        selectionMode = selectedEpisodes.isNotEmpty(),
                        selected = episode.id in selectedEpisodes,
                        inQueue = episode.id in queuedIds,
                        onLongClick = { toggleEpisode(episode.id) },
                        onClick = {
                            if (selectedEpisodes.isNotEmpty()) {
                                toggleEpisode(episode.id)
                            } else {
                                player.play(episode, podcast)
                            }
                        },
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
                val moreExist = episodes.size >= episodeLimit &&
                    (counts?.total ?: Int.MAX_VALUE) > episodes.size
                if (moreExist) {
                    item(key = "load-more") {
                        TextButton(
                            onClick = { episodeLimit += 200 },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            val total = counts?.total
                            Text(
                                if (total != null && total > episodes.size) {
                                    "Show more episodes (${episodes.size} of $total)"
                                } else {
                                    "Show more episodes (${episodes.size} loaded)"
                                }
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    if (settingsDialogOpen && podcast != null) {
        PodcastSettingsDialog(
            introSec = podcast!!.introSkipSec,
            outroSec = podcast!!.outroSkipSec,
            adJumpSec = podcast!!.adJumpSec,
            speed = podcast!!.playbackSpeed,
            currentCategories = myCategories,
            categories = categories,
            keepDownloads = podcast!!.keepDownloads,
            maxAgeDays = podcast!!.maxAgeDays,
            episodeCap = podcast!!.episodeCap,
            sortOldestFirst = podcast!!.sortOldestFirst,
            autoQueue = podcast!!.autoQueue,
            onDismiss = { settingsDialogOpen = false },
            onSave = { result ->
                settingsDialogOpen = false
                scope.launch {
                    repository.setSkips(podcastId, result.introSec, result.outroSec)
                    repository.setAdJump(podcastId, result.adJumpSec)
                    // live notification button follows an ad-jump edit
                    context.sendBroadcast(
                        android.content.Intent(
                            context,
                            com.nsavage.stepcast.playback.CommandReceiver::class.java
                        ).setAction(
                            com.nsavage.stepcast.playback.CommandReceiver
                                .ACTION_REFRESH_NOTIF_BUTTONS
                        )
                    )
                    repository.setPlaybackSpeed(podcastId, result.speed)
                    repository.setCategories(podcastId, result.categories)
                    repository.setRetention(podcastId, result.keep, result.maxAge)
                    repository.setListPrefs(
                        podcastId,
                        result.episodeCap,
                        result.sortOldestFirst,
                        result.autoQueue
                    )
                }
            }
        )
    }

    if (fixFeedOpen) {
        podcast?.let { p ->
            FixFeedDialog(
                podcast = p,
                search = search,
                repository = repository,
                onReplaced = {
                    fixFeedOpen = false
                    scope.launch {
                        snackbar.showSnackbar(
                            context.getString(R.string.feed_replaced)
                        )
                    }
                },
                onDismiss = { fixFeedOpen = false }
            )
        }
    }

    if (olderThanOpen) {
        AlertDialog(
            onDismissRequest = { olderThanOpen = false },
            title = { Text(stringResource(R.string.mark_played_when_older_than_2)) },
            text = {
                Column {
                    for ((label, days) in listOf(
                        "1 week" to 7, "1 month" to 30,
                        "3 months" to 90, "1 year" to 365
                    )) {
                        TextButton(onClick = {
                            olderThanOpen = false
                            scope.launch {
                                repository.markPlayedOlderThan(podcastId, days)
                                snackbar.showSnackbar("Episodes older than $label marked played")
                            }
                        }) { Text(label) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { olderThanOpen = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

/** Everything the settings dialog can change, in one bundle. */
data class PodcastSettingsResult(
    val introSec: Int,
    val outroSec: Int,
    val adJumpSec: Int,
    val speed: Float,
    val categories: List<String>,
    val keep: Int,
    val maxAge: Int,
    val episodeCap: Int,
    val sortOldestFirst: Boolean,
    val autoQueue: Boolean
)

@Composable
private fun PodcastSettingsDialog(
    introSec: Int,
    outroSec: Int,
    adJumpSec: Int,
    speed: Float,
    currentCategories: List<String>,
    categories: List<String>,
    keepDownloads: Int,
    maxAgeDays: Int,
    episodeCap: Int,
    sortOldestFirst: Boolean,
    autoQueue: Boolean,
    onDismiss: () -> Unit,
    onSave: (PodcastSettingsResult) -> Unit
) {
    var intro by remember { mutableStateOf(if (introSec > 0) introSec.toString() else "") }
    var outro by remember { mutableStateOf(if (outroSec > 0) outroSec.toString() else "") }
    var adJump by remember {
        mutableStateOf(if (adJumpSec > 0) adJumpSec.toString() else "")
    }
    var speedText by remember { mutableStateOf(if (speed > 0f) speed.toString() else "") }
    // multi-select: a podcast can live in several categories at once
    val selectedCategories = remember {
        androidx.compose.runtime.mutableStateListOf<String>().apply {
            addAll(currentCategories)
        }
    }
    var newCategory by remember { mutableStateOf("") }
    var keepText by remember { mutableStateOf(keepDownloads.toString()) }
    var ageText by remember { mutableStateOf(if (maxAgeDays > 0) maxAgeDays.toString() else "") }
    var capText by remember { mutableStateOf(if (episodeCap > 0) episodeCap.toString() else "") }
    var oldestFirst by remember { mutableStateOf(sortOldestFirst) }
    var queueNew by remember { mutableStateOf(autoQueue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.podcast_settings_2)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.skips_dialog_explainer),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.size(12.dp))
                Row {
                    OutlinedTextField(
                        value = intro,
                        onValueChange = { intro = it.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.intro_s)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = outro,
                        onValueChange = { outro = it.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.outro_s)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = adJump,
                    onValueChange = { adJump = it.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(R.string.ad_jump_seconds)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.ad_jump_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = speedText,
                    onValueChange = { text ->
                        speedText = text.filter { it.isDigit() || it == '.' }.take(4)
                    },
                    label = { Text(stringResource(R.string.speed_empty_default)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.category), style = MaterialTheme.typography.labelMedium)
                LazyRow {
                    item {
                        FilterChip(
                            selected = selectedCategories.isEmpty(),
                            onClick = { selectedCategories.clear(); newCategory = "" },
                            label = { Text(stringResource(R.string.none)) }
                        )
                    }
                    items(categories) { existing ->
                        Spacer(Modifier.width(6.dp))
                        FilterChip(
                            selected = existing in selectedCategories,
                            onClick = {
                                if (!selectedCategories.remove(existing)) {
                                    selectedCategories.add(existing)
                                }
                            },
                            label = { Text(existing) }
                        )
                    }
                }
                OutlinedTextField(
                    value = newCategory,
                    onValueChange = { text -> newCategory = text.take(40) },
                    label = { Text(stringResource(R.string.new_category)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        capitalization =
                            androidx.compose.ui.text.input.KeyboardCapitalization.Sentences
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.downloads), style = MaterialTheme.typography.labelMedium)
                Row {
                    OutlinedTextField(
                        value = keepText,
                        onValueChange = { keepText = it.filter(Char::isDigit).take(2) },
                        label = { Text(stringResource(R.string.auto_keep_0_off)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = ageText,
                        onValueChange = { ageText = it.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.max_age_days)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.episode_list), style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = capText,
                    onValueChange = { capText = it.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.episodes_kept_in_list_0_all)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.oldest_first_serials),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.Switch(
                        checked = oldestFirst,
                        onCheckedChange = { oldestFirst = it }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.auto_add_new_episodes_to_up_next),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.Switch(
                        checked = queueNew,
                        onCheckedChange = { queueNew = it }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    PodcastSettingsResult(
                        introSec = intro.toIntOrNull() ?: 0,
                        outroSec = outro.toIntOrNull() ?: 0,
                        adJumpSec = adJump.toIntOrNull() ?: 0,
                        speed = speedText.toFloatOrNull() ?: 0f,
                        categories = (
                            selectedCategories +
                                listOfNotNull(
                                    newCategory.trim().takeIf { it.isNotEmpty() }
                                )
                            ).distinct(),
                        keep = keepText.toIntOrNull() ?: 0,
                        maxAge = ageText.toIntOrNull() ?: 0,
                        episodeCap = capText.toIntOrNull() ?: 0,
                        sortOldestFirst = oldestFirst,
                        autoQueue = queueNew
                    )
                )
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
