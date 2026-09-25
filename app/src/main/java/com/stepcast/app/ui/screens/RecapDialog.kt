package com.stepcast.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stepcast.app.R
import com.stepcast.app.data.ListenStats
import com.stepcast.app.data.PodcastRepository

/**
 * "Your year in listening": totals, finished episodes, streak, top shows
 * and a month-by-month bar strip, from the per-day listening table. Years
 * before the table existed (pre-v24) only show finished-episode counts.
 */
@Composable
fun RecapDialog(repository: PodcastRepository, onDismiss: () -> Unit) {
    val thisYear = remember { java.time.LocalDate.now().year }
    var year by remember { mutableIntStateOf(thisYear) }
    var recap by remember { mutableStateOf<PodcastRepository.YearRecap?>(null) }
    LaunchedEffect(year) {
        recap = null
        recap = runCatching { repository.yearRecap(year) }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recap_title, year)) },
        text = {
            val r = recap
            if (r == null) {
                Text(stringResource(R.string.loading))
                return@AlertDialog
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    ListenStats.formatDuration(r.wallMs),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(R.string.recap_listened),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val saved = (r.contentMs - r.wallMs).coerceAtLeast(0)
                Text(
                    pluralStringResource(
                        R.plurals.recap_finished, r.episodesFinished, r.episodesFinished
                    ) + " · " +
                        pluralStringResource(R.plurals.recap_days, r.activeDays, r.activeDays),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp)
                )
                if (r.longestStreakDays > 1) {
                    Text(
                        pluralStringResource(
                            R.plurals.recap_streak, r.longestStreakDays, r.longestStreakDays
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (saved > 60_000) {
                    Text(
                        stringResource(R.string.recap_saved, ListenStats.formatDuration(saved)),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (r.byMonth.isNotEmpty()) {
                    MonthBars(r.byMonth)
                }
                if (r.topShows.isNotEmpty()) {
                    Text(
                        stringResource(R.string.recap_top_shows),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                    )
                    r.topShows.forEachIndexed { i, (podcast, ms) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(
                                "${i + 1}. ${podcast.title}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                ListenStats.formatDuration(ms),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (r.wallMs == 0L) {
                    Text(
                        stringResource(R.string.recap_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
        dismissButton = {
            if (year > thisYear - 5) {
                TextButton(onClick = { year -= 1 }) { Text((year - 1).toString()) }
            }
        }
    )
}

/** Twelve bars, tallest month = full height; the busiest month is labelled. */
@Composable
private fun MonthBars(byMonth: Map<Int, Long>) {
    val max = byMonth.values.maxOrNull()?.takeIf { it > 0 } ?: return
    val busiest = byMonth.maxByOrNull { it.value }?.key
    Column(Modifier.padding(top = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().height(64.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            for (m in 1..12) {
                val fraction = ((byMonth[m] ?: 0L).toFloat() / max).coerceIn(0.03f, 1f)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(fraction)
                        .background(
                            if (m == busiest) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                            },
                            RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
                        )
                )
            }
        }
        busiest?.let {
            Text(
                stringResource(
                    R.string.recap_busiest_month,
                    java.time.Month.of(it).getDisplayName(
                        java.time.format.TextStyle.FULL, java.util.Locale.getDefault()
                    )
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
