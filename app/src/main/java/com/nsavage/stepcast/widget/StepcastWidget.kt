package com.nsavage.stepcast.widget

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.components.SquareIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.nsavage.stepcast.R
import com.nsavage.stepcast.data.AppSettings
import com.nsavage.stepcast.playback.PlaybackService
import com.nsavage.stepcast.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await

// ---------------------------------------------------------------------------
// Three widgets, one state: PLAYER (art + progress + full transport),
// BAR (one-row: art + title + play/pause), MINI (artwork tile + play/pause).
// PlaybackService publishes state to SharedPreferences and calls
// updateAllStepcastWidgets(); background opacity is a Settings choice.
// ---------------------------------------------------------------------------

class StepcastWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StepcastWidget()
}

class StepcastBarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StepcastBarWidget()
}

class StepcastMiniWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StepcastMiniWidget()
}

class StepcastPlayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StepcastPlayWidget()
}

// Glance keeps a widget's composition session alive between updates:
// update() RECOMPOSES it, it does not re-run provideGlance. Anything read
// outside the composition is therefore frozen for the session's lifetime —
// which is why play/pause taps used to leave the glyph stale. Playback
// widgets instead read currentState<Preferences>() inside the composition,
// and this publisher copies the shared source-of-truth prefs into every
// placed widget's Glance state before poking it.
suspend fun updateAllStepcastWidgets(context: Context) {
    val source = context.getSharedPreferences(StepcastWidget.PREFS, Context.MODE_PRIVATE)
    val manager = GlanceAppWidgetManager(context)
    for (widget in listOf(
        StepcastWidget(),
        StepcastBarWidget(),
        StepcastMiniWidget(),
        StepcastPlayWidget()
    )) {
        for (id in manager.getGlanceIds(widget.javaClass)) {
            runCatching {
                updateAppWidgetState(context, id) { prefs ->
                    prefs[StepcastWidget.P_TITLE] =
                        source.getString(StepcastWidget.KEY_TITLE, null).orEmpty()
                    prefs[StepcastWidget.P_PODCAST] =
                        source.getString(StepcastWidget.KEY_PODCAST, "").orEmpty()
                    prefs[StepcastWidget.P_PLAYING] =
                        source.getBoolean(StepcastWidget.KEY_PLAYING, false)
                    prefs[StepcastWidget.P_PROGRESS] =
                        source.getFloat(StepcastWidget.KEY_PROGRESS, 0f)
                    prefs[StepcastWidget.P_ART_PATH] =
                        source.getString(StepcastWidget.KEY_ART_PATH, null).orEmpty()
                }
                widget.update(context, id)
            }
        }
    }
    StepcastSmartPlaysWidget().updateAll(context)
}

internal data class WidgetState(
    val title: String,
    val podcast: String,
    val isPlaying: Boolean,
    val progress: Float,
    val art: Bitmap?
)

/**
 * State for one render: the widget's Glance state, falling back to the
 * shared prefs for a freshly placed widget that hasn't been seeded yet.
 */
internal fun widgetStateFrom(context: Context, prefs: Preferences): WidgetState {
    if (prefs[StepcastWidget.P_TITLE] == null) return loadWidgetState(context)
    val art = prefs[StepcastWidget.P_ART_PATH]?.takeIf { it.isNotEmpty() }
        ?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
    return WidgetState(
        title = prefs[StepcastWidget.P_TITLE].orEmpty(),
        podcast = prefs[StepcastWidget.P_PODCAST].orEmpty(),
        isPlaying = prefs[StepcastWidget.P_PLAYING] ?: false,
        progress = prefs[StepcastWidget.P_PROGRESS] ?: 0f,
        art = art
    )
}

internal fun loadWidgetState(context: Context): WidgetState {
    val prefs = context.getSharedPreferences(StepcastWidget.PREFS, Context.MODE_PRIVATE)
    val art = prefs.getString(StepcastWidget.KEY_ART_PATH, null)
        ?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
    return WidgetState(
        title = prefs.getString(StepcastWidget.KEY_TITLE, null).orEmpty(),
        podcast = prefs.getString(StepcastWidget.KEY_PODCAST, "").orEmpty(),
        isPlaying = prefs.getBoolean(StepcastWidget.KEY_PLAYING, false),
        progress = prefs.getFloat(StepcastWidget.KEY_PROGRESS, 0f),
        art = art
    )
}

