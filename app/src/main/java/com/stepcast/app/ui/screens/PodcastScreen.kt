package com.stepcast.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Star
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import com.stepcast.app.R
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.stepcast.app.data.Podcast
import com.stepcast.app.data.PodcastRepository
import com.stepcast.app.download.DownloadWorker
import com.stepcast.app.ui.PlayerConnection
import com.stepcast.app.ui.PlayerUiState
import kotlinx.coroutines.launch
import androidx.compose.ui.res.pluralStringResource

@Composable
fun PodcastScreen(
    podcastId: Long,
    repository: PodcastRepository,
    search: com.stepcast.app.data.ItunesSearch,
    player: PlayerConnection,
    playerState: PlayerUiState,
    onUnsubscribed: () -> Unit
) {
    val podcast by repository.observePodcast(podcastId).collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    // paged: a 2000-episode feed must not inflate 2000 rows at once
    var episodeLimit by rememberSaveable { mutableStateOf(100) }
    val oldestFirst = podcast?.sortOldestFirst == true
    val sortMode = podcast?.episodeSortMode ?: Podcast.SORT_DATE
    val episodes by remember(podcastId, episodeLimit, sortMode, oldestFirst) {
        repository.episodesForPaged(podcastId, sortMode, oldestFirst, episodeLimit)
    }.collectAsState(initial = emptyList())
    val queueIds by repository.queue.collectAsState(initial = emptyList())
    val queuedIds = queueIds.mapTo(HashSet()) { it.id }
    // header counts come straight from the DB — episodes above is paged
    val counts by remember(podcastId) { repository.episodeCounts(podcastId) }
        .collectAsState(initial = null)
    // seeded from the show's remembered choice on first load only — a
    // stray recomposition of the podcast row (e.g. a refresh finishing)
    // must not silently reset the chip the user picked this visit
    var filterMode by remember(podcastId) { mutableStateOf(Podcast.FILTER_ALL) }
    LaunchedEffect(podcast?.id) {
        podcast?.let { filterMode = it.lastEpisodeFilter }
    }
    fun pickFilter(mode: Int) {
        filterMode = mode
        scope.launch { repository.setLastEpisodeFilter(podcastId, mode) }
    }
    // isAvailableOffline, not isDownloaded: a local folder's episodes are
    // already on-device — the chip used to filter ALL of them out
    val shownEpisodes = when (filterMode) {
        Podcast.FILTER_DOWNLOADED -> episodes.filter { it.isAvailableOffline }
        Podcast.FILTER_UNPLAYED -> episodes.filter { !it.played }
        Podcast.FILTER_FAVORITE -> episodes.filter { it.favorite }
        else -> episodes
    }
    val allPodcasts by repository.podcasts.collectAsState(initial = emptyList())
    val categoryMetas by repository.categoryMetas.collectAsState(initial = emptyList())
    val categories = categoryMetas.map { it.name }
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
    val allMemberships by repository.podcastCategories
        .collectAsState(initial = emptyList())
    val myCategories = allMemberships
        .filter { it.podcastId == podcastId }
        .map { it.category }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var fixFeedOpen by remember { mutableStateOf(false) }
    var olderThanOpen by remember { mutableStateOf(false) }
    var settingsDialogOpen by remember { mutableStateOf(false) }
    var confirmMarkAllPlayed by remember { mutableStateOf(false) }
    var confirmUnsubscribe by remember { mutableStateOf(false) }

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
                    com.stepcast.app.ui.theme.ArtworkOrFolder(
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
                        // never on local folders: no feed to fail, and the
                        // "replacement feed" repair would corrupt the row
                        if (fails >= 3 && podcast?.localFolderUri == null) {
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
                            val isFolder = podcast?.localFolderUri != null
                            snackbar.showSnackbar(
                                when {
                                    added > 0 && isFolder ->
                                        context.resources.getQuantityString(
                                            R.plurals.new_episodes_count, added, added
                                        )
                                    added > 0 ->
                                        context.resources.getQuantityString(
                                            R.plurals.new_episodes_rules_applied,
                                            added, added
                                        )
                                    // folders don't run download rules —
                                    // don't claim they did
                                    isFolder ->
                                        context.getString(R.string.no_new_episodes)
                                    else ->
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
                                confirmMarkAllPlayed = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.unsubscribe)) },
                            onClick = {
                                menuOpen = false
                                confirmUnsubscribe = true
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
                        stringResource(R.string.n_selected, selectedEpisodes.size),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    // a local folder has nothing to download — the icon
                    // was a permanent silent no-op there
                    if (podcast?.localFolderUri == null) {
                        IconButton(onClick = {
                            val targets = episodes.filter {
                                it.id in selectedEpisodes &&
                                    !it.isDownloaded && !it.isDownloading &&
                                    !it.isLocalFile
                            }
                            targets.forEach { DownloadWorker.start(context, it.id) }
                            selectedEpisodes.clear()
                        }) {
                            Icon(Icons.Rounded.Download, contentDescription = stringResource(R.string.download_selected))
                        }
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

            Row(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .horizontalScroll(rememberScrollState())
            ) {
                FilterChip(
                    selected = filterMode == Podcast.FILTER_ALL,
                    onClick = { pickFilter(Podcast.FILTER_ALL) },
                    label = { Text(stringResource(R.string.all)) }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = filterMode == Podcast.FILTER_DOWNLOADED,
                    onClick = { pickFilter(Podcast.FILTER_DOWNLOADED) },
                    label = { Text(stringResource(R.string.downloaded)) }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = filterMode == Podcast.FILTER_UNPLAYED,
                    onClick = { pickFilter(Podcast.FILTER_UNPLAYED) },
                    label = { Text(stringResource(R.string.unplayed)) }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = filterMode == Podcast.FILTER_FAVORITE,
                    onClick = { pickFilter(Podcast.FILTER_FAVORITE) },
                    label = { Text(stringResource(R.string.favorites)) }
                )
            }

            LazyColumn(Modifier.fillMaxSize()) {
                if (shownEpisodes.isEmpty()) {
                    item(key = "empty") {
                        Box(
                            Modifier.fillMaxWidth().padding(top = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            com.stepcast.app.ui.theme.EmptyState(
                                icon = when (filterMode) {
                                    Podcast.FILTER_DOWNLOADED -> Icons.Rounded.Download
                                    Podcast.FILTER_UNPLAYED -> Icons.Rounded.CheckCircle
                                    Podcast.FILTER_FAVORITE -> Icons.Rounded.Star
                                    else -> Icons.Rounded.Refresh
                                },
                                title = stringResource(
                                    when (filterMode) {
                                        Podcast.FILTER_DOWNLOADED -> R.string.no_downloaded_episodes
                                        Podcast.FILTER_UNPLAYED -> R.string.no_unplayed_episodes
                                        Podcast.FILTER_FAVORITE -> R.string.no_favorite_episodes
                                        else -> R.string.no_episodes_yet
                                    }
                                ),
                                hint = ""
                            )
                        }
                    }
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
                        onToggleFavorite = {
                            scope.launch { repository.setFavorite(episode.id, !episode.favorite) }
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
                // gate on the UNFILTERED page: with the Downloaded chip on,
                // the filtered count never reaches the limit and the button
                // vanished — making downloads beyond the first page
                // unreachable
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
                                    stringResource(
                                        R.string.show_more_episodes_n_of_m,
                                        episodes.size, total
                                    )
                                } else {
                                    stringResource(
                                        R.string.show_more_episodes_n_loaded,
                                        episodes.size
                                    )
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
            isLocalFolder = podcast!!.localFolderUri != null,
            episodeSortMode = podcast!!.episodeSortMode,
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
                            com.stepcast.app.playback.CommandReceiver::class.java
                        ).setAction(
                            com.stepcast.app.playback.CommandReceiver
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
                    repository.setEpisodeSortMode(podcastId, result.episodeSortMode)
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
                    for ((labelRes, days) in listOf(
                        R.string.retention_1_week to 7,
                        R.string.retention_1_month to 30,
                        R.string.retention_3_months to 90,
                        R.string.retention_1_year to 365
                    )) {
                        val label = stringResource(labelRes)
                        TextButton(onClick = {
                            olderThanOpen = false
                            scope.launch {
                                repository.markPlayedOlderThan(podcastId, days)
                                snackbar.showSnackbar(
                                    context.getString(
                                        R.string.episodes_older_marked_played, label
                                    )
                                )
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

    if (confirmMarkAllPlayed) {
        AlertDialog(
            onDismissRequest = { confirmMarkAllPlayed = false },
            title = { Text(stringResource(R.string.mark_all_played_confirm_title)) },
            text = { Text(stringResource(R.string.mark_all_played_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmMarkAllPlayed = false
                    scope.launch { repository.markAllPlayed(podcastId) }
                }) { Text(stringResource(R.string.mark_all_played)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmMarkAllPlayed = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (confirmUnsubscribe) {
        AlertDialog(
            onDismissRequest = { confirmUnsubscribe = false },
            title = {
                Text(
                    stringResource(
                        R.string.unsubscribe_confirm_title, podcast?.title.orEmpty()
                    )
                )
            },
            text = { Text(stringResource(R.string.unsubscribe_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnsubscribe = false
                    scope.launch {
                        repository.unsubscribe(podcastId)
                        onUnsubscribed()
                    }
                }) { Text(stringResource(R.string.unsubscribe)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnsubscribe = false }) {
                    Text(stringResource(R.string.cancel))
                }
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
    val autoQueue: Boolean,
    val episodeSortMode: Int
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
    isLocalFolder: Boolean,
    episodeSortMode: Int,
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
    var sortMode by remember { mutableStateOf(episodeSortMode) }

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
                        val filtered = text.filter { it.isDigit() || it == '.' }.take(4)
                        if (filtered.count { it == '.' } <= 1) speedText = filtered
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
                // local folders have no download machinery — auto-keep and
                // max-age would be dead knobs promising deletions that never
                // happen (the files aren't ours to delete)
                if (!isLocalFolder) {
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
                Text(
                    stringResource(R.string.sort_episodes_by),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .horizontalScroll(rememberScrollState())
                ) {
                    FilterChip(
                        selected = sortMode == Podcast.SORT_DATE,
                        onClick = { sortMode = Podcast.SORT_DATE },
                        label = { Text(stringResource(R.string.sort_date)) }
                    )
                    FilterChip(
                        selected = sortMode == Podcast.SORT_TITLE,
                        onClick = { sortMode = Podcast.SORT_TITLE },
                        label = { Text(stringResource(R.string.sort_title_a_z)) }
                    )
                    if (isLocalFolder) {
                        FilterChip(
                            selected = sortMode == Podcast.SORT_FILENAME,
                            onClick = { sortMode = Podcast.SORT_FILENAME },
                            label = { Text(stringResource(R.string.sort_filename_a_z)) }
                        )
                    }
                }
                if (sortMode == Podcast.SORT_DATE) {
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
                        // blank = default; unparseable input keeps the old
                        // value instead of silently resetting the speed
                        speed = if (speedText.isBlank()) {
                            0f
                        } else {
                            speedText.toFloatOrNull()?.coerceIn(0.5f, 3f) ?: speed
                        },
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
                        autoQueue = queueNew,
                        episodeSortMode = sortMode
                    )
                )
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
