package com.stepcast.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stepcast.app.R
import com.stepcast.app.data.Episode
import com.stepcast.app.data.PodcastRepository
import kotlinx.coroutines.launch

/**
 * Saved moments in the playing episode: "bookmark here" (with an optional
 * note — a quote, a book title, a recipe) plus the episode's existing
 * bookmarks; tap one to jump back to it.
 */
@Composable
fun BookmarksDialog(
    episode: Episode,
    repository: PodcastRepository,
    currentPositionMs: Long,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val bookmarks by remember(episode.id) { repository.bookmarksFor(episode.id) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    // captured when the dialog opens: typing a note mustn't move the mark
    val markAt = remember { currentPositionMs }
    var note by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookmarks)) },
        text = {
            Column {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.bookmark_note_hint, clock(markAt))) },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                if (bookmarks.isEmpty()) {
                    Text(
                        stringResource(R.string.no_bookmarks_yet),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 280.dp).padding(top = 8.dp)) {
                        items(bookmarks, key = { it.id }) { b ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSeek(b.positionMs)
                                        onDismiss()
                                    }
                                    .padding(vertical = 4.dp)
                            ) {
                                Text(
                                    clock(b.positionMs),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.width(72.dp)
                                )
                                Text(
                                    b.note.ifEmpty { "—" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    scope.launch { repository.deleteBookmark(b.id) }
                                }) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = stringResource(R.string.delete_bookmark)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    repository.addBookmark(episode.id, markAt, note.trim())
                    note = ""
                }
            }) { Text(stringResource(R.string.add_bookmark_at, clock(markAt))) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}

/** "1:02:03" / "4:05". */
internal fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
