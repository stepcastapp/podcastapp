package com.nsavage.stepcast.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.nsavage.stepcast.data.ItunesSearch
import com.nsavage.stepcast.data.Podcast
import com.nsavage.stepcast.data.PodcastRepository
import com.nsavage.stepcast.data.SearchResult
import kotlinx.coroutines.launch

/**
 * Dead-feed repair: searches the directory for this show (query prefilled
 * with its title, fully editable) and repoints the subscription at the
 * picked result's feed URL. Episodes and history stay; the current feed's
 * own listing is shown disabled so "it found the same dead URL" is visible
 * rather than confusing.
 */
@Composable
fun FixFeedDialog(
    podcast: Podcast,
    search: ItunesSearch,
    repository: PodcastRepository,
    onReplaced: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf(podcast.title) }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val searchFailedMsg = stringResource(R.string.search_failed)
    val feedInUseMsg = stringResource(R.string.feed_in_use)

    fun normalized(url: String) =
        url.trim().substringAfter("://").removeSuffix("/").lowercase()

    fun runSearch() {
        val term = query.trim()
        if (term.isEmpty()) return
        scope.launch {
            busy = true; error = null
            results = try {
                search.search(term)
            } catch (e: Exception) {
                error = e.message ?: searchFailedMsg
                emptyList()
            }
            busy = false
        }
    }

    LaunchedEffect(Unit) { runSearch() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.replace_feed)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.replace_feed_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    enabled = !busy,
                    trailingIcon = {
                        IconButton(onClick = ::runSearch, enabled = !busy) {
                            Icon(
                                Icons.Rounded.Search,
                                contentDescription = stringResource(R.string.search)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                if (busy) {
                    CircularProgressIndicator(Modifier.padding(16.dp))
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(results, key = { it.feedUrl }) { result ->
                        val isCurrent =
                            normalized(result.feedUrl) == normalized(podcast.feedUrl)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = !busy && !isCurrent) {
                                    scope.launch {
                                        busy = true; error = null
                                        val takenBy = repository
                                            .podcastIdForFeed(result.feedUrl)
                                        if (takenBy != null && takenBy != podcast.id) {
                                            error = feedInUseMsg
                                            busy = false
                                            return@launch
                                        }
                                        runCatching {
                                            repository.repointFeed(
                                                podcast.id, result.feedUrl
                                            )
                                        }.onSuccess {
                                            busy = false
                                            onReplaced()
                                        }.onFailure {
                                            error = it.message
                                            busy = false
                                        }
                                    }
                                }
                                .padding(vertical = 6.dp, horizontal = 4.dp)
                        ) {
                            AsyncImage(
                                model = result.imageUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Column(
                                Modifier
                                    .weight(1f)
                                    .padding(start = 10.dp)
                            ) {
                                Text(
                                    result.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    if (isCurrent) {
                                        stringResource(R.string.current_feed)
                                    } else {
                                        listOf(
                                            result.author,
                                            result.feedUrl
                                                .substringAfter("://")
                                                .substringBefore("/")
                                        ).filter { it.isNotEmpty() }
                                            .joinToString(" · ")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isCurrent) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
