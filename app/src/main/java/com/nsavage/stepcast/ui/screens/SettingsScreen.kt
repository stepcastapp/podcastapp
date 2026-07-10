package com.nsavage.stepcast.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import com.nsavage.stepcast.R
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nsavage.stepcast.data.AppSettings
import com.nsavage.stepcast.data.BeyondPodImport
import com.nsavage.stepcast.data.Opml
import com.nsavage.stepcast.data.PodcastRepository
import com.nsavage.stepcast.sync.RefreshWorker
import com.nsavage.stepcast.ui.theme.AccentColor
import com.nsavage.stepcast.ui.theme.ScreenTitle
import com.nsavage.stepcast.ui.theme.StepMark
import com.nsavage.stepcast.ui.theme.ThemeMode
import com.nsavage.stepcast.ui.theme.ThemePrefs
import com.nsavage.stepcast.ui.theme.accentSwatch
import com.nsavage.stepcast.widget.updateAllStepcastWidgets
import androidx.compose.ui.res.pluralStringResource

/**
 * Dedicated settings home: appearance, playback behavior, and feed/download
 * defaults. Every value applies immediately (playback seek increments apply
 * on the next playback start, since the player is built with them).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(repository: PodcastRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val categoryMetas by repository.categoryMetas.collectAsState(initial = emptyList())
    var reorderDialogOpen by remember { mutableStateOf(false) }
    var diagnosticsOpen by remember { mutableStateOf(false) }
    var bpImporting by remember { mutableStateOf(false) }
    var bpResult by remember { mutableStateOf<String?>(null) }
    var backupResult by remember { mutableStateOf<String?>(null) }
    var storageOpen by remember { mutableStateOf(false) }

    // which settings sections are folded shut (session-only; all start open)
    val collapsedSections = remember {
        androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    }
    fun sectionOpen(name: String) = collapsedSections[name] != true
    fun toggleSection(name: String) {
        collapsedSections[name] = sectionOpen(name)
    }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            backupResult = try {
                com.nsavage.stepcast.data.StepcastBackup.export(context, repository, uri)
                context.getString(R.string.backup_saved)
            } catch (e: Exception) {
                context.getString(R.string.backup_failed, e.message ?: "")
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            backupResult = try {
                val s = com.nsavage.stepcast.data.StepcastBackup
                    .import(context, repository, uri)
                RefreshWorker.refreshNow(context)
                context.getString(
                    R.string.restore_summary, s.feeds, s.categories, s.smartPlays
                )
            } catch (e: Exception) {
                context.getString(R.string.restore_failed, e.message ?: "")
            }
        }
    }

    val localFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(
            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        scope.launch {
            try {
                repository.addLocalFolder(uri)
                Toast.makeText(
                    context,
                    context.getString(R.string.local_folder_added),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    context.getString(R.string.couldnt_read_folder, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val importOpmlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val urls = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use(Opml::parse)
                }.getOrNull().orEmpty()
            }
            val count = repository.subscribeAll(urls)
            Toast.makeText(
                context,
                context.getString(R.string.imported_n_of_m_feeds, count, urls.size),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val exportOpmlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/xml")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val xml = Opml.serialize(
                repository.allPodcasts(),
                repository.podcastCategoryList()
                    .groupBy({ it.podcastId }, { it.category })
            )
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(xml.toByteArray())
                    }
                }.isSuccess
            }
            Toast.makeText(
                context,
                context.getString(
                    if (ok) R.string.subscriptions_exported else R.string.export_failed
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val autoBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        AppSettings.setAutoBackupFolder(context, uri.toString())
        com.nsavage.stepcast.sync.AutoBackupWorker.schedule(context)
        backupResult = context.getString(R.string.weekly_auto_backup_enabled_msg)
    }

    val beyondPodLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            bpImporting = true
            bpResult = null
            bpResult = try {
                val s = BeyondPodImport.import(context, repository, uri)
                RefreshWorker.refreshNow(context)
                context.getString(
                    R.string.beyondpod_import_summary,
                    s.feeds, s.categories, s.smartPlays, s.refreshRules
                )
            } catch (e: Exception) {
                context.getString(R.string.import_failed, e.message ?: "")
            }
            bpImporting = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        ScreenTitle(stringResource(R.string.settings), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))

        // ---- Appearance -------------------------------------------------
        SectionHeader(stringResource(R.string.appearance), sectionOpen("Appearance")) {
            toggleSection("Appearance")
        }
        if (sectionOpen("Appearance")) {
        Text(
            stringResource(R.string.theme),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (mode in ThemeMode.entries) {
                val label = stringResource(
                    when (mode) {
                        ThemeMode.SYSTEM -> R.string.theme_system
                        ThemeMode.LIGHT -> R.string.theme_light
                        ThemeMode.DARK -> R.string.theme_dark
                    }
                )
                FilterChip(
                    selected = ThemePrefs.mode == mode,
                    onClick = { ThemePrefs.set(context, mode) },
                    label = { Text(label) }
                )
            }
        }
        Text(
            stringResource(R.string.accent_color),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 14.dp, bottom = 8.dp)
        )
        var wheelOpen by remember { mutableStateOf(false) }
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            for (accent in AccentColor.entries) {
                if (accent == AccentColor.DYNAMIC &&
                    !com.nsavage.stepcast.ui.theme.dynamicAccentAvailable()
                ) {
                    continue
                }
                androidx.compose.foundation.layout.Box(
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(accentSwatch(accent, isDark))
                        .border(
                            width = if (ThemePrefs.accent == accent) 3.dp else 1.dp,
                            color = if (ThemePrefs.accent == accent) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape = CircleShape
                        )
                        .clickable {
                            // the Custom dot opens the wheel; the rest apply
                            if (accent == AccentColor.CUSTOM) {
                                wheelOpen = true
                            } else {
                                ThemePrefs.setAccent(context, accent)
                            }
                        }
                ) {
                    if (accent == AccentColor.CUSTOM) {
                        Icon(
                            Icons.Rounded.Colorize,
                            contentDescription = stringResource(R.string.pick_a_custom_color),
                            tint = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
        if (wheelOpen) {
            com.nsavage.stepcast.ui.theme.ColorWheelDialog(
                initialArgb = ThemePrefs.customAccentArgb,
                onDismiss = { wheelOpen = false },
                onPick = { argb ->
                    wheelOpen = false
                    ThemePrefs.setCustomAccent(context, argb)
                }
            )
        }
        Text(
            ThemePrefs.accent.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            stringResource(R.string.support_color),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 14.dp, bottom = 8.dp)
        )
        var secondaryWheelOpen by remember { mutableStateOf(false) }
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            // Auto first: the pairing that ships with the chosen primary
            val autoColor = com.nsavage.stepcast.ui.theme.secondarySwatch(
                com.nsavage.stepcast.ui.theme.pairedSecondaryAccent(), isDark
            )
            androidx.compose.foundation.layout.Box(
                contentAlignment = androidx.compose.ui.Alignment.Center,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(autoColor)
                    .border(
                        width = if (ThemePrefs.secondaryAccent == null) 3.dp else 1.dp,
                        color = if (ThemePrefs.secondaryAccent == null) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        shape = CircleShape
                    )
                    .clickable { ThemePrefs.setSecondaryAccent(context, null) }
            ) {
                Text(
                    "A",
                    style = MaterialTheme.typography.labelLarge,
                    color = androidx.compose.ui.graphics.Color.White
                )
            }
            for (accent in AccentColor.entries) {
                if (accent == AccentColor.DYNAMIC) continue
                androidx.compose.foundation.layout.Box(
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            com.nsavage.stepcast.ui.theme.secondarySwatch(accent, isDark)
                        )
                        .border(
                            width = if (ThemePrefs.secondaryAccent == accent) 3.dp else 1.dp,
                            color = if (ThemePrefs.secondaryAccent == accent) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape = CircleShape
                        )
                        .clickable {
                            if (accent == AccentColor.CUSTOM) {
                                secondaryWheelOpen = true
                            } else {
                                ThemePrefs.setSecondaryAccent(context, accent)
                            }
                        }
                ) {
                    if (accent == AccentColor.CUSTOM) {
                        Icon(
                            Icons.Rounded.Colorize,
                            contentDescription = stringResource(R.string.pick_a_custom_support_color),
                            tint = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
        if (secondaryWheelOpen) {
            com.nsavage.stepcast.ui.theme.ColorWheelDialog(
                initialArgb = ThemePrefs.customSecondaryArgb,
                onDismiss = { secondaryWheelOpen = false },
                onPick = { argb ->
                    secondaryWheelOpen = false
                    ThemePrefs.setCustomSecondaryAccent(context, argb)
                }
            )
        }
        Text(
            ThemePrefs.secondaryAccent?.label
                ?.let { stringResource(R.string.support_label, it) }
                ?: stringResource(
                    R.string.support_auto_pairing,
                    com.nsavage.stepcast.ui.theme.pairedSecondaryAccent().label,
                    ThemePrefs.accent.label
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            stringResource(R.string.widget_background),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            for (pct in listOf(100, 75, 50, 25, 0)) {
                FilterChip(
                    selected = AppSettings.widgetOpacity == pct,
                    onClick = {
                        AppSettings.setWidgetOpacity(context, pct)
                        scope.launch { updateAllStepcastWidgets(context) }
                    },
                    label = {
                        Text(
                            if (pct == 0) {
                                stringResource(R.string.opacity_clear)
                            } else {
                                "$pct%"
                            }
                        )
                    }
                )
            }
        }
        }

        SectionDivider()

        // ---- Playback ---------------------------------------------------
        SectionHeader(stringResource(R.string.playback), sectionOpen("Playback")) {
            toggleSection("Playback")
        }
        if (sectionOpen("Playback")) {
        Text(
            stringResource(R.string.default_speed),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            stringResource(R.string.shows_with_their_own_speed_override_this),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            for (choice in listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.8f, 2.0f, 2.5f, 3.0f)) {
                FilterChip(
                    selected = abs(AppSettings.defaultPlaybackSpeed - choice) < 0.05f,
                    onClick = { AppSettings.setDefaultPlaybackSpeed(context, choice) },
                    label = {
                        Text(
                            "${if (choice == choice.toLong().toFloat()) {
                                choice.toLong().toString()
                            } else {
                                choice.toString()
                            }}×",
                            fontFamily = FontFamily.Monospace
                        )
                    }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        NumberSetting(
            label = stringResource(R.string.seek_back),
            unit = stringResource(R.string.seconds),
            value = AppSettings.seekBackSeconds,
            hint = stringResource(R.string.s_5_120_applies_when_playback_next_starts),
            onCommit = { AppSettings.setSeekBackSeconds(context, it) }
        )
        NumberSetting(
            label = stringResource(R.string.seek_forward),
            unit = stringResource(R.string.seconds),
            value = AppSettings.seekForwardSeconds,
            hint = stringResource(R.string.s_5_300_applies_when_playback_next_starts),
            onCommit = { AppSettings.setSeekForwardSeconds(context, it) }
        )
        SwitchSetting(
            label = stringResource(R.string.auto_skip_ad_chapters),
            hint = stringResource(R.string.skips_chapters_titled_sponsor_ad_or_promo),
            checked = AppSettings.adChapterAutoSkip,
            onToggle = { AppSettings.setAdChapterAutoSkip(context, it) }
        )
        SwitchSetting(
            label = stringResource(R.string.trim_silence),
            hint = stringResource(R.string.skips_silent_gaps_in_speech_applies_from_t),
            checked = AppSettings.skipSilence,
            onToggle = { AppSettings.setSkipSilence(context, it) }
        )
        SwitchSetting(
            label = stringResource(R.string.done_button_in_media_notification),
            hint = stringResource(R.string.done_button_hint),
            checked = AppSettings.notificationDoneButton,
            onToggle = {
                AppSettings.setNotificationDoneButton(context, it)
                // applies to the live notification immediately
                context.sendBroadcast(
                    android.content.Intent(
                        context,
                        com.nsavage.stepcast.playback.CommandReceiver::class.java
                    ).setAction(
                        com.nsavage.stepcast.playback.CommandReceiver
                            .ACTION_REFRESH_NOTIF_BUTTONS
                    )
                )
            }
        )
        SwitchSetting(
            label = stringResource(R.string.stream_when_not_downloaded),
            hint = stringResource(R.string.off_tapping_an_undownloaded_episode_downlo),
            checked = AppSettings.streamWhenNotDownloaded,
            onToggle = { AppSettings.setStreamWhenNotDownloaded(context, it) }
        )
        }

        SectionDivider()

        // ---- Queue --------------------------------------------------------
        SectionHeader(stringResource(R.string.queue), sectionOpen("Queue")) {
            toggleSection("Queue")
        }
        if (sectionOpen("Queue")) {
        SwitchSetting(
            label = stringResource(R.string.swipe_adds_to_top_of_queue),
            hint = stringResource(R.string.off_swiped_episodes_go_to_the_end_of_up_ne),
            checked = AppSettings.swipeQueueToTop,
            onToggle = { AppSettings.setSwipeQueueToTop(context, it) }
        )
        SwitchSetting(
            label = stringResource(R.string.next_episode_at_the_bottom),
            hint = stringResource(R.string.flips_up_next_so_what_plays_next_sits_at_t),
            checked = AppSettings.queueNextAtBottom,
            onToggle = { AppSettings.setQueueNextAtBottom(context, it) }
        )
        SwitchSetting(
            label = stringResource(R.string.keep_playing_when_the_queue_ends),
            hint = stringResource(R.string.continues_with_the_current_show_s_next_unp),
            checked = AppSettings.continueCurrentShow,
            onToggle = { AppSettings.setContinueCurrentShow(context, it) }
        )
        }

        SectionDivider()

        // ---- Gestures -------------------------------------------------------
        SectionHeader(stringResource(R.string.gestures), sectionOpen("Gestures")) {
            toggleSection("Gestures")
        }
        if (sectionOpen("Gestures")) {
        SwipeActionPicker(
            label = stringResource(R.string.swipe_right),
            selected = AppSettings.swipeRightAction,
            onPick = { AppSettings.setSwipeRightAction(context, it) }
        )
        SwipeActionPicker(
            label = stringResource(R.string.swipe_left),
            selected = AppSettings.swipeLeftAction,
            onPick = { AppSettings.setSwipeLeftAction(context, it) }
        )
        }

        SectionDivider()

        // ---- Feeds & downloads -------------------------------------------
        SectionHeader(stringResource(R.string.feeds_downloads), sectionOpen("Feeds & downloads")) {
            toggleSection("Feeds & downloads")
        }
        if (sectionOpen("Feeds & downloads")) {
        NumberSetting(
            label = stringResource(R.string.refresh_feeds_every),
            unit = stringResource(R.string.hours),
            value = AppSettings.defaultRefreshHours,
            hint = stringResource(R.string.s_1_168_categories_can_override_this_per_cat),
            onCommit = { AppSettings.setDefaultRefreshHours(context, it) }
        )
        NumberSetting(
            label = stringResource(R.string.auto_keep_downloads),
            unit = stringResource(R.string.episodes_2),
            value = AppSettings.defaultKeepDownloads,
            hint = stringResource(R.string.s_0_50_default_for_newly_added_podcasts_0_of),
            onCommit = { AppSettings.setDefaultKeepDownloads(context, it) }
        )
        SwitchSetting(
            label = stringResource(R.string.download_on_wi_fi_only),
            hint = stringResource(R.string.downloads_wait_for_an_unmetered_connection),
            checked = AppSettings.wifiOnlyDownloads,
            onToggle = { AppSettings.setWifiOnlyDownloads(context, it) }
        )
        SwitchSetting(
            label = stringResource(R.string.new_episode_notifications),
            hint = stringResource(R.string.notify_when_background_refresh_finds_new_e),
            checked = AppSettings.newEpisodeNotifications,
            onToggle = { AppSettings.setNewEpisodeNotifications(context, it) }
        )
        ActionRow(
            label = stringResource(R.string.storage_2),
            hint = stringResource(R.string.downloaded_episodes_by_podcast_with_one_ta),
            onClick = { storageOpen = true }
        )
        }

        SectionDivider()

        // ---- Library ------------------------------------------------------
        SectionHeader(stringResource(R.string.library), sectionOpen("Library")) {
            toggleSection("Library")
        }
        if (sectionOpen("Library")) {
        SwitchSetting(
            label = stringResource(R.string.compact_list_layout),
            hint = stringResource(R.string.expanded_categories_show_slim_rows_instead),
            checked = AppSettings.libraryCompactList,
            onToggle = { AppSettings.setLibraryCompactList(context, it) }
        )
        SwitchSetting(
            label = stringResource(R.string.category_refresh_buttons),
            hint = stringResource(R.string.a_refresh_icon_on_each_category_header_in),
            checked = AppSettings.categoryRefreshButtons,
            onToggle = { AppSettings.setCategoryRefreshButtons(context, it) }
        )
        ActionRow(
            label = stringResource(R.string.add_local_folder),
            hint = stringResource(R.string.turn_a_folder_of_audio_files_into_a_virtua),
            onClick = { localFolderLauncher.launch(null) }
        )
        ActionRow(
            label = stringResource(R.string.import_opml),
            hint = stringResource(R.string.subscribe_to_feeds_exported_from_another_a),
            onClick = {
                importOpmlLauncher.launch(
                    arrayOf(
                        "text/xml",
                        "application/xml",
                        "text/x-opml",
                        "application/octet-stream"
                    )
                )
            }
        )
        ActionRow(
            label = stringResource(R.string.export_opml),
            hint = stringResource(R.string.save_your_subscriptions_to_a_file),
            onClick = { exportOpmlLauncher.launch("stepcast.opml") }
        )
        ActionRow(
            label = stringResource(R.string.reorder_categories_2),
            hint = stringResource(R.string.set_the_order_sections_appear_in_the_libra),
            onClick = { reorderDialogOpen = true }
        )
        ActionRow(
            label = stringResource(R.string.back_up_stepcast_data),
            hint = stringResource(R.string.subscriptions_categories_smartplays_and_se),
            onClick = { backupLauncher.launch("stepcast-backup.json") }
        )
        ActionRow(
            label = stringResource(R.string.restore_stepcast_backup),
            hint = stringResource(R.string.merge_a_backup_file_into_this_install),
            onClick = { restoreLauncher.launch(arrayOf("application/json", "*/*")) }
        )
        ActionRow(
            label = stringResource(
                if (AppSettings.autoBackupFolder == null) {
                    R.string.weekly_auto_backup
                } else {
                    R.string.weekly_auto_backup_on
                }
            ),
            hint = stringResource(
                if (AppSettings.autoBackupFolder == null) {
                    R.string.weekly_auto_backup_pick_hint
                } else {
                    R.string.tap_to_turn_off
                }
            ),
            onClick = {
                if (AppSettings.autoBackupFolder == null) {
                    autoBackupLauncher.launch(null)
                } else {
                    AppSettings.setAutoBackupFolder(context, null)
                    com.nsavage.stepcast.sync.AutoBackupWorker.cancel(context)
                    backupResult = context.getString(R.string.weekly_auto_backup_off_msg)
                }
            }
        )
        if (AppSettings.autoBackupFolder != null) {
            ActionRow(
                label = stringResource(R.string.back_up_to_that_folder_now),
                hint = stringResource(R.string.runs_the_weekly_backup_immediately),
                onClick = {
                    scope.launch {
                        val error = com.nsavage.stepcast.sync.AutoBackupWorker
                            .backupNow(context, repository)
                        backupResult = error
                            ?: context.getString(R.string.backup_written_msg)
                    }
                }
            )
        }
        backupResult?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        }

        SectionDivider()

        // ---- Stats ----------------------------------------------------------
        SectionHeader(stringResource(R.string.stats), sectionOpen("Stats")) {
            toggleSection("Stats")
        }
        if (sectionOpen("Stats")) {
        val statsSince = if (com.nsavage.stepcast.data.ListenStats.sinceMs > 0) {
            " " + stringResource(
                R.string.stats_since,
                java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                    .format(java.util.Date(com.nsavage.stepcast.data.ListenStats.sinceMs))
            )
        } else {
            ""
        }
        Text(
            stringResource(
                R.string.stats_listened,
                com.nsavage.stepcast.data.ListenStats
                    .formatDuration(com.nsavage.stepcast.data.ListenStats.wallMs)
            ) + statsSince,
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            stringResource(
                R.string.stats_saved,
                com.nsavage.stepcast.data.ListenStats
                    .formatDuration(com.nsavage.stepcast.data.ListenStats.savedMs),
                com.nsavage.stepcast.data.ListenStats.episodesFinished
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        var topStats by remember {
            mutableStateOf<List<Pair<com.nsavage.stepcast.data.Podcast,
                com.nsavage.stepcast.data.ListenStat>>>(emptyList())
        }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            topStats = runCatching { repository.topListenStats() }
                .getOrDefault(emptyList())
        }
        if (topStats.isNotEmpty()) {
            Text(
                stringResource(R.string.most_listened),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
            )
            for ((statPodcast, stat) in topStats) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    Text(
                        statPodcast.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        com.nsavage.stepcast.data.ListenStats.formatDuration(stat.wallMs),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
        }
        TextButton(onClick = {
            com.nsavage.stepcast.data.ListenStats.reset(context)
            scope.launch {
                runCatching { repository.clearListenStats() }
                topStats = emptyList()
            }
        }) {
            Text(stringResource(R.string.reset_stats))
        }
        }

        SectionDivider()

        // ---- BeyondPod import ----------------------------------------------
        SectionHeader(stringResource(R.string.import_from_beyondpod), sectionOpen("Import from BeyondPod")) {
            toggleSection("Import from BeyondPod")
        }
        if (sectionOpen("Import from BeyondPod")) {
        Text(
            stringResource(R.string.beyondpod_import_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = { beyondPodLauncher.launch(arrayOf("*/*")) },
            enabled = !bpImporting,
            modifier = Modifier.padding(top = 10.dp)
        ) {
            Text(
                stringResource(
                    if (bpImporting) R.string.importing else R.string.choose_backup_file
                )
            )
        }
        bpResult?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        }

        SectionDivider()

        // ---- Troubleshooting -----------------------------------------------
        val crashFile = java.io.File(context.filesDir, "last_crash.txt")
        if (crashFile.exists()) {
            SectionHeader(stringResource(R.string.troubleshooting), sectionOpen("Troubleshooting")) {
                toggleSection("Troubleshooting")
            }
            if (sectionOpen("Troubleshooting")) {
            ActionRow(
                label = stringResource(R.string.share_last_crash_report),
                hint = stringResource(R.string.stack_trace_from_the_most_recent_crash),
                onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(
                            android.content.Intent.EXTRA_TEXT,
                            runCatching { crashFile.readText() }.getOrDefault(
                                context.getString(R.string.couldnt_read_crash_file)
                            )
                        )
                    context.startActivity(
                        android.content.Intent.createChooser(
                            send, context.getString(R.string.crash_report)
                        )
                    )
                }
            )
            }
            SectionDivider()
        }

        // ---- Footer (long-press for the hidden diagnostics dump) ----------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = { diagnosticsOpen = true }
                )
                .padding(top = 4.dp, bottom = 24.dp)
        ) {
            StepMark(color = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 10.dp)) {
                Text(stringResource(R.string.stepcast), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.one_step_at_a_time),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (diagnosticsOpen) {
        // hidden support surface: technical, log-style output on purpose —
        // it exists so "X isn't working" reports can carry real state
        var diag by remember { mutableStateOf("…") }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            diag = runCatching {
                buildString {
                    val pods = repository.allPodcasts()
                    appendLine("podcasts: ${pods.size}")
                    appendLine("episodes: ${repository.episodeCount()}")
                    appendLine("queue: ${repository.queueSnapshot().size}")
                    appendLine("active station: ${AppSettings.activeStationId}")
                    val fmt = java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
                    )
                    appendLine("stalest feeds:")
                    pods.filter { it.localFolderUri == null }
                        .sortedBy { it.lastRefreshed }
                        .take(5)
                        .forEach {
                            val at = if (it.lastRefreshed > 0) {
                                fmt.format(java.util.Date(it.lastRefreshed))
                            } else {
                                "never"
                            }
                            appendLine(
                                "  ${it.title.take(28)}: $at" +
                                    " (fails ${it.consecutiveFailures})"
                            )
                        }
                    val prefs = context.getSharedPreferences(
                        com.nsavage.stepcast.widget.StepcastWidget.PREFS,
                        android.content.Context.MODE_PRIVATE
                    )
                    appendLine(
                        "widget state: episode=" + prefs.getLong(
                            com.nsavage.stepcast.widget.StepcastWidget.KEY_EPISODE_ID,
                            -1
                        ) + " playing=" + prefs.getBoolean(
                            com.nsavage.stepcast.widget.StepcastWidget.KEY_PLAYING,
                            false
                        )
                    )
                    appendLine(
                        "crash file: " +
                            java.io.File(context.filesDir, "last_crash.txt").exists()
                    )
                }
            }.getOrElse { "diagnostics failed: ${it.message}" }
        }
        AlertDialog(
            onDismissRequest = { diagnosticsOpen = false },
            title = { Text(stringResource(R.string.diagnostics)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        diag,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { diagnosticsOpen = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    if (reorderDialogOpen) {
        AlertDialog(
            onDismissRequest = { reorderDialogOpen = false },
            title = { Text(stringResource(R.string.reorder_categories)) },
            text = {
                Column {
                    if (categoryMetas.isEmpty()) {
                        Text(
                            stringResource(R.string.no_categories_yet_assign_one_from_a_podc),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    categoryMetas.forEachIndexed { index, meta ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                meta.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    scope.launch { repository.moveCategory(meta.name, up = true) }
                                },
                                enabled = index > 0
                            ) {
                                Icon(
                                    Icons.Rounded.KeyboardArrowUp,
                                    contentDescription = stringResource(
                                        R.string.move_up_cd, meta.name
                                    )
                                )
                            }
                            IconButton(
                                onClick = {
                                    scope.launch { repository.moveCategory(meta.name, up = false) }
                                },
                                enabled = index < categoryMetas.lastIndex
                            ) {
                                Icon(
                                    Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = stringResource(
                                        R.string.move_down_cd, meta.name
                                    )
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { reorderDialogOpen = false }) { Text(stringResource(R.string.done)) }
            }
        )
    }

    if (storageOpen) {
        var usage by remember {
            mutableStateOf<List<com.nsavage.stepcast.data.StorageUsage>?>(null)
        }
        androidx.compose.runtime.LaunchedEffect(storageOpen) {
            usage = repository.downloadUsage()
        }
        AlertDialog(
            onDismissRequest = { storageOpen = false },
            title = { Text(stringResource(R.string.storage)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState())
                ) {
                    val rows = usage
                    when {
                        rows == null -> Text(stringResource(R.string.measuring))
                        rows.isEmpty() -> Text(stringResource(R.string.no_downloaded_episodes))
                        else -> {
                            val totalEpisodes = rows.sumOf { it.episodes }
                            Text(
                                stringResource(
                                    R.string.storage_total_across,
                                    formatBytes(rows.sumOf { it.bytes }),
                                    pluralStringResource(
                                        R.plurals.episodes_count,
                                        totalEpisodes, totalEpisodes
                                    )
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            for (row in rows) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            row.podcast.title,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1
                                        )
                                        Text(
                                            pluralStringResource(
                                                R.plurals.episodes_count,
                                                row.episodes, row.episodes
                                            ) + " · " + formatBytes(row.bytes),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    TextButton(onClick = {
                                        scope.launch {
                                            repository
                                                .deleteDownloadsForPodcast(row.podcast.id)
                                            usage = repository.downloadUsage()
                                        }
                                    }) { Text(stringResource(R.string.free_up)) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { storageOpen = false }) { Text(stringResource(R.string.close)) }
            }
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.0f MB".format(bytes / 1_048_576.0)
    else -> "%.0f KB".format(bytes / 1024.0)
}

@Composable
private fun SwipeActionPicker(
    label: String,
    selected: String,
    onPick: (String) -> Unit
) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        for ((key, chipLabel) in AppSettings.SWIPE_LABELS) {
            FilterChip(
                selected = selected == key,
                onClick = { onPick(key) },
                label = { Text(chipLabel) }
            )
        }
    }
}

/** A tappable settings row: label + hint on the left, chevron on the right. */
@Composable
private fun ActionRow(label: String, hint: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onToggle != null) Modifier.clickable(onClick = onToggle)
                else Modifier
            )
            .padding(top = 8.dp, bottom = 8.dp)
    ) {
        StepMark(color = MaterialTheme.colorScheme.tertiary)
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f)
        )
        if (onToggle != null) {
            Icon(
                if (expanded) Icons.Rounded.KeyboardArrowUp
                else Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (expanded) {
                    "Collapse $title"
                } else {
                    "Expand $title"
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = 16.dp)
    )
}

/**
 * A numeric preference row: label + hint on the left, a small text field on
 * the right that commits on every valid change (the setter clamps ranges).
 */
@Composable
private fun NumberSetting(
    label: String,
    unit: String,
    value: Int,
    hint: String,
    onCommit: (Int) -> Unit
) {
    // deliberately not keyed on [value]: the clamped setter may echo back a
    // different number mid-typing, which would yank the cursor around
    var text by remember { mutableStateOf(value.toString()) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input.filter(Char::isDigit).take(3)
                text.toIntOrNull()?.let(onCommit)
            },
            suffix = { Text(unit, style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(130.dp)
        )
    }
}

@Composable
private fun SwitchSetting(
    label: String,
    hint: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        onClick = { onToggle(!checked) },
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 6.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onToggle)
        }
    }
}
