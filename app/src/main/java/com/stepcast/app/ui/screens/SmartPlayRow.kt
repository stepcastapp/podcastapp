package com.stepcast.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import com.stepcast.app.R
import androidx.compose.ui.unit.dp
import com.stepcast.app.data.PodcastRepository
import com.stepcast.app.data.SmartPlay
import com.stepcast.app.ui.PlayerConnection
import com.stepcast.app.ui.theme.StepMark
import kotlinx.coroutines.launch

/**
 * The SmartPlay strip on the Up Next screen: tap the play button to fill
 * the queue from its rules and start playing; the pencil opens the rule
 * editor; "New" creates one and opens its editor. Wrapped in its own card
 * so it reads as a distinct section rather than stray rows sandwiched
 * between the queue list and the mini pill.
 */
@Composable
fun SmartPlayRow(
    repository: PodcastRepository,
    player: PlayerConnection,
    onEdit: (Long) -> Unit,
    snackbar: androidx.compose.material3.SnackbarHostState
) {
    val smartPlays by repository.smartPlays.collectAsState(initial = emptyList())
    val podcasts by repository.podcasts.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun playSmartPlay(smartPlay: SmartPlay) {
        scope.launch {
            val episodes = repository.episodesFor(smartPlay)
            if (episodes.isEmpty()) {
                // distinguish "library still refreshing" from "rules match nothing"
                val message = if (repository.episodeCount() == 0) {
                    context.getString(R.string.smartplay_empty_refreshing)
                } else {
                    context.getString(R.string.smartplay_no_match, smartPlay.name)
                }
                snackbar.showSnackbar(message)
                return@launch
            }
            // starting a SmartPlay replaces a possibly hand-curated queue —
            // that must never be silent or unrecoverable
            val before = repository.queueSnapshot().map { it.id }
            repository.replaceQueue(episodes.drop(1).map { it.id })
            val first = episodes.first()
            player.play(
                first,
                podcasts.firstOrNull { it.id == first.podcastId },
                fromStationId = if (smartPlay.continuous) smartPlay.id else 0L
            )
            if (before.isNotEmpty()) {
                val result = snackbar.showSnackbar(
                    message = context.getString(
                        R.string.queue_replaced_by, smartPlay.name
                    ),
                    actionLabel = context.getString(R.string.undo),
                    withDismissAction = true
                )
                if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                    repository.replaceQueue(before)
                }
            }
        }
    }

    var reorderOpen by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(Modifier.padding(vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 6.dp)
            ) {
                StepMark()
                Text(
                    stringResource(R.string.smartplays),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                )
                if (smartPlays.size > 1) {
                    IconButton(
                        onClick = { reorderOpen = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Rounded.SwapVert,
                            contentDescription = stringResource(R.string.reorder_smartplays),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            LazyRow(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                items(smartPlays, key = { it.id }) { smartPlay ->
                    SmartPlayChip(
                        smartPlay = smartPlay,
                        onPlay = { playSmartPlay(smartPlay) },
                        onEdit = { onEdit(smartPlay.id) }
                    )
                }
                item {
                    AssistChip(
                        onClick = {
                            scope.launch {
                                val id = repository.createSmartPlay("SmartPlay")
                                onEdit(id)
                            }
                        },
                        label = { Text(stringResource(R.string.new_label)) },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
            }
        }
    }

    if (reorderOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { reorderOpen = false },
            title = { Text(stringResource(R.string.reorder_smartplays)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    smartPlays.forEachIndexed { index, smartPlay ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                smartPlay.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                enabled = index > 0,
                                onClick = {
                                    scope.launch {
                                        repository.moveSmartPlay(smartPlay.id, up = true)
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Rounded.ArrowUpward,
                                    contentDescription = stringResource(
                                        R.string.move_up_cd, smartPlay.name
                                    )
                                )
                            }
                            IconButton(
                                enabled = index < smartPlays.lastIndex,
                                onClick = {
                                    scope.launch {
                                        repository.moveSmartPlay(smartPlay.id, up = false)
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Rounded.ArrowDownward,
                                    contentDescription = stringResource(
                                        R.string.move_down_cd, smartPlay.name
                                    )
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { reorderOpen = false }) {
                    Text(stringResource(R.string.done))
                }
            }
        )
    }
}

/** One SmartPlay as a pill — play glyph, name, edit — matching the shape
 * of the trailing "+ New" chip so the row reads as one family of pills. */
@Composable
private fun SmartPlayChip(
    smartPlay: SmartPlay,
    onPlay: () -> Unit,
    onEdit: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 2.dp, end = 12.dp)
        ) {
            IconButton(onClick = onPlay, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(
                        R.string.play_item_cd, smartPlay.name
                    ),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                smartPlay.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp)
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = stringResource(
                        R.string.edit_item_cd, smartPlay.name
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}
