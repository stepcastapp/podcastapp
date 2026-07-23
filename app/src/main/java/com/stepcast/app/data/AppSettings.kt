package com.stepcast.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * App-wide knobs, persisted in Preferences DataStore (migrated from the old
 * SharedPreferences file on first open) and exposed as Compose state. Call
 * [init] once at startup (Application.onCreate) so services, workers, and
 * the widgets all see the values; after that the in-memory state is the
 * source of truth and the store is pure persistence.
 */
object AppSettings {

    var defaultRefreshHours by mutableStateOf(3)
        private set
    var defaultKeepDownloads by mutableStateOf(2)
        private set
    var seekBackSeconds by mutableStateOf(10)
        private set
    var seekForwardSeconds by mutableStateOf(30)
        private set
    var adChapterAutoSkip by mutableStateOf(true)
        private set
    var newEpisodeNotifications by mutableStateOf(true)
        private set

    /** New-episode alerts only near "Fresh by" checkpoints, not every check. */
    var notifyOnlyAtCheckpoints by mutableStateOf(true)
        private set
    var defaultPlaybackSpeed by mutableStateOf(1.0f)
        private set
    var wifiOnlyDownloads by mutableStateOf(false)
        private set
    var streamWhenNotDownloaded by mutableStateOf(true)
        private set
    var skipSilence by mutableStateOf(false)
        private set
    var notificationDoneButton by mutableStateOf(true)
        private set
    var swipeQueueToTop by mutableStateOf(false)
        private set
    var queueNextAtBottom by mutableStateOf(false)
        private set

    /** When Up Next runs dry: keep playing the current show's unplayed episodes. */
    var continueCurrentShow by mutableStateOf(false)
        private set
    var widgetOpacity by mutableStateOf(100)
        private set
    var collapsedCategories by mutableStateOf(setOf<String>())
        private set
    var swipeRightAction by mutableStateOf(SWIPE_PLAYED)
        private set
    var swipeLeftAction by mutableStateOf(SWIPE_QUEUE)
        private set

    /** Library page: refresh icon on every category header. */
    var categoryRefreshButtons by mutableStateOf(false)
        private set

    /** Library page: expanded categories render as compact rows, not tiles. */
    var libraryCompactList by mutableStateOf(false)
        private set

    /** SAF tree URI the weekly auto-backup writes into; null = off. */
    var autoBackupFolder by mutableStateOf<String?>(null)
        private set

    /**
     * "Fresh by" checkpoints — the four named daily moments (Morning,
     * Midday, Evening, Night) as minutes after midnight, plus which are
     * enabled. Automatic-mode shows refresh to meet every enabled one.
     */
    var checkpointTimes by mutableStateOf(listOf(390, 720, 1050, 1320))
        private set
    var checkpointEnabled by mutableStateOf(listOf(true, true, true, false))
        private set

    /** No automatic refreshes between these times when enabled. */
    var quietHoursEnabled by mutableStateOf(false)
        private set
    var quietStartMinutes by mutableStateOf(1380)
        private set
    var quietEndMinutes by mutableStateOf(360)
        private set

    fun enabledCheckpointMinutes(): List<Int> =
        checkpointTimes.filterIndexed { i, _ ->
            checkpointEnabled.getOrElse(i) { false }
        }

