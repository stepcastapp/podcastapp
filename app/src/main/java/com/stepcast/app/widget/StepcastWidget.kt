package com.stepcast.app.widget

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
import com.stepcast.app.R
import com.stepcast.app.data.AppSettings
import com.stepcast.app.playback.PlaybackService
import com.stepcast.app.ui.MainActivity
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

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        removeWidgetOpacityPrefs(context, appWidgetIds)
    }
}

class StepcastBarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StepcastBarWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        removeWidgetOpacityPrefs(context, appWidgetIds)
    }
}

class StepcastMiniWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StepcastMiniWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        removeWidgetOpacityPrefs(context, appWidgetIds)
    }
}

class StepcastPlayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StepcastPlayWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        removeWidgetOpacityPrefs(context, appWidgetIds)
    }
}

/**
 * Drops a deleted widget's per-widget opacity choice. Android RECYCLES
 * appWidgetIds, so without this a freshly placed widget could silently
 * inherit a long-gone widget's setting — worst case "fully transparent".
 */
internal fun removeWidgetOpacityPrefs(context: Context, appWidgetIds: IntArray) {
    val editor = context
        .getSharedPreferences(StepcastWidget.PREFS, Context.MODE_PRIVATE)
        .edit()
    for (id in appWidgetIds) editor.remove("opacity_$id")
    editor.apply()
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
    // the SmartPlays widget reads its list from Glance state for the same
    // reason (see the session-lifetime note above) — seed every placed
    // instance with the current names before poking it
    // "id|name" lines: the id survives a RENAME — a tap on a stale widget
    // still starts the right SmartPlay instead of "not found"
    val smartPlayLines = runCatching {
        (context.applicationContext as com.stepcast.app.StepcastApplication)
            .repository.smartPlayList().map { "${it.id}|${it.name}" }
    }.getOrNull()
    val smartPlaysWidget = StepcastSmartPlaysWidget()
    for (id in manager.getGlanceIds(StepcastSmartPlaysWidget::class.java)) {
        runCatching {
            if (smartPlayLines != null) {
                updateAppWidgetState(context, id) { prefs ->
                    prefs[StepcastSmartPlaysWidget.P_SMARTPLAY_NAMES] =
                        smartPlayLines.joinToString("\n")
                }
            }
            smartPlaysWidget.update(context, id)
        }
    }
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
 * Below this, the widget's own panel is too faint to define contrast and
 * the content is effectively sitting on raw wallpaper. Surface-relative
 * theme colours (onSurface, primary, surfaceVariant) stop meaning
 * anything there: they are picked against a surface that isn't visible,
 * so on a dark wallpaper a light-theme phone renders near-black text on
 * near-black pixels. Everything below switches to fixed colours instead.
 */
private const val FLOATING_BELOW = 40

internal fun widgetIsFloating(opacity: Int) = opacity < FLOATING_BELOW

/** Primary text: theme-aware on a panel, fixed white when floating. */
@Composable
internal fun widgetTextColor(opacity: Int): androidx.glance.unit.ColorProvider =
    if (widgetIsFloating(opacity)) {
        ColorProvider(day = Color.White, night = Color.White)
    } else {
        GlanceTheme.colors.onSurface
    }

/** Secondary text: slightly dimmed, same rule. */
@Composable
internal fun widgetSecondaryTextColor(opacity: Int): androidx.glance.unit.ColorProvider =
    if (widgetIsFloating(opacity)) {
        ColorProvider(day = Color(0xE0FFFFFF), night = Color(0xE0FFFFFF))
    } else {
        GlanceTheme.colors.onSurfaceVariant
    }

/** Glyph tint: the accent can be a mid-tone that disappears on wallpaper. */
@Composable
internal fun widgetIconTint(opacity: Int): androidx.glance.unit.ColorProvider =
    if (widgetIsFloating(opacity)) {
        ColorProvider(day = Color.White, night = Color.White)
    } else {
        GlanceTheme.colors.primary
    }

/**
 * A dark pill behind floating text. White alone is a coin flip — Glance
 * has no text shadow or outline, so against a pale wallpaper white text
 * would be just as unreadable as the dark text this replaces. The scrim
 * is what actually guarantees legibility; it stays off entirely once the
 * widget has a real panel behind it.
 */
internal fun GlanceModifier.widgetTextScrim(opacity: Int): GlanceModifier =
    if (widgetIsFloating(opacity)) {
        this.background(ColorProvider(day = Color(0x8A000000), night = Color(0x8A000000)))
            .cornerRadius(8.dp)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    } else {
        this
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
                colorFilter = ColorFilter.tint(widgetIconTint(opacity)),
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

/**
 * Same treatment for the secondary transport buttons. CircleIconButton has
 * no size parameter of its own and defaults to a fixed 48dp regardless of
 * what the caller expects — that mismatch (the row's width math assumed a
 * smaller, PlayPauseButton-matching size) is what let the row overflow its
 * own widget bounds and clip. Sizing it explicitly, driven by the same
 * value PlayPauseButton gets, is what makes the row's own width budget
 * (see PlayerWidget) actually correct.
 */
@Composable
internal fun TransportButton(
    icon: Int,
    label: String,
    opacity: Int,
    onClick: androidx.glance.action.Action,
    sizeDp: Int = 44
) {
    if (opacity <= 0) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = GlanceModifier.size(sizeDp.dp).clickable(onClick)
        ) {
            Image(
                provider = ImageProvider(icon),
                contentDescription = label,
                colorFilter = ColorFilter.tint(widgetIconTint(opacity)),
                modifier = GlanceModifier.size((sizeDp * 3 / 4).dp)
            )
        }
    } else {
        CircleIconButton(
            imageProvider = ImageProvider(icon),
            contentDescription = label,
            onClick = onClick,
            modifier = GlanceModifier.size(sizeDp.dp)
        )
    }
}

