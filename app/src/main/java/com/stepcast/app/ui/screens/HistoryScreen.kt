package com.stepcast.app.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import coil.request.ImageRequest
import com.stepcast.app.data.PodcastRepository
import com.stepcast.app.ui.Formatters
import com.stepcast.app.ui.PlayerConnection
import com.stepcast.app.ui.theme.EmptyState
import com.stepcast.app.ui.theme.ScreenTitle
import kotlinx.coroutines.launch
import androidx.compose.ui.res.pluralStringResource

/**
 * Recently finished episodes, newest first — the answer to "what was that
 * episode I just deleted?". Tap to replay from the start; the unplay
 * button drops it back into the unplayed pool.
 */
@Composable
fun HistoryScreen(
    repository: PodcastRepository,
    player: PlayerConnection
) {
    val history by repository.history.collectAsState(initial = emptyList())
    val podcasts by repository.podcasts.collectAsState(initial = emptyList())
    val byId = podcasts.associateBy { it.id }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = androidx.compose.runtime.remember {
        androidx.compose.material3.SnackbarHostState()
    }
    val markedUnplayedMsg = stringResource(R.string.marked_unplayed)
    val undoLabel = stringResource(R.string.undo)

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(
            stringResource(R.string.history),
            modifier = Modifier.padding(start = 16.dp, top = 4.dp)
        )
        Text(
            pluralStringResource(
                R.plurals.finished_episodes_count, history.size, history.size
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
        )
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Rounded.History,
                    title = stringResource(R.string.nothing_finished_yet),
                    hint = stringResource(R.string.history_empty_hint)
                )
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(history, key = { it.id }) { episode ->
                val podcast = byId[episode.podcastId]
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { player.play(episode, podcast) }
                            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp)
                    ) {
                        AsyncImage(
                            model = remember(episode.imageUrl, podcast?.imageUrl) {
                                ImageRequest.Builder(context)
                                    .data(episode.imageUrl ?: podcast?.imageUrl)
                                    // the show's art is almost always
                                    // already warm in Coil's memory cache —
                                    // show it immediately instead of a
                                    // blank square while episode art loads
                                    .apply {
                                        val fallback = podcast?.imageUrl
                                        if (episode.imageUrl != null && fallback != null) {
                                            placeholderMemoryCacheKey(fallback)
                                        }
                                    }
                                    .build()
                            },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(start = 12.dp)
                        ) {
                            Text(
                                episode.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            val finished = Formatters.dateTime(episode.playedAtMs)
                            Text(
                                listOf(podcast?.title.orEmpty(), finished)
                                    .filter { it.isNotEmpty() }
                                    .joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    repository.setPlayed(episode.id, false)
                                    val r = snackbar.showSnackbar(
                                        markedUnplayedMsg,
                                        actionLabel = undoLabel,
                                        duration = androidx.compose.material3
                                            .SnackbarDuration.Short
                                    )
                                    if (r == androidx.compose.material3
                                            .SnackbarResult.ActionPerformed
                                    ) {
                                        repository.setPlayed(episode.id, true)
                                    }
                                }
                            }
                        ) {
                            Icon(
                                Icons.Rounded.Replay,
                                contentDescription = stringResource(R.string.mark_unplayed),
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        IconButton(onClick = { player.play(episode, podcast) }) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = stringResource(R.string.play_again),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
    androidx.compose.material3.SnackbarHost(
        hostState = snackbar,
        modifier = Modifier.align(Alignment.BottomCenter)
    )
    }
}