    fun init(context: Context) {
        // one small file, read once at startup — blocking is deliberate so
        // everything after Application.onCreate sees real values
        val p = runBlocking { context.applicationContext.settingsStore.data.first() }
        defaultRefreshHours = p[intPreferencesKey(KEY_REFRESH_HOURS)] ?: 3
        defaultKeepDownloads = p[intPreferencesKey(KEY_KEEP_DOWNLOADS)] ?: 2
        seekBackSeconds = p[intPreferencesKey(KEY_SEEK_BACK)] ?: 10
        seekForwardSeconds = p[intPreferencesKey(KEY_SEEK_FWD)] ?: 30
        adChapterAutoSkip = p[booleanPreferencesKey(KEY_AD_SKIP)] ?: true
        newEpisodeNotifications = p[booleanPreferencesKey(KEY_NOTIFY_NEW)] ?: true
        notifyOnlyAtCheckpoints = p[booleanPreferencesKey(KEY_NOTIFY_CHECKPOINTS)] ?: true
        defaultPlaybackSpeed = p[floatPreferencesKey(KEY_DEFAULT_SPEED)] ?: 1.0f
        wifiOnlyDownloads = p[booleanPreferencesKey(KEY_WIFI_ONLY)] ?: false
        streamWhenNotDownloaded = p[booleanPreferencesKey(KEY_STREAM_OK)] ?: true
        skipSilence = p[booleanPreferencesKey(KEY_SKIP_SILENCE)] ?: false
        notificationDoneButton = p[booleanPreferencesKey(KEY_NOTIF_DONE)] ?: true
        swipeQueueToTop = p[booleanPreferencesKey(KEY_SWIPE_TO_TOP)] ?: false
        queueNextAtBottom = p[booleanPreferencesKey(KEY_QUEUE_AT_BOTTOM)] ?: false
        widgetOpacity = p[intPreferencesKey(KEY_WIDGET_OPACITY)] ?: 100
        collapsedCategories = p[stringSetPreferencesKey(KEY_COLLAPSED)] ?: emptySet()
        swipeRightAction = p[stringPreferencesKey(KEY_SWIPE_RIGHT)] ?: SWIPE_PLAYED
        swipeLeftAction = p[stringPreferencesKey(KEY_SWIPE_LEFT)] ?: SWIPE_QUEUE
        autoBackupFolder = p[stringPreferencesKey(KEY_AUTO_BACKUP)]
        continueCurrentShow = p[booleanPreferencesKey(KEY_CONTINUE_SHOW)] ?: false
        categoryRefreshButtons = p[booleanPreferencesKey(KEY_CAT_REFRESH)] ?: false
        libraryCompactList = p[booleanPreferencesKey(KEY_LIB_COMPACT)] ?: false
        activeStationId = p[longPreferencesKey(KEY_ACTIVE_STATION)] ?: 0L
        checkpointTimes = parseIntList(
            p[stringPreferencesKey(KEY_CHECKPOINT_TIMES)], listOf(390, 720, 1050, 1320)
        )
        checkpointEnabled = parseBoolList(
            p[stringPreferencesKey(KEY_CHECKPOINT_ON)], listOf(true, true, true, false)
        )
        quietHoursEnabled = p[booleanPreferencesKey(KEY_QUIET_ON)] ?: false
        quietStartMinutes = p[intPreferencesKey(KEY_QUIET_START)] ?: 1380
        quietEndMinutes = p[intPreferencesKey(KEY_QUIET_END)] ?: 360
    }

    private fun parseIntList(raw: String?, fallback: List<Int>): List<Int> {
        val parsed = raw?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
        return if (parsed != null && parsed.size == fallback.size) parsed else fallback
    }

    private fun parseBoolList(raw: String?, fallback: List<Boolean>): List<Boolean> {
        val parsed = raw?.split(",")?.map { it.trim() == "1" }
        return if (parsed != null && parsed.size == fallback.size) parsed else fallback
    }

    fun setCheckpointTime(context: Context, index: Int, minutes: Int) {
        if (index !in checkpointTimes.indices) return
        checkpointTimes = checkpointTimes.toMutableList().also {
            it[index] = minutes.coerceIn(0, 1439)
        }
        putString(context, KEY_CHECKPOINT_TIMES, checkpointTimes.joinToString(","))
    }

    fun setCheckpointEnabled(context: Context, index: Int, enabled: Boolean) {
        if (index !in checkpointEnabled.indices) return
        checkpointEnabled = checkpointEnabled.toMutableList().also {
            it[index] = enabled
        }
        putString(
            context, KEY_CHECKPOINT_ON,
            checkpointEnabled.joinToString(",") { if (it) "1" else "0" }
        )
    }

    fun setQuietHoursEnabled(context: Context, enabled: Boolean) {
        quietHoursEnabled = enabled
        putBoolean(context, KEY_QUIET_ON, enabled)
    }

    fun setQuietHours(context: Context, startMinutes: Int, endMinutes: Int) {
        quietStartMinutes = startMinutes.coerceIn(0, 1439)
        quietEndMinutes = endMinutes.coerceIn(0, 1439)
        putInt(context, KEY_QUIET_START, quietStartMinutes)
        putInt(context, KEY_QUIET_END, quietEndMinutes)
    }

    /** The continuous SmartPlay currently feeding the queue; 0 = none. */
    var activeStationId by mutableStateOf(0L)
        private set

