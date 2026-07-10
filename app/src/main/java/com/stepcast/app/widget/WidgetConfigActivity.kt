package com.stepcast.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.stepcast.app.R
import androidx.compose.ui.unit.dp
import com.stepcast.app.data.AppSettings
import com.stepcast.app.ui.theme.StepcastTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Per-widget settings, launched from the widget's reconfigure affordance
 * (or on placement). Currently one knob: background opacity, overriding
 * the global Settings default for just this widget.
 */
class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_CANCELED, result)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        com.stepcast.app.ui.theme.ThemePrefs.init(this)
        AppSettings.init(this)

        val prefs = getSharedPreferences(StepcastWidget.PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getInt("opacity_$appWidgetId", -1)

        setContent {
            StepcastTheme {
                Surface(Modifier.fillMaxSize()) {
                    var chosen by remember {
                        mutableStateOf(saved.takeIf { it in 0..100 })
                    }
                    Column(
                        Modifier
                            .systemBarsPadding()
                            .padding(24.dp)
                    ) {
                        Text(
                            stringResource(R.string.this_widget_s_background),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Overrides the global widget opacity for this " +
                                "widget only",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = chosen == null,
                                onClick = { chosen = null },
                                label = {
                                    Text("Global (${AppSettings.widgetOpacity}%)")
                                }
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            for (pct in listOf(100, 75, 50, 25, 0)) {
                                FilterChip(
                                    selected = chosen == pct,
                                    onClick = { chosen = pct },
                                    label = { Text(if (pct == 0) "Clear" else "$pct%") }
                                )
                            }
                        }
                        Button(
                            onClick = {
                                prefs.edit().apply {
                                    val pick = chosen
                                    if (pick == null) {
                                        remove("opacity_$appWidgetId")
                                    } else {
                                        putInt("opacity_$appWidgetId", pick)
                                    }
                                }.apply()
                                val appContext = applicationContext
                                CoroutineScope(Dispatchers.Default).launch {
                                    runCatching { updateAllStepcastWidgets(appContext) }
                                }
                                setResult(RESULT_OK, result)
                                finish()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 20.dp)
                        ) { Text(stringResource(R.string.done)) }
                    }
                }
            }
        }
    }
}
