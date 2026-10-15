package com.stepcast.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stepcast.app.R
import com.stepcast.app.data.PodcastRepository
import com.stepcast.app.data.StoragePlanner
import kotlinx.coroutines.launch

/**
 * What the download rules add up to against the storage limit, and the
 * changes listening history suggests — each one tap to apply. See
 * [StoragePlanner] for the rules.
 */
@Composable
fun StoragePlannerDialog(repository: PodcastRepository, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<StoragePlanner.Plan?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(reload) { plan = runCatching { repository.storagePlan() }.getOrNull() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.storage_planner)) },
        text = {
            val p = plan
            if (p == null) {
                Text(stringResource(R.string.measuring))
                return@AlertDialog
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.planner_using, bytesLabel(p.usedBytes)),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    stringResource(R.string.planner_rules_need, bytesLabel(p.plannedBytes)),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    if (p.capBytes > 0) {
                        stringResource(R.string.planner_limit, bytesLabel(p.capBytes))
                    } else {
                        stringResource(R.string.planner_no_limit)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                val status = when {
                    p.overCap -> stringResource(R.string.planner_status_paused)
                    p.rulesExceedCap -> stringResource(R.string.planner_status_will_fill)
                    else -> null
                }
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                if (p.suggestions.isEmpty()) {
                    Text(
                        stringResource(R.string.planner_no_suggestions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        stringResource(
                            R.string.planner_if_all,
                            bytesLabel(p.plannedAfterAll.coerceAtLeast(0))
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    for (s in p.suggestions) SuggestionRow(s, enabled = !busy) {
                        busy = true
                        scope.launch {
                            runCatching { repository.applyStorageSuggestion(s) }
                            busy = false
                            reload++
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}

@Composable
private fun SuggestionRow(s: StoragePlanner.Suggestion, enabled: Boolean, onApply: () -> Unit) {
    val reason = when (s.kind) {
        StoragePlanner.Kind.STOP ->
            if (s.show.lastListenedMs > 0) {
                stringResource(
                    R.string.planner_reason_stale,
                    android.text.format.DateUtils
                        .getRelativeTimeSpanString(s.show.lastListenedMs).toString()
                )
            } else {
                stringResource(R.string.planner_reason_never)
            }
        StoragePlanner.Kind.REDUCE -> stringResource(
            R.string.planner_reason_rate,
            s.show.finishedRecently, StoragePlanner.RATE_DAYS, s.show.keepDownloads
        )
    }
    val action = if (s.newKeep == 0) {
        stringResource(R.string.planner_action_stop)
    } else {
        stringResource(R.string.planner_action_keep, s.show.keepDownloads, s.newKeep)
    }
    val saving = buildList {
        if (s.freesNowBytes > 0) add(stringResource(R.string.planner_frees_now, bytesLabel(s.freesNowBytes)))
        if (s.plannedSavingBytes > 0) {
            add(stringResource(R.string.planner_saves_planned, bytesLabel(s.plannedSavingBytes)))
        }
    }.joinToString(" · ")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                s.show.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(action, style = MaterialTheme.typography.bodyMedium)
            Text(
                "$reason · $saving",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onApply, enabled = enabled) { Text(stringResource(R.string.apply)) }
    }
}

private fun bytesLabel(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.0f MB".format(bytes / 1_048_576.0)
    else -> "%.0f KB".format(bytes / 1024.0)
}