    fun setActiveStationId(context: Context, id: Long) {
        activeStationId = id
        val appContext = context.applicationContext
        prefsWriteScope.launch {
            appContext.settingsStore.edit {
                it[longPreferencesKey(KEY_ACTIVE_STATION)] = id
            }
        }
    }

    fun setCategoryRefreshButtons(context: Context, enabled: Boolean) {
        categoryRefreshButtons = enabled
        putBoolean(context, KEY_CAT_REFRESH, enabled)
    }

    fun setLibraryCompactList(context: Context, enabled: Boolean) {
        libraryCompactList = enabled
        putBoolean(context, KEY_LIB_COMPACT, enabled)
    }

    fun setContinueCurrentShow(context: Context, enabled: Boolean) {
        continueCurrentShow = enabled
        putBoolean(context, KEY_CONTINUE_SHOW, enabled)
    }

    fun setAutoBackupFolder(context: Context, treeUri: String?) {
        autoBackupFolder = treeUri
        val appContext = context.applicationContext
        prefsWriteScope.launch {
            appContext.settingsStore.edit {
                val key = stringPreferencesKey(KEY_AUTO_BACKUP)
                if (treeUri == null) it.remove(key) else it[key] = treeUri
            }
        }
    }

    fun setDefaultRefreshHours(context: Context, hours: Int) {
        defaultRefreshHours = hours.coerceIn(1, 168)
        putInt(context, KEY_REFRESH_HOURS, defaultRefreshHours)
    }

    fun setDefaultKeepDownloads(context: Context, keep: Int) {
        defaultKeepDownloads = keep.coerceIn(0, 50)
        putInt(context, KEY_KEEP_DOWNLOADS, defaultKeepDownloads)
    }

    fun setSeekBackSeconds(context: Context, seconds: Int) {
        seekBackSeconds = seconds.coerceIn(5, 120)
        putInt(context, KEY_SEEK_BACK, seekBackSeconds)
    }

    fun setSeekForwardSeconds(context: Context, seconds: Int) {
        seekForwardSeconds = seconds.coerceIn(5, 300)
        putInt(context, KEY_SEEK_FWD, seekForwardSeconds)
    }

    fun setAdChapterAutoSkip(context: Context, enabled: Boolean) {
        adChapterAutoSkip = enabled
        putBoolean(context, KEY_AD_SKIP, enabled)
    }

    fun setNewEpisodeNotifications(context: Context, enabled: Boolean) {
        newEpisodeNotifications = enabled
        putBoolean(context, KEY_NOTIFY_NEW, enabled)
    }

    fun setNotifyOnlyAtCheckpoints(context: Context, enabled: Boolean) {
        notifyOnlyAtCheckpoints = enabled
        putBoolean(context, KEY_NOTIFY_CHECKPOINTS, enabled)
    }

    fun setDefaultPlaybackSpeed(context: Context, speed: Float) {
        defaultPlaybackSpeed = speed.coerceIn(0.5f, 3.0f)
        val value = defaultPlaybackSpeed
        val appContext = context.applicationContext
        prefsWriteScope.launch {
            appContext.settingsStore.edit {
                it[floatPreferencesKey(KEY_DEFAULT_SPEED)] = value
            }
        }
    }

    fun setWifiOnlyDownloads(context: Context, enabled: Boolean) {
        wifiOnlyDownloads = enabled
        putBoolean(context, KEY_WIFI_ONLY, enabled)
    }

    fun setStreamWhenNotDownloaded(context: Context, enabled: Boolean) {
        streamWhenNotDownloaded = enabled
        putBoolean(context, KEY_STREAM_OK, enabled)
    }

    fun setSkipSilence(context: Context, enabled: Boolean) {
        skipSilence = enabled
        putBoolean(context, KEY_SKIP_SILENCE, enabled)
    }

    fun setNotificationDoneButton(context: Context, enabled: Boolean) {
        notificationDoneButton = enabled
        putBoolean(context, KEY_NOTIF_DONE, enabled)
    }

    fun setSwipeQueueToTop(context: Context, enabled: Boolean) {
        swipeQueueToTop = enabled
        putBoolean(context, KEY_SWIPE_TO_TOP, enabled)
    }

    fun setQueueNextAtBottom(context: Context, enabled: Boolean) {
        queueNextAtBottom = enabled
        putBoolean(context, KEY_QUEUE_AT_BOTTOM, enabled)
    }

    fun setWidgetOpacity(context: Context, percent: Int) {
        widgetOpacity = percent.coerceIn(0, 100)
        putInt(context, KEY_WIDGET_OPACITY, widgetOpacity)
    }

