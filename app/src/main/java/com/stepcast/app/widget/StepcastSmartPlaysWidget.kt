package com.stepcast.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.Image
import com.stepcast.app.R
import com.stepcast.app.StepcastApplication
import com.stepcast.app.playback.CommandReceiver
import com.stepcast.app.ui.MainActivity

/**
 * Home-screen SmartPlay launcher: one tap fills the queue from a
 * SmartPlay's rules and starts playing, without opening the app first.
 */
class StepcastSmartPlaysWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StepcastSmartPlaysWidget()
}

class StepcastSmartPlaysWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as StepcastApplication
        val names = runCatching {
            app.repository.smartPlayList().map { it.name }
        }.getOrDefault(emptyList())
        val opacity = widgetOpacity(context, id)
        provideContent {
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
                            color = GlanceTheme.colors.primary
                        ),
                        modifier = GlanceModifier
                            .clickable(actionStartActivity<MainActivity>())
                    )
                    Spacer(GlanceModifier.height(4.dp))
                    if (names.isEmpty()) {
                        Box(
                            GlanceModifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No SmartPlays yet — create one on the Up Next tab",
                                style = TextStyle(
                                    color = GlanceTheme.colors.onSurfaceVariant
                                )
                            )
                        }
                    }
                    for (name in names.take(6)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable(
                                    actionRunCallback<StartSmartPlayAction>(
                                        actionParametersOf(SMARTPLAY_NAME_KEY to name)
                                    )
                                )
                        ) {
                            Image(
                                provider = ImageProvider(R.drawable.ic_notif_play),
                                contentDescription = "Play $name",
                                modifier = GlanceModifier.size(20.dp)
                            )
                            Spacer(GlanceModifier.width(10.dp))
                            Text(
                                name,
                                style = TextStyle(
                                    fontWeight = FontWeight.Medium,
                                    color = GlanceTheme.colors.onSurface
                                ),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        val SMARTPLAY_NAME_KEY = ActionParameters.Key<String>("smartplayName")
    }
}

/** Routes through CommandReceiver — the same path as external automation. */
class StartSmartPlayAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val name = parameters[StepcastSmartPlaysWidget.SMARTPLAY_NAME_KEY] ?: return
        context.sendBroadcast(
            Intent(context, CommandReceiver::class.java)
                .setAction(CommandReceiver.ACTION_START_SMART_PLAY)
                .putExtra("smartplay", name)
        )
    }
}
