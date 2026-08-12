package com.stepcast.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.Image
import com.stepcast.app.R
import com.stepcast.app.StepcastApplication
import com.stepcast.app.ui.MainActivity

/**
 * Home-screen SmartPlay launcher: one tap fills the queue from a
 * SmartPlay's rules and starts playing, without opening the app first.
 */
class StepcastSmartPlaysWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StepcastSmartPlaysWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        removeWidgetOpacityPrefs(context, appWidgetIds)
    }
}

class StepcastSmartPlaysWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // read INSIDE the composition — Glance keeps the session alive
            // between updates, so anything read out here in provideGlance
            // would be frozen for the widget's lifetime (see the note on
            // updateAllStepcastWidgets, which seeds this state)
            val plays = smartPlaysFrom(context, currentState())
            val opacity = widgetOpacity(context, id)
            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(widgetBackgroundColor(opacity))
                        .cornerRadius(20.dp)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        "SmartPlays",
                        style = TextStyle(
                            fontWeight = FontWeight.Bold,
                            color = widgetIconTint(opacity)
                        ),
                        modifier = GlanceModifier
                            .widgetTextScrim(opacity)
                            .clickable(actionStartActivity<MainActivity>())
                    )
                    Spacer(GlanceModifier.height(4.dp))
                    if (plays.isEmpty()) {
                        Box(
                            GlanceModifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No SmartPlays yet — create one on the Up Next tab",
                                style = TextStyle(
                                    color = widgetSecondaryTextColor(opacity)
                                ),
                                modifier = GlanceModifier.widgetTextScrim(opacity)
                            )
                        }
                    }
                    for ((playId, name) in plays.take(6)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable(
                                    // an activity start (not a broadcast) so the
                                    // service may go foreground from a background
                                    // tap — see PlaybackTrampolineActivity. The
                                    // unique data URI keeps each row's
                                    // PendingIntent distinct (equal-extras
                                    // intents would collapse to one). The ID is
                                    // authoritative (survives renames); the name
                                    // rides along as a fallback for old widgets.
                                    androidx.glance.appwidget.action.actionStartActivity(
                                        Intent(
                                            context,
                                            com.stepcast.app.playback
                                                .PlaybackTrampolineActivity::class.java
                                        )
                                            .setData(
                                                android.net.Uri.parse(
                                                    "stepcast://smartplay/$playId/" +
                                                        android.net.Uri.encode(name)
                                                )
                                            )
                                            .putExtra("smartplay", name)
                                            .putExtra("smartplayId", playId)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                )
                        ) {
                            Image(
                                provider = ImageProvider(R.drawable.ic_notif_play),
                                contentDescription = "Play $name",
                                // the drawable is hardcoded white, so it was
                                // invisible on a light panel until now
                                colorFilter = androidx.glance.ColorFilter.tint(
                                    widgetIconTint(opacity)
                                ),
                                modifier = GlanceModifier.size(20.dp)
                            )
                            Spacer(GlanceModifier.width(10.dp))
                            Text(
                                name,
                                style = TextStyle(
                                    fontWeight = FontWeight.Medium,
                                    color = widgetTextColor(opacity)
                                ),
                                maxLines = 1,
                                modifier = GlanceModifier.widgetTextScrim(opacity)
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        /**
         * "\n"-joined "id|name" lines, seeded by updateAllStepcastWidgets.
         * (Pre-0.5 state was bare names — the parser treats a line without
         * '|' as id 0, which falls back to name lookup in the trampoline.)
         */
        val P_SMARTPLAY_NAMES = stringPreferencesKey("smartPlayNames")
    }
}

/**
 * (id, name) pairs for one render: the widget's Glance state, falling back
 * to a one-time direct load for a freshly placed widget that hasn't been
 * seeded yet (same shape as widgetStateFrom's fallback).
 */
private fun smartPlaysFrom(context: Context, prefs: Preferences): List<Pair<Long, String>> {
    val joined = prefs[StepcastSmartPlaysWidget.P_SMARTPLAY_NAMES]
        ?: return runCatching {
            kotlinx.coroutines.runBlocking {
                (context.applicationContext as StepcastApplication)
                    .repository.smartPlayList().map { it.id to it.name }
            }
        }.getOrDefault(emptyList())
    return joined.split("\n").filter { it.isNotEmpty() }.map { line ->
        val id = line.substringBefore('|', "").toLongOrNull() ?: 0L
        val name = if (line.contains('|')) line.substringAfter('|') else line
        id to name
    }
}
