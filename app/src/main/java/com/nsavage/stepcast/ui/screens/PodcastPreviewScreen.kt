package com.nsavage.stepcast.ui.screens

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
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nsavage.stepcast.R
import com.nsavage.stepcast.data.ParsedFeed
import com.nsavage.stepcast.data.PodcastRepository
import com.nsavage.stepcast.ui.PlayerConnection
import com.nsavage.stepcast.ui.theme.EmptyState
import com.nsavage.stepcast.ui.theme.StepMark
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * A show from Discover BEFORE subscribing: artwork, description, and its
 * recent episodes — any of which can stream right now via the preview
 * player. Subscribe is an explicit button, so a tap on a search result is
 * never a commitment. Already-subscribed feeds swap the button for a jump
 * into the real podcast screen.
 */
@Composable
fun PodcastPreviewScreen(
    feedUrl: String,
    repository: PodcastRepository,
    player: PlayerConnection,
    onSubscribed: (Long) -> Unit,
    onOpenPodcast: (Long) -> Unit
) {
    var feed by remember { mutableStateOf<ParsedFeed?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var existingId by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var categoryPromptFor by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    val subscribeFailedMsg = stringResource(R.string.subscribe_failed)

    LaunchedEffect(feedUrl) {
        existingId = repository.podcastIdForFeed(feedUrl)
        runCatching { repository.previewFeed(feedUrl) }
            .onSuccess {
                feed = it
                // same show subscribed under a different feed URL entirely
                if (existingId == null) {
                    existingId = repository.podcastIdByTitle(it.title)
                }
            }
            .onFailure { error = it.message }
    }

    val loaded = feed
    when {
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.Rounded.ErrorOutline,
                title = stringResource(R.string.couldnt_load_feed),
                hint = error.orEmpty()
            )
        }
        loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> Column(Modifier.fillMaxSize()) {
            // hero header, same card family as the subscribed podcast screen
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
                    AsyncImage(
                        model = loaded.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
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
                            loaded.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (loaded.author.isNotEmpty()) {
                            Text(
                                loaded.author,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.padding(top = 6.dp))
                        val subscribedId = existingId
                        if (subscribedId != null) {
                            OutlinedButton(onClick = { onOpenPodcast(subscribedId) }) {
                                Text(stringResource(R.string.open_in_library))
                            }
                        } else {
                            Button(
                                enabled = !busy,
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        runCatching {
                                            repository.subscribe(feedUrl, loaded)
                                        }.onSuccess {
                                            categoryPromptFor = it
                                        }.onFailure {
                                            error = it.message ?: subscribeFailedMsg
                                        }
                                        busy = false
                                    }
                                }
                            ) {
                                Text(
                                    stringResource(
                                        if (busy) {
                                            R.string.subscribing
                                        } else {
                                            R.string.subscribe
                                        }
                                    )
                                )
                            }
                        }
                    }
                }
            }

            if (loaded.description.isNotBlank()) {
                var expanded by remember { mutableStateOf(false) }
                Text(
                    loaded.description.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp)
            ) {
                StepMark()
                Text(
                    stringResource(R.string.latest_episodes),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Text(
                stringResource(R.string.preview_streams_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
            )

            LazyColumn(Modifier.fillMaxSize()) {
                // positional keys: preview feeds can carry blank/duplicate guids
                items(loaded.episodes.take(30)) { ep ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = ep.audioUrl.isNotEmpty()) {
                                player.playPreview(
                                    title = ep.title,
                                    podcastTitle = loaded.title,
                                    artworkUrl = ep.imageUrl ?: loaded.imageUrl,
                                    audioUrl = ep.audioUrl
                                )
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                ep.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            val date = if (ep.pubDateMs > 0) {
                                DateFormat.getDateInstance(DateFormat.MEDIUM)
                                    .format(Date(ep.pubDateMs))
                            } else {
                                ""
                            }
                            val duration = if (ep.durationMs > 0) {
                                stringResource(
                                    R.string.duration_minutes, ep.durationMs / 60000
                                )
                            } else {
                                ""
                            }
                            val meta = listOf(date, duration).filter { it.isNotEmpty() }
                            if (meta.isNotEmpty()) {
                                Text(
                                    meta.joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                        if (ep.audioUrl.isNotEmpty()) {
                            IconButton(onClick = {
                                player.playPreview(
                                    title = ep.title,
                                    podcastTitle = loaded.title,
                                    artworkUrl = ep.imageUrl ?: loaded.imageUrl,
                                    audioUrl = ep.audioUrl
                                )
                            }) {
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(
                                        R.string.play_item_cd, ep.title
                                    ),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    categoryPromptFor?.let { newPodcastId ->
        val categoryMetas by repository.categoryMetas
            .collectAsState(initial = emptyList())
        val categories = categoryMetas.map { it.name }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
        var picked by remember { mutableStateOf("") }
        var newCategory by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {
                categoryPromptFor = null
                onSubscribed(newPodcastId)
            },
            title = { Text(stringResource(R.string.subscribed_add_to_a_category)) },
            text = {
                Column {
                    LazyRow {
                        item {
                            FilterChip(
                                selected = picked.isEmpty(),
                                onClick = { picked = ""; newCategory = "" },
                                label = { Text(stringResource(R.string.none)) }
                            )
                        }
                        items(categories) { existing ->
                            Spacer(Modifier.width(6.dp))
                            FilterChip(
                                selected = picked == existing,
                                onClick = { picked = existing; newCategory = "" },
                                label = { Text(existing) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = newCategory,
                        onValueChange = { text ->
                            newCategory = text.take(40)
                            picked = text.trim()
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
                    categoryPromptFor = null
                    val category = picked
                    scope.launch { repository.setSingleCategory(newPodcastId, category) }
                    onSubscribed(newPodcastId)
                }) { Text(stringResource(R.string.done)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    categoryPromptFor = null
                    onSubscribed(newPodcastId)
                }) { Text(stringResource(R.string.skip)) }
            }
        )
    }
}