/**
 * Opacity for one placed widget: its own configured value (set by the
 * widget's configure screen), or the global Settings default.
 */
internal fun widgetOpacity(context: Context, glanceId: GlanceId): Int = runCatching {
    val appWidgetId =
        androidx.glance.appwidget.GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
    val prefs = context.getSharedPreferences(StepcastWidget.PREFS, Context.MODE_PRIVATE)
    prefs.getInt("opacity_$appWidgetId", -1).takeIf { it in 0..100 }
        ?: AppSettings.widgetOpacity
}.getOrDefault(AppSettings.widgetOpacity)

/** Widget background honoring the per-widget/global opacity choice. */
@Composable
internal fun widgetBackgroundColor(
    pct: Int = AppSettings.widgetOpacity
): androidx.glance.unit.ColorProvider {
    if (pct >= 100) return GlanceTheme.colors.widgetBackground
    val alpha = pct / 100f
    return ColorProvider(
        day = Color(0xFFF3EDF7).copy(alpha = alpha),
        night = Color(0xFF1D1B20).copy(alpha = alpha)
    )
}

/**
 * The play/pause control every widget shares. With a visible background
 * it's a filled button; at Clear (0%) it collapses to just the tinted
 * glyph so nothing floats on the wallpaper but the symbol itself.
 */
@Composable
internal fun PlayPauseButton(isPlaying: Boolean, opacity: Int, sizeDp: Int = 44) {
    val icon = if (isPlaying) R.drawable.ic_notif_pause else R.drawable.ic_notif_play
    val label = if (isPlaying) "Pause" else "Play"
    if (opacity <= 0) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = GlanceModifier
                .size(sizeDp.dp)
                .clickable(actionRunCallback<PlayPauseAction>())
        ) {
            Image(
                provider = ImageProvider(icon),
                contentDescription = label,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                modifier = GlanceModifier.size((sizeDp * 3 / 4).dp)
            )
        }
    } else {
        SquareIconButton(
            imageProvider = ImageProvider(icon),
            contentDescription = label,
            onClick = actionRunCallback<PlayPauseAction>(),
            modifier = GlanceModifier.size(sizeDp.dp)
        )
    }
}

/** Same treatment for the secondary transport buttons. */
@Composable
internal fun TransportButton(
    icon: Int,
    label: String,
    opacity: Int,
    onClick: androidx.glance.action.Action
) {
    if (opacity <= 0) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = GlanceModifier.size(44.dp).clickable(onClick)
        ) {
            Image(
                provider = ImageProvider(icon),
                contentDescription = label,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                modifier = GlanceModifier.size(26.dp)
            )
        }
    } else {
        CircleIconButton(
            imageProvider = ImageProvider(icon),
            contentDescription = label,
            onClick = onClick
        )
    }
}

