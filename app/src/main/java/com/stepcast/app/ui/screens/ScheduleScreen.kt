package com.stepcast.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stepcast.app.R
import com.stepcast.app.data.AppSettings
import com.stepcast.app.data.CategoryMeta
import com.stepcast.app.data.Podcast
import com.stepcast.app.data.PodcastRepository
import com.stepcast.app.sync.RefreshSchedule
import com.stepcast.app.ui.theme.ScreenTitle
import com.stepcast.app.ui.theme.StepMark

/**
 * Read-first overview of every refresh cadence and download rule in one
 * place — the "schedule" the app runs on. Refresh cadence is per category
 * (with the global default shown first); download retention is per show.
 * Tapping a row jumps to the category or podcast screen where it's edited;
 * nothing here mutates state directly.
 */
@Composable
fun ScheduleScreen(
    repository: PodcastRepository,
    onOpenCategory: (String) -> Unit,
    onOpenPodcast: (Long) -> Unit
) {
    val categoryMetas by repository.categoryMetas.collectAsState(initial = emptyList())
    val podcasts by repository.podcasts.collectAsState(initial = emptyList())
    val memberships by repository.podcastCategories.collectAsState(initial = emptyList())
    val defaultRefreshHours = AppSettings.defaultRefreshHours
    val defaultKeepDownloads = AppSettings.defaultKeepDownloads

    val memberCount = remember(memberships) {
        memberships.groupingBy { it.category }.eachCount()
    }
    val sortedCats = remember(categoryMetas) { categoryMetas.sortedBy { it.sortOrder } }
    val sortedPods = remember(podcasts) {
        podcasts.sortedBy { it.title.lowercase() }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle(
            stringResource(R.string.schedule),
            modifier = Modifier.padding(start = 16.dp, top = 4.dp)
        )
        Text(
            stringResource(R.string.schedule_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { ScheduleSectionHeader(stringResource(R.string.schedule_refresh_cadence)) }
            item {
                ScheduleRow(
                    title = stringResource(R.string.schedule_default),
                    detail = stringResource(R.string.schedule_every_hours, defaultRefreshHours)
                )
            }
            items(sortedCats, key = { it.name }) { meta ->
                val shows = pluralStringResource(
                    R.plurals.schedule_shows,
                    memberCount[meta.name] ?: 0,
                    memberCount[meta.name] ?: 0
                )
                ScheduleRow(
                    title = meta.name,
                    detail = cadenceText(meta, defaultRefreshHours) + "  ·  " + shows,
                    onClick = { onOpenCategory(meta.name) }
                )
            }

            item { ScheduleSectionHeader(stringResource(R.string.schedule_downloads)) }
            item {
                ScheduleRow(
                    title = stringResource(R.string.schedule_default),
                    detail = if (defaultKeepDownloads == 0) {
                        stringResource(R.string.schedule_autodownload_off)
                    } else {
                        stringResource(R.string.schedule_keep_n, defaultKeepDownloads)
                    }
                )
            }
            items(sortedPods, key = { it.id }) { podcast ->
                ScheduleRow(
                    title = podcast.title,
                    detail = retentionText(podcast),
                    onClick = { onOpenPodcast(podcast.id) }
                )
            }
        }
    }
}

@Composable
private fun cadenceText(meta: CategoryMeta, defaultHours: Int): String =
    when {
        meta.refreshHours == 0 ->
            stringResource(R.string.schedule_app_default_hours, defaultHours)
        meta.anchorMinutes >= 0 ->
            stringResource(
                R.string.schedule_every_hours_at,
                meta.refreshHours,
                RefreshSchedule.formatAnchor(meta.anchorMinutes)
            )
        else ->
            stringResource(R.string.schedule_every_hours, meta.refreshHours)
    }

@Composable
private fun retentionText(podcast: Podcast): String {
    val parts = mutableListOf<String>()
    parts += if (podcast.keepDownloads == 0) {
        stringResource(R.string.schedule_autodownload_off)
    } else {
        stringResource(R.string.schedule_keep_n, podcast.keepDownloads)
    }
    if (podcast.maxAgeDays > 0) {
        parts += stringResource(R.string.schedule_max_age_d, podcast.maxAgeDays)
    }
    if (podcast.episodeCap > 0) {
        parts += stringResource(R.string.schedule_cap_n, podcast.episodeCap)
    }
    if (podcast.autoQueue) {
        parts += stringResource(R.string.schedule_auto_queue)
    }
    return parts.joinToString("  ·  ")
}

@Composable
private fun ScheduleSectionHeader(title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
    ) {
        StepMark()
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.tertiary
        )
    }
}

@Composable
private fun ScheduleRow(title: String, detail: String, onClick: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = if (onClick != null) {
            Modifier.fillMaxWidth().clickable(onClick = onClick)
        } else {
            Modifier.fillMaxWidth()
        }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onClick != null) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
