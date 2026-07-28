package com.stepcast.app

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.stepcast.app.playback.PlaybackTrampolineActivity

/**
 * Publishes each SmartPlay as a launcher shortcut (long-press the app icon,
 * then drag one to the home screen for a one-tap playlist starter). Kept in
 * sync with the SmartPlay list by [StepcastApplication].
 */
object SmartPlayShortcuts {

    const val ACTION_START = "com.stepcast.app.shortcut.SMARTPLAY"

    fun publish(context: Context, plays: List<com.stepcast.app.data.SmartPlay>) {
        runCatching {
            val max = ShortcutManagerCompat
                .getMaxShortcutCountPerActivity(context)
                .coerceIn(1, 4)
            // keyed by ID, not name: renaming a SmartPlay must update an
            // already-PINNED shortcut in place — a name-keyed pin kept
            // pointing at the old name and silently played nothing
            val shortcuts = plays.take(max).map { play ->
                val name = play.name
                ShortcutInfoCompat.Builder(context, "smartplay-${play.id}")
                    .setShortLabel(name.ifBlank { "SmartPlay" })
                    .setLongLabel("Play $name")
                    .setIcon(
                        IconCompat.createWithResource(context, R.mipmap.ic_launcher)
                    )
                    .setIntent(
                        // the invisible trampoline, NOT MainActivity: a
                        // shortcut tap should start playback without opening
                        // the app (and never leaves a stale launch intent for
                        // a later recreation to replay). Re-publishing under
                        // the same ids updates already-pinned shortcuts;
                        // MainActivity keeps its legacy ACTION_START handler
                        // for any pin that hasn't refreshed yet.
                        Intent(context, PlaybackTrampolineActivity::class.java)
                            .setAction(ACTION_START)
                            .setData(
                                android.net.Uri.parse("stepcast://smartplay/id/${play.id}")
                            )
                            // id is authoritative (survives renames); the name
                            // rides along for older handlers
                            .putExtra("smartplay", name)
                            .putExtra("smartplayId", play.id)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        }
    }
}
