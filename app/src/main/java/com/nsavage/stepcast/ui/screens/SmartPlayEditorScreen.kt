package com.nsavage.stepcast.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import com.nsavage.stepcast.R
import androidx.compose.ui.unit.dp
import com.nsavage.stepcast.data.Podcast
import com.nsavage.stepcast.data.PodcastRepository
import com.nsavage.stepcast.data.SmartPlayEntry
import kotlinx.coroutines.launch
import androidx.compose.ui.res.pluralStringResource

/**
 * Full editor for one SmartPlay: rename it, and manage its ORDERED list of
 * rules — each rule picks episodes from a scope (everything, a folder, or
 * one podcast) with its own count, sort, and filters. Rules run top to
 * bottom when the SmartPlay plays.
 */
@Composable
fun SmartPlayEditorScreen(
    smartPlayId: Long,
    repository: PodcastRepository,
    onDone: () -> Unit
) {
    val smartPlay by repository.observeSmartPlay(smartPlayId).collectAsState(initial = null)
    val entries by repository.observeSmartPlayEntries(smartPlayId)
        .collectAsState(initial = emptyList())

    // live "N match now" per rule — makes empty SmartPlays self-explanatory
    var matchCounts by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(mapOf<Long, Int>())
    }
    var explains by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(mapOf<Long, String>())
    }
    androidx.compose.runtime.LaunchedEffect(entries) {
        matchCounts = entries.associate { it.id to repository.countSmartPlayMatches(it) }
        explains = entries
            .filter { matchCounts[it.id] == 0 }
            .associate { it.id to repository.explainSmartPlayEntry(it) }
    }
    val podcasts by repository.podcasts.collectAsState(initial = emptyList())
    val podcastsById = podcasts.associateBy { it.id }
    val categoryMetas by repository.categoryMetas.collectAsState(initial = emptyList())
    val categoryNames = categoryMetas.map { it.name }
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var nameLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(smartPlay) {
        val current = smartPlay
        if (current != null && !nameLoaded) {
            name = current.name
            nameLoaded = true
        }
    }
    var editingEntry by remember { mutableStateOf<SmartPlayEntry?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(30) },
                label = { Text(stringResource(R.string.smartplay_name)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input
                        .KeyboardCapitalization.Sentences
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                scope.launch {
                    repository.renameSmartPlay(smartPlayId, name)
                    onDone()
                }
            }) { Text(stringResource(R.string.done)) }
        }

        Text(
            stringResource(R.string.rules_run_top_to_bottom_each_adds_its_ep),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        smartPlay?.let { sp ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.station),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        stringResource(R.string.station_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = sp.continuous,
                    onCheckedChange = {
                        scope.launch {
                            repository.setSmartPlayContinuous(sp.id, it)
                        }
                    }
                )
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editingEntry = entry }
                        .padding(vertical = 6.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            entryScopeLabel(entry, podcastsById),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val matches = matchCounts[entry.id]
                        val explain = if (matches == 0) {
                            explains[entry.id]?.let { " — $it" }.orEmpty()
                        } else {
                            ""
                        }
                        Text(
                            entryRuleLabel(entry) +
                                (matches?.let {
                                    " · " + stringResource(R.string.n_match_now, it)
                                } ?: "") + explain,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (matches == 0) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                repository.moveSmartPlayEntry(smartPlayId, entry.id, up = true)
                            }
                        },
                        enabled = index > 0
                    ) { Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.move_rule_up)) }
                    IconButton(
                        onClick = {
                            scope.launch {
                                repository.moveSmartPlayEntry(smartPlayId, entry.id, up = false)
                            }
                        },
                        enabled = index < entries.lastIndex
                    ) { Icon(Icons.Rounded.ArrowDownward, contentDescription = stringResource(R.string.move_rule_down)) }
                    IconButton(onClick = {
                        scope.launch { repository.deleteSmartPlayEntry(entry.id) }
                    }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.delete_rule)) }
                }
            }
            item {
                Row(modifier = Modifier.padding(vertical = 8.dp)) {
                    Button(onClick = {
                        editingEntry = SmartPlayEntry(
                            smartPlayId = smartPlayId,
                            episodeSort = SmartPlayEntry.SORT_NEWEST
                        )
                    }) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.add_rule))
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = { confirmDelete = true }) {
                        Text(stringResource(R.string.delete_smartplay))
                    }
                }
            }
        }
    }

    editingEntry?.let { entry ->
        EntryEditorDialog(
            entry = entry,
            podcasts = podcasts,
            folders = categoryNames
                .distinct().sorted(),
            onDismiss = { editingEntry = null },
            onSave = { edited ->
                editingEntry = null
                scope.launch { repository.saveSmartPlayEntry(edited) }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_this_smartplay)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        repository.deleteSmartPlay(smartPlayId)
                        onDone()
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun entryScopeLabel(entry: SmartPlayEntry, podcastsById: Map<Long, Podcast>): String =
    when {
        entry.podcastId != null ->
            podcastsById[entry.podcastId]?.title
                ?: stringResource(R.string.deleted_podcast)
        entry.folder != null -> entry.folder
        else -> stringResource(R.string.all_podcasts)
    }

@Composable
private fun entryRuleLabel(entry: SmartPlayEntry): String {
    val count = if (entry.maxTracks > 0) {
        pluralStringResource(
            R.plurals.episodes_count, entry.maxTracks, entry.maxTracks
        )
    } else {
        stringResource(R.string.all_episodes)
    }
    val sort = sortLabel(entry.episodeSort)
    val extras = buildList {
        if (entry.downloadedOnly) add(stringResource(R.string.downloaded_only))
        if (entry.includePlayed) add(stringResource(R.string.incl_played))
    }
    return listOf(count, sort).plus(extras).joinToString(" • ")
}

@Composable
private fun EntryEditorDialog(
    entry: SmartPlayEntry,
    podcasts: List<Podcast>,
    folders: List<String>,
    onDismiss: () -> Unit,
    onSave: (SmartPlayEntry) -> Unit
) {
    var podcastId by remember { mutableStateOf(entry.podcastId) }
    var folder by remember { mutableStateOf(entry.folder) }
    var maxText by remember { mutableStateOf(entry.maxTracks.toString()) }
    var sort by remember { mutableStateOf(entry.episodeSort) }
    var includePlayed by remember { mutableStateOf(entry.includePlayed) }
    var downloadedOnly by remember { mutableStateOf(entry.downloadedOnly) }
    var podcastMenuOpen by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (entry.id == 0L) R.string.new_rule else R.string.edit_rule
                )
            )
        },
        text = {
            Column {
                Text(stringResource(R.string.source), style = MaterialTheme.typography.labelMedium)
                LazyRow {
                    item {
                        AssistChip(
                            onClick = { podcastId = null; folder = null },
                            label = {
                                val allShows = stringResource(R.string.all_podcasts)
                                Text(
                                    if (podcastId == null && folder == null) {
                                        "✓ $allShows"
                                    } else {
                                        allShows
                                    }
                                )
                            }
                        )
                    }
                    items(folders) { f ->
                        Spacer(Modifier.width(6.dp))
                        AssistChip(
                            onClick = { folder = f; podcastId = null },
                            label = { Text(if (folder == f) "✓ $f" else f) }
                        )
                    }
                }
                Spacer(Modifier.size(6.dp))
                OutlinedButton(onClick = { podcastMenuOpen = true }) {
                    Text(
                        podcastId?.let { id ->
                            "✓ " + (podcasts.firstOrNull { it.id == id }?.title ?: "?")
                        } ?: stringResource(R.string.pick_a_single_podcast),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    DropdownMenu(
                        expanded = podcastMenuOpen,
                        onDismissRequest = { podcastMenuOpen = false }
                    ) {
                        for (podcast in podcasts) {
                            DropdownMenuItem(
                                text = { Text(podcast.title, maxLines = 1) },
                                onClick = {
                                    podcastMenuOpen = false
                                    podcastId = podcast.id
                                    folder = null
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.size(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = maxText,
                        onValueChange = { maxText = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.episodes_0_all)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { sortMenuOpen = true }) {
                        Text(sortLabel(sort))
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false }
                        ) {
                            for ((value, labelRes) in SORT_OPTIONS) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(labelRes)) },
                                    onClick = { sortMenuOpen = false; sort = value }
                                )
                            }
                        }
                    }
                }

                EntrySwitch(
                    stringResource(R.string.downloaded_only_label), downloadedOnly
                ) { downloadedOnly = it }
                EntrySwitch(
                    stringResource(R.string.include_played), includePlayed
                ) { includePlayed = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    entry.copy(
                        podcastId = podcastId,
                        folder = folder,
                        maxTracks = (maxText.toIntOrNull() ?: 0).coerceIn(0, 500),
                        episodeSort = sort,
                        includePlayed = includePlayed,
                        downloadedOnly = downloadedOnly
                    )
                )
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun EntrySwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Sort constants paired with their labels; the single source for the
 * editor's dropdown and the rule summaries. */
private val SORT_OPTIONS = listOf(
    SmartPlayEntry.SORT_NAME_ASC to R.string.sort_name_asc,
    SmartPlayEntry.SORT_NAME_DESC to R.string.sort_name_desc,
    SmartPlayEntry.SORT_OLDEST to R.string.sort_oldest,
    SmartPlayEntry.SORT_NEWEST to R.string.sort_newest,
    SmartPlayEntry.SORT_DURATION to R.string.sort_shortest,
    SmartPlayEntry.SORT_SHUFFLE to R.string.sort_shuffle
)

@Composable
private fun sortLabel(sort: Int): String = stringResource(
    SORT_OPTIONS.firstOrNull { it.first == sort }?.second
        ?: R.string.sort
)
