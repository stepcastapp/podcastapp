package com.nsavage.stepcast.ui.screens

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import com.nsavage.stepcast.R
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nsavage.stepcast.data.ItunesSearch
import com.nsavage.stepcast.data.SearchResult
import com.nsavage.stepcast.ui.theme.EmptyState
import com.nsavage.stepcast.ui.theme.ScreenTitle
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    search: ItunesSearch,
    onOpenPreview: (String) -> Unit,
    initialQuery: String = ""
) {
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // the charts fill the screen until the user actually searches
    var trending by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var trendingBusy by remember { mutableStateOf(false) }
    val searchFailedMsg = stringResource(R.string.search_failed)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (trending.isEmpty()) {
            trendingBusy = true
            trending = runCatching { search.trending() }.getOrDefault(emptyList())
            trendingBusy = false
        }
    }

    fun runSearch() {
        val term = query.trim()
        if (term.isEmpty()) return
        if (term.startsWith("http://") || term.startsWith("https://")) {
            // direct RSS URL: straight to its preview
            onOpenPreview(term)
            return
        }
        scope.launch {
            busy = true; error = null
            try {
                results = search.search(term)
            } catch (e: Exception) {
                error = e.message ?: searchFailedMsg
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        ScreenTitle(stringResource(R.string.discover), modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            placeholder = { Text(stringResource(R.string.search_podcasts_or_paste_rss_url)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
            trailingIcon = {
                IconButton(onClick = ::runSearch, enabled = !busy) {
                    Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.search))
                }
            }
        )

        if (busy) {
            CircularProgressIndicator(Modifier.padding(24.dp))
        }
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }

        val showTrending = results.isEmpty() && trending.isNotEmpty()
        val listItems = if (showTrending) trending else results

        if (listItems.isEmpty() && trendingBusy && !busy) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.loading_top_podcasts),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
            return@Column
        }

        if (listItems.isEmpty() && !busy && error == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Rounded.Explore,
                    title = stringResource(R.string.find_your_next_show),
                    hint = stringResource(R.string.discover_empty_hint)
                )
            }
            return@Column
        }

        if (showTrending && !busy) {
            Text(
                stringResource(R.string.top_podcasts_right_now),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp)
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(listItems, key = { it.feedUrl }) { result ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        // tap = look, not commit: the preview screen owns Subscribe
                        .clickable { onOpenPreview(result.feedUrl) }
                        .padding(8.dp)
                ) {
                    AsyncImage(
                        model = result.imageUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(
                            result.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            result.author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                }
            }
        }
    }
}