@Composable
internal fun ArtworkOrGlyph(art: Bitmap?, sizeDp: Int, opacity: Int = 100) {
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
            Text("▂▄▆", style = TextStyle(color = widgetIconTint(opacity)))
        }
    }
}

// ---- PLAYER: art + text + progress + full transport ------------------------

class StepcastWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    // Buttons drop by tier as the widget narrows (seek pair below 170dp,
    // Done below 230dp; play/pause always survives). Within whichever tier
    // is shown, PlayerWidget sizes the buttons to fit that tier's own
    // declared width rather than assuming a fixed size — CircleIconButton's
    // built-in default (48dp) is wider than the row's old width math
    // assumed, so at these exact breakpoints the row used to demand more
    // space than the tier promised and got clipped by the widget's actual
    // bounds.
    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(110.dp, 100.dp),
            DpSize(170.dp, 100.dp),
            DpSize(230.dp, 100.dp)
        )
    )

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
                ArtworkOrGlyph(state.art, sizeDp = 56, opacity = opacity)
                Spacer(GlanceModifier.width(10.dp))
                Column(GlanceModifier.defaultWeight().widgetTextScrim(opacity)) {
                    Text(
                        state.title,
                        style = TextStyle(
                            fontWeight = FontWeight.Medium,
                            color = widgetTextColor(opacity)
                        ),
                        maxLines = 2
                    )
                    if (state.podcast.isNotEmpty()) {
                        Text(
                            state.podcast,
                            style = TextStyle(color = widgetSecondaryTextColor(opacity)),
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
                    // the TRACK is the full-width part: as an opaque
                    // surfaceVariant on a clear widget it read as a big
                    // white slab, louder than the episode itself
                    backgroundColor = if (widgetIsFloating(opacity)) {
                        ColorProvider(day = Color(0x59FFFFFF), night = Color(0x59FFFFFF))
                    } else {
                        GlanceTheme.colors.surfaceVariant
                    }
                )
                Spacer(GlanceModifier.height(10.dp))
            }
            val width = androidx.glance.LocalSize.current.width
            // same setting that gates the notification's Done button — read
            // inside the composition so a toggle can take effect
            val showSeek = width >= 170.dp
            val showDone = width >= 230.dp && AppSettings.notificationDoneButton
            val buttonCount = 1 + (if (showSeek) 2 else 0) + (if (showDone) 1 else 0)
            val spacerCount = buttonCount - 1
            // fit whatever's shown into the row's real content budget (the
            // Column's 14dp horizontal padding on each side) instead of
            // assuming a fixed button size — see the sizeMode comment above
            val buttonSizeDp = ((width - 28.dp - 10.dp * spacerCount) / buttonCount)
                .coerceIn(32.dp, 44.dp)
                .value
                .toInt()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = GlanceModifier.fillMaxWidth()
            ) {
                // narrow widgets drop buttons instead of clipping the LAST
                // one off-screen (Done was silently unreachable)
                if (showSeek) {
                    TransportButton(
                        R.drawable.ic_notif_replay,
                        "Seek back",
                        opacity,
                        actionRunCallback<SeekBackAction>(),
                        sizeDp = buttonSizeDp
                    )
                    Spacer(GlanceModifier.width(10.dp))
                }
                PlayPauseButton(state.isPlaying, opacity, sizeDp = buttonSizeDp)
                if (showSeek) {
                    Spacer(GlanceModifier.width(10.dp))
                    TransportButton(
                        R.drawable.ic_notif_forward,
                        "Seek forward",
                        opacity,
                        actionRunCallback<SeekForwardAction>(),
                        sizeDp = buttonSizeDp
                    )
                }
                if (showDone) {
                    Spacer(GlanceModifier.width(10.dp))
                    TransportButton(
                        R.drawable.ic_notif_done,
                        "Done: mark played, delete, next",
                        opacity,
                        actionRunCallback<DoneDeleteAction>(),
                        sizeDp = buttonSizeDp
                    )
                }
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
                        ArtworkOrGlyph(state.art, sizeDp = 44, opacity = opacity)
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
                        ArtworkOrGlyph(state.art, sizeDp = 44, opacity = opacity)
                        Spacer(GlanceModifier.width(10.dp))
                        Column(
                            GlanceModifier.defaultWeight().widgetTextScrim(opacity)
                        ) {
                            Text(
                                state.title.ifEmpty { "Nothing playing" },
                                style = TextStyle(
                                    fontWeight = FontWeight.Medium,
                                    color = widgetTextColor(opacity)
                                ),
                                maxLines = 1
                            )
                            if (state.podcast.isNotEmpty()) {
                                Text(
                                    state.podcast,
                                    style = TextStyle(
                                        color = widgetSecondaryTextColor(opacity)
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
                                style = TextStyle(color = widgetIconTint(opacity))
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
                        colorFilter = ColorFilter.tint(widgetIconTint(opacity)),
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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = GlanceModifier.widgetTextScrim(opacity)
        ) {
            Text("▂▄▆", style = TextStyle(color = widgetIconTint(opacity)))
            Spacer(GlanceModifier.height(4.dp))
            Text(
                "Nothing playing",
                style = TextStyle(
                    fontWeight = FontWeight.Medium,
                    color = widgetTextColor(opacity)
                )
            )
            Text(
                "Tap to open Stepcast",
                style = TextStyle(color = widgetSecondaryTextColor(opacity))
            )
        }
    }
}

// ---- actions ---------------------------------------------------------------

/**
 * Play/pause is a BROADCAST callback, not an activity start. It must be:
 * activity PendingIntents fired from a lock-screen widget make the host
 * demand an unlock first, no matter what showWhenLocked says — only
 * broadcast taps fire while locked (One UI lock-screen widgets, verified
 * on-device). The Android 12+ problem that originally forced the activity
 * trampoline — launcher PendingIntents carry no FGS allowlist, so a play
 * from a background callback can't promote the service — is solved inside
 * [PlayPauseAction] instead: every command goes through the bound
 * controller, targeted at our own session. PAUSE never needs FGS; PLAY
 * reaches playback resumption on a dead session all the same (see
 * [resumeStepcastPlayback]), with the old global media key demoted to a
 * fallback for the case where the system refuses the targeted start.
 */
class PlayPauseAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        var startedPlay = false
        // 2s hold: a cold play resolves through onPlaybackResumption, which
        // rebuilds the queue from disk before playback starts
        sendPlayerCommand(context, holdMs = 2_000) { controller ->
            if (controller.isPlaying) {
                controller.pause()
            } else {
                startedPlay = true
                resumeStepcastPlayback(context, controller)
            }
        }
        // flip the glyph immediately from this side of the tap — the
        // service's own publish converges the real state right after, but
        // must not be the only thing standing between tap and feedback
        val prefs = context.getSharedPreferences(StepcastWidget.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(StepcastWidget.KEY_PLAYING, startedPlay).apply()
        runCatching { updateAllStepcastWidgets(context) }
        // a play can silently fail (nothing to resume, focus denied,
        // network error) and then nothing ever re-publishes — reconcile
        // the optimistic glyph against reality after a beat
        if (startedPlay) {
            kotlinx.coroutines.delay(1_500)
            var actuallyPlaying = false
            sendPlayerCommand(context) { controller ->
                actuallyPlaying = controller.isPlaying
            }
            if (!actuallyPlaying) {
                // last resort: the old global media-key route. It can be
                // stolen by whatever app most recently held a session (the
                // reason it is no longer the primary path), but if the
                // targeted play was refused outright — e.g. a background
                // foreground-service start the system declined — the key's
                // system pipeline carries an exemption that our own bound
                // controller does not.
                com.stepcast.app.data.PlaybackJournal.log(
                    "widget", "controller play didn't start; falling back to media key"
                )
                dispatchPlayMediaKey(context)
                kotlinx.coroutines.delay(1_500)
                sendPlayerCommand(context) { controller ->
                    actuallyPlaying = controller.isPlaying
                }
            }
            if (!actuallyPlaying) {
                // the direct evidence for "widget play did nothing" — by
                // here BOTH the targeted controller play and the media-key
                // fallback have been tried and neither produced playback
                com.stepcast.app.data.PlaybackJournal.log(
                    "mediakey", "play failed: controller and media key both did nothing"
                )
                prefs.edit().putBoolean(StepcastWidget.KEY_PLAYING, false).apply()
                runCatching { updateAllStepcastWidgets(context) }
            }
        }
    }
}

/**
 * Starts playback targeted at STEPCAST — in BOTH the warm and cold cases.
 *
 * A global media key goes to whichever media session the system saw most
 * recently, which is not necessarily ours: anything else that took audio
 * focus in the meantime (see the "suppress transient-focus-loss" journal
 * tag) steals the key, and our play silently does nothing. That was already
 * fixed for a live session; the DEAD-session case still fell back to the
 * global key and so still lost the race.
 *
 * It turns out the key was never needed. Media3 routes a controller's
 * play() on an EMPTY timeline into
 * MediaLibrarySession.Callback.onPlaybackResumption — the very callback
 * that rebuilds the queue from disk — so a plain play() on our own bound
 * controller reaches the same revival path the media key was chasing,
 * addressed straight at our session where nothing can intercept it.
 */
internal fun resumeStepcastPlayback(context: Context, controller: MediaController) {
    val cold = controller.currentMediaItem == null
    com.stepcast.app.data.PlaybackJournal.log(
        "widget", if (cold) "play via controller (cold, expect resume)" else "play via controller"
    )
    controller.play()
}

/**
 * FALLBACK ONLY — prefer [resumeStepcastPlayback]. Injects
 * KEYCODE_MEDIA_PLAY into the system media-key pipeline, whose FGS
 * exemption can start playback where a background-initiated one is
 * refused. The trade-off is global routing: the system hands the key to
 * the most recent media session, which is ours only if nothing else has
 * taken audio focus since — the observed cause of widget taps silently
 * doing nothing. Plain PLAY, not PLAY_PAUSE, so a misrouted key is a
 * no-op in an already-playing app instead of pausing it.
 */
internal fun dispatchPlayMediaKey(context: Context) {
    com.stepcast.app.data.PlaybackJournal.log("mediakey", "dispatch PLAY")
    val audioManager = context.applicationContext
        .getSystemService(android.media.AudioManager::class.java) ?: return
    val code = android.view.KeyEvent.KEYCODE_MEDIA_PLAY
    audioManager.dispatchMediaKeyEvent(
        android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code)
    )
    audioManager.dispatchMediaKeyEvent(
        android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code)
    )
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
    holdMs: Long = 300,
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
            // let the command dispatch before releasing the controller.
            // A cold play needs much longer than a transport tap: it lands
            // in onPlaybackResumption, which reads the episode and the whole
            // queue off disk before playback can begin, and dropping the
            // last bound controller mid-rebuild risks the service being
            // torn down before it ever reaches play.
            delay(holdMs)
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
