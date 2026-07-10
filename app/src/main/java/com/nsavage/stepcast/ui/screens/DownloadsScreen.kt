package com.nsavage.stepcast.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nsavage.stepcast.R
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
import androidx.compose.ui.unit.dp
import com.nsavage.stepcast.data.AppSettings
import com.nsavage.stepcast.data.Episode
import androidx.compose.ui.res.pluralStringResource
import com.nsavage.stepcast.data.PodcastRepository
import com.nsavage.stepcast.download.DownloadWorker
import com.nsavage.stepcast.ui.theme.EmptyState
import com.nsavage.stepcast.ui.theme.ScreenTitle
import com.nsavage.stepcast.ui.theme.StepMark
import kotlinx.coroutines.launch

/**
 * Full download management: what's actively downloading, what's waiting
 * (and WHY — a Wi-Fi-only setting on mobile data is the usual answer),
 * and what failed, with per-row and bulk retry/dismiss. "Use mobile data"
 * is a one-shot override that re-enqueues everything without the
 * unmetered constraint, leaving the global setting alone.
 */
@Composable
fun DownloadsScreen(repository: PodcastRepository) {
    val activity by repository.downloadActivity.collectAsState(initial = emptyList())
    val podcasts by repository.podcasts.collectAsState(initial = emptyList())
    val podcastsById = podcasts.associateBy { it.id }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val downloading = activity.filter { it.isDownloading && it.downloadProgress > 0 }
    val waiting = activity.filter { it.isDownloading && it.downloadProgress <= 0 }
    val failed = activity.filter { it.downloadStatus == Episode.DOWNLOAD_FAILED }
    val wifiOnly = AppSettings.wifiOnlyDownloads
    val onMeteredNetwork = remember {
        runCatching {
            val cm = context.getSystemService(
                android.content.Context.CONNECTIVITY_SERVICE
            ) as android.net.ConnectivityManager
            cm.isActiveNetworkMetered
        }.getOrDefault(false)
    }
    val waitingForWifi = wifiOnly && onMeteredNetwork && waiting.isNotEmpty()

    var confirmCancelAll by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle(
            stringResource(R.string.downloads),
            modifier = Modifier.padding(start = 16.dp, top = 4.dp)
        )
        val nothingInFlight = stringResource(R.string.nothing_in_flight)
        Text(
            buildList {
                if (downloading.isNotEmpty()) {
                    add(stringResource(R.string.n_downloading, downloading.size))
                }
                if (waiting.isNotEmpty()) {
                    add(stringResource(R.string.n_waiting, waiting.size))
                }
                if (failed.isNotEmpty()) {
                    add(stringResource(R.string.n_failed, failed.size))
                }
                if (isEmpty()) add(nothingInFlight)
            }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp)
        )

        // bulk actions — the interesting one is the Wi-Fi override
        Row(Modifier.padding(start = 8.dp)) {
            if (wifiOnly && (waiting.isNotEmpty() || failed.isNotEmpty())) {
                TextButton(onClick = {
                    (waiting + failed).forEach {
                        DownloadWorker.start(context, it.id, allowMetered = true)
                    }
                }) {
                    Text(
                        stringResource(
                            R.string.use_mobile_data_n, waiting.size + failed.size
                        )
                    )
                }
            }
            if (failed.isNotEmpty()) {
                TextButton(onClick = {
                    failed.forEach { DownloadWorker.start(context, it.id) }
                }) { Text(stringResource(R.string.retry_all_n, failed.size)) }
            }
            if (downloading.isNotEmpty() || waiting.isNotEmpty()) {
                TextButton(onClick = { confirmCancelAll = true }) {
                    Text(
                        stringResource(
                            R.string.cancel_all_n, downloading.size + waiting.size
                        )
                    )
                }
            }
        }
        if (failed.isNotEmpty()) {
            Row(Modifier.padding(start = 8.dp)) {
                TextButton(onClick = {
                    scope.launch { failed.forEach { repository.dismissDownload(it.id) } }
                }) { Text(stringResource(R.string.dismiss_all_failed_n, failed.size)) }
            }
        }

        if (activity.isEmpty()) {
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(
                    icon = Icons.Rounded.CloudDone,
                    title = stringResource(R.string.all_caught_up),
                    hint = stringResource(R.string.downloads_empty_hint)
                )
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            if (downloading.isNotEmpty()) {
                item(key = "h-downloading") {
                    SectionLabel(stringResource(R.string.downloading), downloading.size)
                }
                items(downloading, key = { "d${it.id}" }) { episode ->
                    DownloadRow(
                        episode = episode,
                        podcastTitle = podcastsById[episode.podcastId]?.title,
                        statusLine = "${episode.downloadProgress}%",
                        progress = episode.downloadProgress / 100f,
                        primaryLabel = stringResource(R.string.cancel),
                        onPrimary = { DownloadWorker.cancel(context, episode.id) }
                    )
                }
            }
            if (waiting.isNotEmpty()) {
                item(key = "h-waiting") {
                    SectionLabel(stringResource(R.string.waiting), waiting.size)
                }
                items(waiting, key = { "w${it.id}" }) { episode ->
                    DownloadRow(
                        episode = episode,
                        podcastTitle = podcastsById[episode.podcastId]?.title,
                        statusLine = if (waitingForWifi) {
                            stringResource(R.string.waiting_for_wifi)
                        } else {
                            stringResource(R.string.waiting_for_slot)
                        },
                        progress = null,
                        primaryLabel = stringResource(R.string.cancel),
                        onPrimary = { DownloadWorker.cancel(context, episode.id) }
                    )
                }
            }
            if (failed.isNotEmpty()) {
                item(key = "h-failed") {
                    SectionLabel(stringResource(R.string.failed), failed.size)
                }
                items(failed, key = { "f${it.id}" }) { episode ->
                    DownloadRow(
                        episode = episode,
                        podcastTitle = podcastsById[episode.podcastId]?.title,
                        statusLine = buildString {
                            val attempts = episode.downloadAttempts.coerceAtLeast(1)
                            append(
                                pluralStringResource(
                                    R.plurals.attempts_count, attempts, attempts
                                )
                            )
                            if (episode.downloadAttempts >=
                                Episode.MAX_AUTO_DOWNLOAD_ATTEMPTS
                            ) {
                                append(" · " + stringResource(R.string.auto_retry_stopped))
                            }
                        },
                        statusIsError = true,
                        progress = null,
                        primaryLabel = stringResource(R.string.retry),
                        onPrimary = { DownloadWorker.start(context, episode.id) },
                        secondaryLabel = stringResource(R.string.dismiss),
                        onSecondary = {
                            scope.launch { repository.dismissDownload(episode.id) }
                        }
                    )
                }
            }
        }
    }

    if (confirmCancelAll) {
        val count = downloading.size + waiting.size
        AlertDialog(
            onDismissRequest = { confirmCancelAll = false },
            title = {
                Text(
                    pluralStringResource(
                        R.plurals.cancel_downloads_question, count, count
                    )
                )
            },
            text = { Text(stringResource(R.string.everything_currently_downloading_or_waitin)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancelAll = false
                    (downloading + waiting).forEach {
                        DownloadWorker.cancel(context, it.id)
                    }
                }) { Text(stringResource(R.string.cancel_downloads)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancelAll = false }) {
                    Text(stringResource(R.string.keep_downloading))
                }
            }
        )
    }
}

@Composable
private fun SectionLabel(title: String, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp)
    ) {
        StepMark()
        Text(
            "$title · $count",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun DownloadRow(
    episode: Episode,
    podcastTitle: String?,
    statusLine: String,
    progress: Float?,
    primaryLabel: String,
    onPrimary: () -> Unit,
    statusIsError: Boolean = false,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    episode.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOfNotNull(podcastTitle, statusLine).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (statusIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onPrimary) { Text(primaryLabel) }
            if (secondaryLabel != null && onSecondary != null) {
                TextButton(onClick = onSecondary) { Text(secondaryLabel) }
            }
        }
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}
