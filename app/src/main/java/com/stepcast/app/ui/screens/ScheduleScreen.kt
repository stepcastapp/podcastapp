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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stepcast.app.R
import com.stepcast.app.data.AppSettings
import com.stepcast.app.data.Podcast
import com.stepcast.app.data.PodcastRepository
import com.stepcast.app.sync.RefreshSchedule
import com.stepcast.app.sync.RefreshWorker
import com.stepcast.app.sync.ReleasePattern
import com.stepcast.app.sync.ScheduleEngine
import com.stepcast.app.ui.theme.ScreenTitle
import com.stepcast.app.ui.theme.StepMark
import java.text.DateFormat
import java.text.DateFormatSymbols
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class TimelineRow(val timeMs: Long, val label: String)

/**
 * The schedule as PROMISES with clock times: a "next checks" timeline
 * (when + why), the "fresh by" checkpoints, quiet hours, and each show's
 * refresh rule with its inferred release pattern. Every element answers
 * "what will happen, and when" — the answer to "refresh every N hours
 * from... what, exactly?"
 */
@Composable
fun ScheduleScreen(
    repository: PodcastRepository,
    onOpenPodcast: (Long) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val podcasts by repository.podcasts.collectAsState(initial = emptyList())
    val sortedPods = remember(podcasts) { podcasts.sortedBy { it.title.lowercase() } }

    val checkpointTimes = AppSettings.checkpointTimes
    val checkpointEnabled = AppSettings.checkpointEnabled
    val quietOn = AppSettings.quietHoursEnabled
    val quietStart = AppSettings.quietStartMinutes
    val quietEnd = AppSettings.quietEndMinutes

    // inferred release patterns, computed off-main once per data change
    var patterns by remember {
        mutableStateOf<Map<Long, ReleasePattern.Pattern>>(emptyMap())
    }
    LaunchedEffect(podcasts) {
        patterns = withContext(Dispatchers.IO) {
            podcasts.filter { it.localFolderUri == null }.associate { podcast ->
                podcast.id to ReleasePattern.infer(
                    repository.recentPubDates(podcast.id)
                )
            }
        }
    }

    // the "next checks" timeline: soonest upcoming events, deduped so all
    // checkpoint-driven shows collapse into one row per checkpoint
    var timeline by remember { mutableStateOf<List<TimelineRow>>(emptyList()) }
    val checkpointNames = listOf(
        stringResource(R.string.checkpoint_morning),
        stringResource(R.string.checkpoint_midday),
        stringResource(R.string.checkpoint_evening),
        stringResource(R.string.checkpoint_night)
    )
    val reasonRelease = stringResource(R.string.reason_release)
    val reasonHourly = stringResource(R.string.reason_hourly)
    val reasonDaily = stringResource(R.string.reason_daily)
    val reasonWeekly = stringResource(R.string.reason_weekly)
    val reasonBaseline = stringResource(R.string.reason_baseline)
    val reasonCheckpoint = stringResource(R.string.reason_checkpoint)
    LaunchedEffect(
        podcasts, patterns, checkpointTimes, checkpointEnabled,
        quietOn, quietStart, quietEnd
    ) {
        timeline = withContext(Dispatchers.IO) {
            val cfg = ScheduleEngine.Config(
                checkpointMinutes = AppSettings.enabledCheckpointMinutes(),
                quietEnabled = AppSettings.quietHoursEnabled,
                quietStartMinutes = AppSettings.quietStartMinutes,
                quietEndMinutes = AppSettings.quietEndMinutes
            )
            val now = System.currentTimeMillis()
            val autoCount = podcasts.count {
                it.scheduleMode == ScheduleEngine.MODE_AUTO
            }
            val rows = mutableListOf<TimelineRow>()
            // one row per enabled checkpoint (they cover every Automatic show)
            AppSettings.checkpointTimes.forEachIndexed { i, minutes ->
                if (AppSettings.checkpointEnabled.getOrElse(i) { false }) {
                    // a checkpoint inside quiet hours actually fires at the
                    // quiet end — show the time the engine will use
                    val slot = ScheduleEngine.quietEndAfter(
                        RefreshSchedule.latestSlotMs(minutes, 24, now) + 86_400_000L,
                        cfg,
                        java.util.TimeZone.getDefault()
                    )
                    rows += TimelineRow(
                        slot,
                        reasonCheckpoint
                            .replace("%1\$s", checkpointNames[i])
                            .replace(
                                "%2\$s",
                                context.resources.getQuantityString(
                                    R.plurals.n_shows, autoCount, autoCount
                                )
                            )
                    )
                }
            }
            // per-show events: releases + pinned rules (skip checkpoint/baseline
            // reasons here — the checkpoint rows above already cover them)
            podcasts.forEach { podcast ->
                val pattern = patterns[podcast.id]
                val expected = if (
                    podcast.scheduleMode == ScheduleEngine.MODE_AUTO &&
                    pattern != null
                ) {
                    ReleasePattern.nextExpectedMs(
                        pattern,
                        repository.recentPubDates(podcast.id).firstOrNull() ?: 0L,
                        now
                    )
                } else {
                    null
                }
                val next = ScheduleEngine.nextCheck(
                    podcast.scheduleMode, podcast.scheduleParam,
                    podcast.lastRefreshed, expected, now, cfg
                ) ?: return@forEach
                val label = when (next.reason) {
                    ScheduleEngine.Reason.RELEASE ->
                        reasonRelease.replace("%1\$s", podcast.title)
                    ScheduleEngine.Reason.HOURLY ->
                        reasonHourly.replace("%1\$s", podcast.title)
                    ScheduleEngine.Reason.DAILY ->
                        reasonDaily.replace("%1\$s", podcast.title)
                    ScheduleEngine.Reason.WEEKLY ->
                        reasonWeekly.replace("%1\$s", podcast.title)
                    ScheduleEngine.Reason.BASELINE ->
                        reasonBaseline.replace("%1\$s", podcast.title)
                    ScheduleEngine.Reason.CHECKPOINT -> return@forEach
                }
                rows += TimelineRow(next.timeMs, label)
            }
            rows.sortedBy { it.timeMs }.take(8)
        }
    }

    var ruleFor by remember { mutableStateOf<Podcast?>(null) }
    var checkpointEdit by remember { mutableStateOf(-1) }
    var quietEdit by remember { mutableStateOf(false) }

    val timeFmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

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
            item { ScheduleHeader(stringResource(R.string.schedule_next_checks)) }
            itemsIndexed(
                timeline,
                key = { index, row -> "${row.timeMs}-${row.label}-$index" }
            ) { _, row ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    Text(
                        timeFmt.format(Date(row.timeMs)),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(76.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            android.text.format.DateUtils.getRelativeTimeSpanString(
                                row.timeMs,
                                System.currentTimeMillis(),
                                android.text.format.DateUtils.MINUTE_IN_MILLIS
                            ).toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { ScheduleHeader(stringResource(R.string.schedule_fresh_by)) }
            items(checkpointTimes.indices.toList(), key = { "cp$it" }) { i ->
                ScheduleCard(onClick = { checkpointEdit = i }) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            checkpointNames[i],
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            RefreshSchedule.formatAnchor(checkpointTimes[i]),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = checkpointEnabled.getOrElse(i) { false },
                        onCheckedChange = {
                            AppSettings.setCheckpointEnabled(context, i, it)
                            RefreshWorker.replan(context)
                        }
                    )
                }
            }

            item { ScheduleHeader(stringResource(R.string.schedule_quiet_hours)) }
            item {
                ScheduleCard(onClick = { quietEdit = true }) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(
                                R.string.schedule_quiet_range,
                                RefreshSchedule.formatAnchor(quietStart),
                                RefreshSchedule.formatAnchor(quietEnd)
                            ),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            stringResource(R.string.schedule_quiet_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = quietOn,
                        onCheckedChange = {
                            AppSettings.setQuietHoursEnabled(context, it)
                            RefreshWorker.replan(context)
                        }
                    )
                }
            }

            item { ScheduleHeader(stringResource(R.string.schedule_show_rules)) }
            items(sortedPods, key = { it.id }) { podcast ->
                val isFolder = podcast.localFolderUri != null
                // local folders: no rule dialog — the worker rescans them at
                // most daily regardless, so offering Hourly/Manual was a
                // promise the app never kept. No retention line either
                // (auto-download rules skip folders entirely).
                ScheduleCard(
                    onClick = if (isFolder) ({}) else ({ ruleFor = podcast })
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            podcast.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (isFolder) {
                                stringResource(R.string.local_folder_rescans_daily)
                            } else {
                                ruleText(podcast) + "  ·  " +
                                    patternText(patterns[podcast.id])
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!isFolder) {
                            Text(
                                retentionText(podcast),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (checkpointEdit >= 0) {
        val i = checkpointEdit
        TimeDialog(
            title = checkpointNames[i],
            initial = RefreshSchedule.formatAnchor(checkpointTimes[i]),
            onDismiss = { checkpointEdit = -1 },
            onSave = { minutes ->
                AppSettings.setCheckpointTime(context, i, minutes)
                RefreshWorker.replan(context)
                checkpointEdit = -1
            }
        )
    }

    if (quietEdit) {
        QuietDialog(
            initialStart = RefreshSchedule.formatAnchor(quietStart),
            initialEnd = RefreshSchedule.formatAnchor(quietEnd),
            onDismiss = { quietEdit = false },
            onSave = { start, end ->
                AppSettings.setQuietHours(context, start, end)
                RefreshWorker.replan(context)
                quietEdit = false
            }
        )
    }

    ruleFor?.let { podcast ->
        RuleDialog(
            podcast = podcast,
            patternHint = patternText(patterns[podcast.id]),
            onDismiss = { ruleFor = null },
            onOpenShow = { ruleFor = null; onOpenPodcast(podcast.id) },
            onSave = { mode, param ->
                scope.launch {
                    repository.setScheduleRule(podcast.id, mode, param)
                    RefreshWorker.replan(context)
                }
                ruleFor = null
            }
        )
    }
}

@Composable
private fun ScheduleHeader(title: String) {
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
private fun ScheduleCard(
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
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
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            content = content
        )
    }
}

@Composable
private fun ruleText(podcast: Podcast): String = when (podcast.scheduleMode) {
    ScheduleEngine.MODE_HOURLY -> stringResource(R.string.rule_hourly)
    ScheduleEngine.MODE_DAILY_AT -> stringResource(
        R.string.rule_daily_at,
        RefreshSchedule.formatAnchor(podcast.scheduleParam)
    )
    ScheduleEngine.MODE_WEEKLY_AT -> stringResource(
        R.string.rule_weekly_at,
        isoDayName(podcast.scheduleParam / 1440),
        RefreshSchedule.formatAnchor(podcast.scheduleParam % 1440)
    )
    ScheduleEngine.MODE_MANUAL -> stringResource(R.string.rule_manual)
    else -> stringResource(R.string.rule_auto)
}

@Composable
private fun patternText(pattern: ReleasePattern.Pattern?): String = when {
    pattern == null -> stringResource(R.string.pattern_none)
    pattern.kind == ReleasePattern.Kind.DAILY &&
        pattern.daysOfWeek == setOf(1, 2, 3, 4, 5) -> stringResource(
        R.string.pattern_weekdays, RefreshSchedule.formatAnchor(pattern.minutesOfDay)
    )
    pattern.kind == ReleasePattern.Kind.DAILY -> stringResource(
        R.string.pattern_daily, RefreshSchedule.formatAnchor(pattern.minutesOfDay)
    )
    pattern.kind == ReleasePattern.Kind.WEEKLY -> stringResource(
        R.string.pattern_weekly,
        isoDayName(pattern.daysOfWeek.firstOrNull() ?: 1),
        RefreshSchedule.formatAnchor(pattern.minutesOfDay)
    )
    pattern.kind == ReleasePattern.Kind.SPARSE ->
        stringResource(R.string.pattern_sparse)
    else -> stringResource(R.string.pattern_none)
}

private fun isoDayName(isoDow: Int): String {
    val calIdx = (isoDow.coerceIn(1, 7) % 7) + 1 // ISO Mon=1..Sun=7 -> SUN=1
    return DateFormatSymbols().shortWeekdays.getOrElse(calIdx) { "?" }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit
) {
    // structured time input instead of a hand-typed H:MM field with a
    // silently-greyed Save
    val context = LocalContext.current
    val initialMinutes = RefreshSchedule.parseAnchor(initial) ?: (6 * 60 + 30)
    val state = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        is24Hour = android.text.format.DateFormat.is24HourFormat(context)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimeInput(state = state) },
        confirmButton = {
            TextButton(
                onClick = { onSave(state.hour * 60 + state.minute) }
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietDialog(
    initialStart: String,
    initialEnd: String,
    onDismiss: () -> Unit,
    onSave: (Int, Int) -> Unit
) {
    val context = LocalContext.current
    val is24h = android.text.format.DateFormat.is24HourFormat(context)
    val startMinutes = RefreshSchedule.parseAnchor(initialStart) ?: 1380
    val endMinutes = RefreshSchedule.parseAnchor(initialEnd) ?: 360
    val startState = rememberTimePickerState(
        initialHour = startMinutes / 60,
        initialMinute = startMinutes % 60,
        is24Hour = is24h
    )
    val endState = rememberTimePickerState(
        initialHour = endMinutes / 60,
        initialMinute = endMinutes % 60,
        is24Hour = is24h
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.schedule_quiet_hours)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.schedule_quiet_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.quiet_from),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
                TimeInput(state = startState)
                Text(
                    stringResource(R.string.quiet_until),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                TimeInput(state = endState)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        startState.hour * 60 + startState.minute,
                        endState.hour * 60 + endState.minute
                    )
                }
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleDialog(
    podcast: Podcast,
    patternHint: String,
    onDismiss: () -> Unit,
    onOpenShow: () -> Unit,
    onSave: (Int, Int) -> Unit
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(podcast.scheduleMode) }
    val initialTimeMinutes = when (podcast.scheduleMode) {
        ScheduleEngine.MODE_DAILY_AT -> podcast.scheduleParam
        ScheduleEngine.MODE_WEEKLY_AT -> podcast.scheduleParam % 1440
        else -> 7 * 60
    }.coerceIn(0, 1439)
    val timeState = rememberTimePickerState(
        initialHour = initialTimeMinutes / 60,
        initialMinute = initialTimeMinutes % 60,
        is24Hour = android.text.format.DateFormat.is24HourFormat(context)
    )
    var weekday by remember {
        mutableStateOf(
            if (podcast.scheduleMode == ScheduleEngine.MODE_WEEKLY_AT) {
                (podcast.scheduleParam / 1440).coerceIn(1, 7)
            } else {
                1
            }
        )
    }
    val pickedMinutes = timeState.hour * 60 + timeState.minute
    val needsTime = mode == ScheduleEngine.MODE_DAILY_AT ||
        mode == ScheduleEngine.MODE_WEEKLY_AT

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                podcast.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column {
                RuleOption(
                    label = stringResource(R.string.rule_auto),
                    hint = stringResource(R.string.rule_auto_hint) +
                        " · " + patternHint,
                    selected = mode == ScheduleEngine.MODE_AUTO,
                    onClick = { mode = ScheduleEngine.MODE_AUTO }
                )
                RuleOption(
                    label = stringResource(R.string.rule_hourly),
                    hint = stringResource(R.string.rule_hourly_hint),
                    selected = mode == ScheduleEngine.MODE_HOURLY,
                    onClick = { mode = ScheduleEngine.MODE_HOURLY }
                )
                RuleOption(
                    label = stringResource(R.string.rule_daily),
                    hint = stringResource(R.string.rule_daily_hint),
                    selected = mode == ScheduleEngine.MODE_DAILY_AT,
                    onClick = { mode = ScheduleEngine.MODE_DAILY_AT }
                )
                RuleOption(
                    label = stringResource(R.string.rule_weekly),
                    hint = stringResource(R.string.rule_weekly_hint),
                    selected = mode == ScheduleEngine.MODE_WEEKLY_AT,
                    onClick = { mode = ScheduleEngine.MODE_WEEKLY_AT }
                )
                RuleOption(
                    label = stringResource(R.string.rule_manual),
                    hint = stringResource(R.string.rule_manual_hint),
                    selected = mode == ScheduleEngine.MODE_MANUAL,
                    onClick = { mode = ScheduleEngine.MODE_MANUAL }
                )
                if (mode == ScheduleEngine.MODE_WEEKLY_AT) {
                    Row(Modifier.padding(top = 8.dp)) {
                        (1..7).forEach { dow ->
                            val selected = weekday == dow
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .clickable { weekday = dow }
                            ) {
                                Text(
                                    isoDayName(dow),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(
                                        horizontal = 6.dp, vertical = 6.dp
                                    )
                                )
                            }
                        }
                    }
                }
                if (needsTime) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.padding(top = 8.dp)
                    ) {
                        TimeInput(state = timeState)
                    }
                }
                TextButton(onClick = onOpenShow) {
                    Text(stringResource(R.string.open_show_settings))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val param = when (mode) {
                        ScheduleEngine.MODE_DAILY_AT -> pickedMinutes
                        ScheduleEngine.MODE_WEEKLY_AT ->
                            weekday * 1440 + pickedMinutes
                        else -> 0
                    }
                    onSave(mode, param)
                }
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun RuleOption(
    label: String,
    hint: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            androidx.compose.ui.graphics.Color.Transparent
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
