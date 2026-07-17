package com.stepcast.app.playback

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Invisible (translucent, zero-UI) activity that widget playback buttons
 * launch instead of sending a background broadcast.
 *
 * Why it exists: launcher-delivered widget PendingIntents carry NO
 * temporary foreground-service allowlist (unlike notification actions,
 * whose sender is the privileged SystemUI). So on Android 12+ a play
 * issued from a widget's background broadcast cannot promote
 * PlaybackService to foreground — Media3 catches the
 * ForegroundServiceStartNotAllowedException and pauses, and the tap looks
 * dead, leaving a primed queue that pops off whenever the app next comes
 * to the foreground. Activity starts from widgets ARE always allowed;
 * while this activity is resumed the app is foreground, so the service
 * promotes normally. It finishes as soon as the command has landed.
 *
 * Handles: extra "smartplay" = SmartPlay name (service-side queue + play),
 * or extra "command" = "TOGGLE" (play/pause; play on an empty player goes
 * through Media3 playback resumption, restoring the last queue).
 */
class PlaybackTrampolineActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreation must never replay the command (same stale-intent rule
        // as MainActivity) — with noHistory + excludeFromRecents this should
        // not happen, but guard anyway.
        if (savedInstanceState != null) {
            finish()
            return
        }
        val smartPlay = intent.getStringExtra("smartplay")
        val command = intent.getStringExtra("command")
        if (smartPlay == null && command == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            try {
                val token = SessionToken(
                    this@PlaybackTrampolineActivity,
                    ComponentName(
                        this@PlaybackTrampolineActivity,
                        PlaybackService::class.java
                    )
                )
                val controller = MediaController.Builder(
                    this@PlaybackTrampolineActivity, token
                ).buildAsync().await()
                try {
                    when {
                        smartPlay != null -> {
                            val future = controller.sendCustomCommand(
                                SessionCommand(
                                    PlaybackService.ACTION_START_SMARTPLAY,
                                    Bundle.EMPTY
                                ),
                                Bundle().apply {
                                    putString(
                                        PlaybackService.KEY_SMARTPLAY_NAME, smartPlay
                                    )
                                }
                            )
                            // stay resumed (= app foreground) until the service
                            // has queued AND started playback
                            withTimeoutOrNull(8_000) { future.await() }
                        }
                        command == "TOGGLE" -> {
                            val nowPlaying = !controller.isPlaying
                            if (controller.isPlaying) {
                                controller.pause()
                            } else {
                                controller.play()
                            }
                            // flip the glyph immediately from this side of the
                            // tap (same optimistic update PlayPauseAction did)
                            getSharedPreferences(
                                com.stepcast.app.widget.StepcastWidget.PREFS,
                                MODE_PRIVATE
                            ).edit().putBoolean(
                                com.stepcast.app.widget.StepcastWidget.KEY_PLAYING,
                                nowPlaying
                            ).apply()
                            runCatching {
                                com.stepcast.app.widget.updateAllStepcastWidgets(
                                    this@PlaybackTrampolineActivity
                                )
                            }
                            // hold the foreground window while the service
                            // promotes (or runs playback resumption on an empty
                            // player after process death); pause needs only the
                            // dispatch grace
                            delay(if (nowPlaying) 1_500 else 300)
                        }
                    }
                } finally {
                    controller.release()
                }
            } catch (_: Exception) {
                // a widget tap must never crash
            } finally {
                finish()
            }
        }
    }
}