    fun setSwipeRightAction(context: Context, action: String) {
        swipeRightAction = action.takeIf { it in SWIPE_LABELS } ?: SWIPE_PLAYED
        putString(context, KEY_SWIPE_RIGHT, swipeRightAction)
    }

    fun setSwipeLeftAction(context: Context, action: String) {
        swipeLeftAction = action.takeIf { it in SWIPE_LABELS } ?: SWIPE_QUEUE
        putString(context, KEY_SWIPE_LEFT, swipeLeftAction)
    }

    fun toggleCategoryCollapsed(context: Context, name: String) {
        collapsedCategories = if (name in collapsedCategories) {
            collapsedCategories - name
        } else {
            collapsedCategories + name
        }
        val value = collapsedCategories
        val appContext = context.applicationContext
        prefsWriteScope.launch {
            appContext.settingsStore.edit {
                it[stringSetPreferencesKey(KEY_COLLAPSED)] = value
            }
        }
    }

    private fun putInt(context: Context, key: String, value: Int) {
        val appContext = context.applicationContext
        prefsWriteScope.launch {
            appContext.settingsStore.edit { it[intPreferencesKey(key)] = value }
        }
    }

    private fun putBoolean(context: Context, key: String, value: Boolean) {
        val appContext = context.applicationContext
        prefsWriteScope.launch {
            appContext.settingsStore.edit { it[booleanPreferencesKey(key)] = value }
        }
    }

    private fun putString(context: Context, key: String, value: String) {
        val appContext = context.applicationContext
        prefsWriteScope.launch {
            appContext.settingsStore.edit { it[stringPreferencesKey(key)] = value }
        }
    }

    private const val KEY_REFRESH_HOURS = "defaultRefreshHours"
    private const val KEY_KEEP_DOWNLOADS = "defaultKeepDownloads"
    private const val KEY_SEEK_BACK = "seekBackSeconds"
    private const val KEY_SEEK_FWD = "seekForwardSeconds"
    private const val KEY_AD_SKIP = "adChapterAutoSkip"
    private const val KEY_NOTIFY_NEW = "newEpisodeNotifications"
    private const val KEY_NOTIFY_CHECKPOINTS = "notifyOnlyAtCheckpoints"
    private const val KEY_DEFAULT_SPEED = "defaultPlaybackSpeed"
    private const val KEY_WIFI_ONLY = "wifiOnlyDownloads"
    private const val KEY_STREAM_OK = "streamWhenNotDownloaded"
    private const val KEY_SKIP_SILENCE = "skipSilence"
    private const val KEY_NOTIF_DONE = "notificationDoneButton"
    private const val KEY_SWIPE_TO_TOP = "swipeQueueToTop"
    private const val KEY_QUEUE_AT_BOTTOM = "queueNextAtBottom"
    private const val KEY_WIDGET_OPACITY = "widgetOpacity"
    private const val KEY_COLLAPSED = "collapsedCategories"
    private const val KEY_SWIPE_RIGHT = "swipeRightAction"
    private const val KEY_SWIPE_LEFT = "swipeLeftAction"
    private const val KEY_AUTO_BACKUP = "autoBackupFolder"
    private const val KEY_CONTINUE_SHOW = "continueCurrentShow"
    private const val KEY_CAT_REFRESH = "categoryRefreshButtons"
    private const val KEY_LIB_COMPACT = "libraryCompactList"
    private const val KEY_ACTIVE_STATION = "activeStationId"
    private const val KEY_CHECKPOINT_TIMES = "checkpointTimes"
    private const val KEY_CHECKPOINT_ON = "checkpointEnabled"
    private const val KEY_QUIET_ON = "quietHoursEnabled"
    private const val KEY_QUIET_START = "quietStartMinutes"
    private const val KEY_QUIET_END = "quietEndMinutes"

    // swipe gesture actions
    const val SWIPE_PLAYED = "played"
    const val SWIPE_QUEUE = "queue"
    const val SWIPE_DOWNLOAD = "download"
    const val SWIPE_DONE = "done"

    val SWIPE_LABELS = linkedMapOf(
        SWIPE_PLAYED to "Played",
        SWIPE_QUEUE to "Queue",
        SWIPE_DOWNLOAD to "Download",
        SWIPE_DONE to "Done + delete"
    )
}
