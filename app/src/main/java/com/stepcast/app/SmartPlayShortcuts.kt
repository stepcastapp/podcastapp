package com.stepcast.app

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.stepcast.app.ui.MainActivity

/**
 * Publishes each SmartPlay as a launcher shortcut (long-press the app icon,
 * then drag one to the home screen for a one-tap playlist starter). Kept in
 * sync with the SmartPlay list by [StepcastApplication].
 */
object SmartPlayShortcuts {

    const val ACTION_START = "com.stepcast.app.shortcut.SMARTPLAY"

    fun publish(context: Context, names: List<String>) {
        runCatching {
            val max = ShortcutManagerCompat
                .getMaxShortcutCountPerActivity(context)
                .coerceIn(1, 4)
            val shortcuts = names.take(max).map { name ->
                ShortcutInfoCompat.Builder(context, "smartplay-$name")
                    .setShortLabel(name.ifBlank { "SmartPlay" })
                    .setLongLabel("Play $name")
                    .setIcon(
                        IconCompat.createWithResource(context, R.mipmap.ic_launcher)
                    )
                    .setIntent(
                        Intent(context, MainActivity::class.java)
                            .setAction(ACTION_START)
                            .putExtra("smartplay", name)
                            .addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                            )
                    )
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        }
    }
}
