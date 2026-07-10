package com.nsavage.stepcast.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.nsavage.stepcast.R
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nsavage.stepcast.data.Podcast
import com.nsavage.stepcast.data.PodcastRepository
import com.nsavage.stepcast.sync.RefreshWorker
import com.nsavage.stepcast.ui.theme.EmptyState
import com.nsavage.stepcast.ui.theme.ScreenTitle
import com.nsavage.stepcast.ui.theme.StepMark
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    repository: PodcastRepository,
    onPodcastClick: (Long) -> Unit,
    onCategoryClick: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenInbox: () -> Unit
) {
    val podcasts by repository.podcasts.collectAsState(initial = emptyList())
    val categoryMetas by repository.categoryMetas.collectAsState(initial = emptyList())
    val memberships by repository.podcastCategories.collectAsState(initial = emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // categories created before metas existed get rows on first sight
    LaunchedEffect(podcasts.size) { repository.ensureCategoryMetas() }

    // multi-select for bulk category assignment
    val selected = remember { mutableStateListOf<Long>() }
    var bulkCategoryDialogOpen by remember { mutableStateOf(false) }
    fun toggleSelected(id: Long) {
        if (!selected.remove(id)) selected.add(id)
    }
    BackHandler(enabled = selected.isNotEmpty()) { selected.clear() }

    Column(Modifier.fillMaxSize()) {
        if (selected.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp)
            ) {
                IconButton(onClick = { selected.clear() }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel_selection))
                }
                Text(
                    stringResource(R.string.n_selected, selected.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { bulkCategoryDialogOpen = true }) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Label,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text(stringResource(R.string.set_category))
                }
            }
        } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp)
        ) {
            ScreenTitle(stringResource(R.string.library), modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenDiscover) {
                Icon(
                    Icons.Rounded.Explore,
                    contentDescription = stringResource(R.string.discover)
                )
            }
            IconButton(onClick = onOpenSearch) {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = stringResource(R.string.search_your_library)
                )
            }
            IconButton(onClick = { RefreshWorker.refreshNow(context) }) {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.refresh_all_feeds))
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.settings))
            }
        }
        }
        if (podcasts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(
                        icon = Icons.Rounded.Podcasts,
                        title = stringResource(R.string.nothing_here_yet),
                        hint = stringResource(R.string.home_empty_hint)
                    )
                    // a fresh install shouldn't have to hunt through header
                    // icons and Settings to get its first shows
                    androidx.compose.material3.Button(
                        onClick = onOpenDiscover,
                        modifier = Modifier.padding(top = 20.dp)
                    ) {
                        Text(stringResource(R.string.find_shows))
                    }
                    androidx.compose.material3.OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(stringResource(R.string.import_from_another_app))
                    }
                }
            }
        } else {
            // the answer to "anything new since I last looked?" without
            // opening every show — tap through to the triage inbox
            val inboxCount by repository.inboxCount().collectAsState(initial = 0)
            if (inboxCount > 0) {
                androidx.compose.material3.Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(16.dp),
                    onClick = onOpenInbox,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        StepMark()
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(start = 10.dp)
                        ) {
                            Text(
                                stringResource(R.string.inbox_title),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                pluralStringResource(
                                    R.plurals.new_episodes_count,
                                    inboxCount, inboxCount
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            val refreshingCategories = remember { mutableStateListOf<String>() }
            PodcastGrid(
                podcasts = podcasts,
                memberships = memberships,
                categoryOrder = categoryMetas.map { it.name },
                selectedIds = selected,
                onPodcastClick = { id ->
                    if (selected.isNotEmpty()) toggleSelected(id) else onPodcastClick(id)
                },
                onPodcastLongClick = ::toggleSelected,
                onCategoryClick = onCategoryClick,
                refreshingCategories = refreshingCategories,
                onRefreshCategory = { category ->
                    if (category !in refreshingCategories) {
                        refreshingCategories.add(category)
                        scope.launch {
                            runCatching { repository.refreshCategory(category) }
                            refreshingCategories.remove(category)
                        }
                    }
                }
            )
        }
    }

    if (bulkCategoryDialogOpen) {
        val categories = categoryMetas.map { it.name }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
        var pickedCategory by remember { mutableStateOf("") }
        var newCategory by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { bulkCategoryDialogOpen = false },
            title = {
                Text(
                    pluralStringResource(
                        R.plurals.set_category_for_podcasts,
                        selected.size, selected.size
                    )
                )
            },
            text = {
                Column {
                    LazyRow {
                        item {
                            FilterChip(
                                selected = pickedCategory.isEmpty(),
                                onClick = { pickedCategory = ""; newCategory = "" },
                                label = { Text(stringResource(R.string.none)) }
                            )
                        }
                        items(categories) { existing ->
                            Spacer(Modifier.width(6.dp))
                            FilterChip(
                                selected = pickedCategory == existing,
                                onClick = { pickedCategory = existing; newCategory = "" },
                                label = { Text(existing) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = newCategory,
                        onValueChange = { text ->
                            newCategory = text.take(40)
                            pickedCategory = text.trim()
                        },
                        label = { Text(stringResource(R.string.new_category)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            capitalization =
                                androidx.compose.ui.text.input.KeyboardCapitalization.Sentences
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    bulkCategoryDialogOpen = false
                    val ids = selected.toList()
                    val category = pickedCategory
                    selected.clear()
                    scope.launch {
                        ids.forEach { repository.setSingleCategory(it, category) }
                    }
                }) { Text(stringResource(R.string.apply)) }
            },
            dismissButton = {
                TextButton(onClick = { bulkCategoryDialogOpen = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

}

/**
 * The Library, grouped by category: each category is a section with a
 * clickable header (opens the merged category view) above its podcasts;
 * uncategorized podcasts follow.
 */
@Composable
private fun PodcastGrid(
    podcasts: List<Podcast>,
    memberships: List<com.nsavage.stepcast.data.PodcastCategory>,
    categoryOrder: List<String>,
    selectedIds: List<Long>,
    onPodcastClick: (Long) -> Unit,
    onPodcastLongClick: (Long) -> Unit,
    onCategoryClick: (String) -> Unit,
    refreshingCategories: List<String>,
    onRefreshCategory: (String) -> Unit
) {
    val orderIndex = categoryOrder.withIndex().associate { (i, name) -> name to i }
    // membership-driven: a podcast shows under EVERY category it's in
    val podcastsById = podcasts.associateBy { it.id }
    val byCategory = memberships
        .groupBy({ it.category }, { it.podcastId })
        .mapValues { (_, ids) ->
            ids.mapNotNull(podcastsById::get).sortedBy { it.title.lowercase() }
        }
        .entries
        .filter { it.value.isNotEmpty() }
        .sortedWith(
            compareBy(
                { orderIndex[it.key] ?: Int.MAX_VALUE },
                { it.key.lowercase() }
            )
        )
    val categorizedIds = memberships.mapTo(HashSet()) { it.podcastId }
    val uncategorized = podcasts.filter { it.id !in categorizedIds }

    val context = LocalContext.current
    val collapsed = com.nsavage.stepcast.data.AppSettings.collapsedCategories

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        for ((category, members) in byCategory) {
            val isCollapsed = category in collapsed
            item(key = "header-$category", span = { GridItemSpan(maxLineSpan) }) {
                // tap the header to OPEN the merged category view; only the
                // leading chevron collapses/expands the tile list
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .clickable { onCategoryClick(category) }
                        .padding(start = 2.dp, top = 2.dp, bottom = 2.dp, end = 12.dp)
                ) {
                    IconButton(onClick = {
                        com.nsavage.stepcast.data.AppSettings
                            .toggleCategoryCollapsed(context, category)
                    }) {
                        Icon(
                            if (isCollapsed) {
                                Icons.AutoMirrored.Rounded.KeyboardArrowRight
                            } else {
                                Icons.Rounded.KeyboardArrowDown
                            },
                            contentDescription = stringResource(
                                if (isCollapsed) {
                                    R.string.expand_item_cd
                                } else {
                                    R.string.collapse_item_cd
                                },
                                category
                            ),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    StepMark(color = MaterialTheme.colorScheme.tertiary)
                    Text(
                        "$category  ·  ${members.size}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                    )
                    if (com.nsavage.stepcast.data.AppSettings.categoryRefreshButtons) {
                        if (category in refreshingCategories) {
                            androidx.compose.material3.CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier
                                    .padding(12.dp)
                                    .then(Modifier.width(20.dp).aspectRatio(1f))
                            )
                        } else {
                            IconButton(onClick = { onRefreshCategory(category) }) {
                                Icon(
                                    Icons.Rounded.Refresh,
                                    contentDescription = stringResource(
                                        R.string.refresh_item_cd, category
                                    ),
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                }
            }
            if (!isCollapsed) {
                if (com.nsavage.stepcast.data.AppSettings.libraryCompactList) {
                    items(
                        members,
                        key = { "c/$category/${it.id}" },
                        span = { GridItemSpan(maxLineSpan) }
                    ) { podcast ->
                        PodcastCompactRow(
                            podcast = podcast,
                            selected = podcast.id in selectedIds,
                            onClick = { onPodcastClick(podcast.id) },
                            onLongClick = { onPodcastLongClick(podcast.id) }
                        )
                    }
                } else {
                    items(members, key = { "c/$category/${it.id}" }) { podcast ->
                        PodcastTile(
                            podcast = podcast,
                            selected = podcast.id in selectedIds,
                            onClick = { onPodcastClick(podcast.id) },
                            onLongClick = { onPodcastLongClick(podcast.id) }
                        )
                    }
                }
            }
        }
        if (uncategorized.isNotEmpty()) {
            if (byCategory.isNotEmpty()) {
                item(key = "header-uncategorized", span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        StepMark(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(R.string.other_podcasts),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
            if (com.nsavage.stepcast.data.AppSettings.libraryCompactList) {
                items(
                    uncategorized,
                    key = { "u/${it.id}" },
                    span = { GridItemSpan(maxLineSpan) }
                ) { podcast ->
                    PodcastCompactRow(
                        podcast = podcast,
                        selected = podcast.id in selectedIds,
                        onClick = { onPodcastClick(podcast.id) },
                        onLongClick = { onPodcastLongClick(podcast.id) }
                    )
                }
            } else {
                items(uncategorized, key = { "u/${it.id}" }) { podcast ->
                    PodcastTile(
                        podcast = podcast,
                        selected = podcast.id in selectedIds,
                        onClick = { onPodcastClick(podcast.id) },
                        onLongClick = { onPodcastLongClick(podcast.id) }
                    )
                }
            }
        }

        // automatic bottom section: feeds the dead-feed detector flagged.
        // These shows STAY in their categories above — this is a repair
        // list, and each podcast screen offers the replacement-feed search.
        val failing = podcasts
            .filter { it.localFolderUri == null && it.consecutiveFailures >= 3 }
            .sortedBy { it.title.lowercase() }
        if (failing.isNotEmpty()) {
            item(key = "header-attention", span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(
                        Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        stringResource(R.string.needs_attention) +
                            "  ·  ${failing.size}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            if (com.nsavage.stepcast.data.AppSettings.libraryCompactList) {
                items(
                    failing,
                    key = { "a/${it.id}" },
                    span = { GridItemSpan(maxLineSpan) }
                ) { podcast ->
                    PodcastCompactRow(
                        podcast = podcast,
                        selected = podcast.id in selectedIds,
                        onClick = { onPodcastClick(podcast.id) },
                        onLongClick = { onPodcastLongClick(podcast.id) }
                    )
                }
            } else {
                items(failing, key = { "a/${it.id}" }) { podcast ->
                    PodcastTile(
                        podcast = podcast,
                        selected = podcast.id in selectedIds,
                        onClick = { onPodcastClick(podcast.id) },
                        onLongClick = { onPodcastLongClick(podcast.id) }
                    )
                }
            }
        }
    }
}

/** Compact alternative to [PodcastTile]: one slim row per podcast. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PodcastCompactRow(
    podcast: Podcast,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                } else {
                    Modifier
                }
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        com.nsavage.stepcast.ui.theme.ArtworkOrFolder(
            imageUrl = podcast.imageUrl,
            isLocalFolder = podcast.localFolderUri != null,
            contentDescription = null,
            modifier = Modifier
                .width(40.dp)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
        )
        Text(
            podcast.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
        )
        if (podcast.consecutiveFailures >= 3) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = stringResource(R.string.feed_failing_to_refresh),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = 6.dp)
            )
        }
        if (selected) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = stringResource(R.string.selected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 6.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PodcastTile(
    podcast: Podcast,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box {
            com.nsavage.stepcast.ui.theme.ArtworkOrFolder(
                imageUrl = podcast.imageUrl,
                isLocalFolder = podcast.localFolderUri != null,
                contentDescription = podcast.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .then(
                        if (selected) {
                            Modifier.border(
                                3.dp,
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(12.dp)
                            )
                        } else {
                            Modifier
                        }
                    )
            )
            if (selected) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = stringResource(R.string.selected),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                )
            }
            if (podcast.consecutiveFailures >= 3) {
                Icon(
                    Icons.Rounded.ErrorOutline,
                    contentDescription = stringResource(R.string.feed_failing_to_refresh),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                )
            }
        }
        Text(
            podcast.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