@Composable
internal fun ArtworkOrGlyph(art: Bitmap?, sizeDp: Int) {
    if (art != null) {
        Image(
            provider = ImageProvider(art),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = GlanceModifier.size(sizeDp.dp).cornerRadius(12.dp)
        )
    } else {
        Box(
            modifier = GlanceModifier.size(sizeDp.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("▂▄▆", style = TextStyle(color = GlanceTheme.colors.primary))
        }
    }
}

// ---- PLAYER: art + text + progress + full transport ------------------------

class StepcastWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // read INSIDE the composition — currentState changes on every
            // publish, re-running this block with fresh values
            val state = widgetStateFrom(context, currentState())
            val opacity = widgetOpacity(context, id)
            GlanceTheme {
                if (state.title.isEmpty()) EmptyWidget(opacity) else PlayerWidget(state, opacity)
            }
        }
    }

    @Composable
    private fun PlayerWidget(state: WidgetState, opacity: Int) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(widgetBackgroundColor(opacity))
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .clickable(actionStartActivity<MainActivity>())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArtworkOrGlyph(state.art, sizeDp = 56)
                Spacer(GlanceModifier.width(10.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(
                        state.title,
                        style = TextStyle(
                            fontWeight = FontWeight.Medium,
                            color = GlanceTheme.colors.onSurface
                        ),
                        maxLines = 2
                    )
                    if (state.podcast.isNotEmpty()) {
                        Text(
                            state.podcast,
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                            maxLines = 1
                        )
                    }
                }
            }
            Spacer(GlanceModifier.defaultWeight())
            if (state.progress > 0f) {
                LinearProgressIndicator(
                    progress = state.progress,
                    modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                    color = GlanceTheme.colors.primary,
                    backgroundColor = GlanceTheme.colors.surfaceVariant
                )
                Spacer(GlanceModifier.height(10.dp))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = GlanceModifier.fillMaxWidth()
            ) {
                TransportButton(
                    R.drawable.ic_notif_replay,
                    "Seek back",
                    opacity,
                    actionRunCallback<SeekBackAction>()
                )
                Spacer(GlanceModifier.width(10.dp))
                PlayPauseButton(state.isPlaying, opacity)
                Spacer(GlanceModifier.width(10.dp))
                TransportButton(
                    R.drawable.ic_notif_forward,
                    "Seek forward",
                    opacity,
                    actionRunCallback<SeekForwardAction>()
                )
                Spacer(GlanceModifier.width(10.dp))
                TransportButton(
                    R.drawable.ic_notif_done,
                    "Done: mark played, delete, next",
                    opacity,
                    actionRunCallback<DoneDeleteAction>()
                )
            }
        }
    }

    companion object {
        const val PREFS = "stepcast_widget"
        const val KEY_TITLE = "title"
        const val KEY_PODCAST = "podcast"
        const val KEY_PLAYING = "playing"
        const val KEY_PROGRESS = "progress"
        const val KEY_ART_PATH = "artPath"
        const val KEY_EPISODE_ID = "episodeId"

        // per-widget Glance state (what the composition actually reads)
        val P_TITLE = stringPreferencesKey("title")
        val P_PODCAST = stringPreferencesKey("podcast")
        val P_PLAYING = booleanPreferencesKey("playing")
        val P_PROGRESS = floatPreferencesKey("progress")
        val P_ART_PATH = stringPreferencesKey("artPath")
    }
}

// ---- BAR: one-row strip — art, title, play/pause ---------------------------

class StepcastBarWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    // the play/pause button is the one thing that must survive shrinking:
    // below ~200dp the text goes, below ~110dp the artwork goes too
    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(40.dp, 40.dp),
            DpSize(110.dp, 40.dp),
            DpSize(200.dp, 40.dp)
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val state = widgetStateFrom(context, currentState())
            val opacity = widgetOpacity(context, id)
            val width = androidx.glance.LocalSize.current.width
            GlanceTheme {
                when {
                    width < 110.dp -> Box(
                        contentAlignment = Alignment.Center,
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .background(widgetBackgroundColor(opacity))
                            .cornerRadius(20.dp)
                    ) {
                        PlayPauseButton(state.isPlaying, opacity)
                    }
                    width < 200.dp -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .background(widgetBackgroundColor(opacity))
                            .cornerRadius(20.dp)
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                            .clickable(actionStartActivity<MainActivity>())
                    ) {
                        ArtworkOrGlyph(state.art, sizeDp = 44)
                        Spacer(GlanceModifier.defaultWeight())
                        PlayPauseButton(state.isPlaying, opacity)
                    }
                    else -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .background(widgetBackgroundColor(opacity))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .clickable(actionStartActivity<MainActivity>())
                    ) {
                        ArtworkOrGlyph(state.art, sizeDp = 44)
                        Spacer(GlanceModifier.width(10.dp))
                        Column(GlanceModifier.defaultWeight()) {
                            Text(
                                state.title.ifEmpty { "Nothing playing" },
                                style = TextStyle(
                                    fontWeight = FontWeight.Medium,
                                    color = GlanceTheme.colors.onSurface
                                ),
                                maxLines = 1
                            )
                            if (state.podcast.isNotEmpty()) {
                                Text(
                                    state.podcast,
                                    style = TextStyle(
                                        color = GlanceTheme.colors.onSurfaceVariant
                                    ),
                                    maxLines = 1
                                )
                            }
                        }
                        Spacer(GlanceModifier.width(10.dp))
                        PlayPauseButton(state.isPlaying, opacity)
                    }
                }
            }
        }
    }
}

// ---- MINI: artwork tile with a play/pause corner button --------------------

class StepcastMiniWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val state = widgetStateFrom(context, currentState())
            val opacity = widgetOpacity(context, id)
            GlanceTheme {
                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(widgetBackgroundColor(opacity))
                        .cornerRadius(20.dp)
                        .clickable(actionStartActivity<MainActivity>())
                ) {
                    if (state.art != null) {
                        Image(
                            provider = ImageProvider(state.art),
                            contentDescription = state.title,
                            contentScale = ContentScale.Crop,
                            modifier = GlanceModifier.fillMaxSize().cornerRadius(20.dp)
                        )
                    } else {
                        Box(
                            modifier = GlanceModifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "▂▄▆",
                                style = TextStyle(color = GlanceTheme.colors.primary)
                            )
                        }
                    }
                    Box(
                        modifier = GlanceModifier.fillMaxSize().padding(6.dp),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        // mini keeps its filled corner button — it sits ON
                        // the artwork, where a bare glyph would vanish
                        SquareIconButton(
                            imageProvider = ImageProvider(
                                if (state.isPlaying) {
                                    R.drawable.ic_notif_pause
                                } else {
                                    R.drawable.ic_notif_play
                                }
                            ),
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            onClick = actionRunCallback<PlayPauseAction>(),
                            modifier = GlanceModifier.size(36.dp)
                        )
                    }
                }
            }
        }
    }
}

// ---- PLAY: minimalist single play/pause button ------------------------------

class StepcastPlayWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val state = widgetStateFrom(context, currentState())
            // honors the per-widget/global opacity — Clear (0%) leaves just
            // the glyph floating on the wallpaper
            val opacity = widgetOpacity(context, id)
            GlanceTheme {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(widgetBackgroundColor(opacity))
                        .cornerRadius(24.dp)
                        .clickable(actionRunCallback<PlayPauseAction>())
                ) {
                    Image(
                        provider = ImageProvider(
                            if (state.isPlaying) {
                                R.drawable.ic_notif_pause
                            } else {
                                R.drawable.ic_notif_play
                            }
                        ),
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                        modifier = GlanceModifier.size(32.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyWidget(opacity: Int) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(widgetBackgroundColor(opacity))
            .padding(16.dp)
            .clickable(actionStartActivity<MainActivity>()),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("▂▄▆", style = TextStyle(color = GlanceTheme.colors.primary))
            Spacer(GlanceModifier.height(4.dp))
            Text(
                "Nothing playing",
                style = TextStyle(
                    fontWeight = FontWeight.Medium,
                    color = GlanceTheme.colors.onSurface
                )
            )
            Text(
                "Tap to open Stepcast",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant)
            )
        }
    }
}

// ---- actions ---------------------------------------------------------------

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        var nowPlaying: Boolean? = null
        sendPlayerCommand(context) {
            nowPlaying = !it.isPlaying
            if (it.isPlaying) it.pause() else it.play()
        }
        // flip the glyph immediately from this side of the tap — the
        // service's own publish converges the real state right after, but
        // must not be the only thing standing between tap and feedback
        nowPlaying?.let { playing ->
            context.getSharedPreferences(StepcastWidget.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(StepcastWidget.KEY_PLAYING, playing).apply()
            runCatching { updateAllStepcastWidgets(context) }
        }
    }
}

class SeekBackAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) = sendPlayerCommand(context) { it.seekBack() }
}

class SeekForwardAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) = sendPlayerCommand(context) { it.seekForward() }
}

/** Mark played + delete download + advance — same command the notification uses. */
class DoneDeleteAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) = sendPlayerCommand(context) { controller ->
        controller.sendCustomCommand(
            SessionCommand(PlaybackService.ACTION_DONE_DELETE, Bundle.EMPTY),
            Bundle.EMPTY
        )
    }
}

// Glance runs ActionCallbacks on a background dispatcher, but every
// MediaController method throws if called off the controller's looper
// (main) — so the whole command sequence hops to Main or no button works.
private suspend fun sendPlayerCommand(
    context: Context,
    command: (MediaController) -> Unit
) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
    try {
        val token = SessionToken(
            context.applicationContext,
            ComponentName(context.applicationContext, PlaybackService::class.java)
        )
        val controller = MediaController.Builder(context.applicationContext, token)
            .buildAsync()
            .await()
        try {
            command(controller)
            delay(300) // let the command dispatch before releasing the controller
        } finally {
            controller.release()
        }
    } catch (t: Throwable) {
        // surface instead of dying silently in Glance's catch — a dead
        // button with no feedback is undebuggable from the home screen
        android.widget.Toast.makeText(
            context.applicationContext,
            "Stepcast widget: ${t.message ?: t.javaClass.simpleName}",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}
