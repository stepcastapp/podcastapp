package com.stepcast.app.playback

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.stepcast.app.StepcastApplication
import com.stepcast.app.sync.RefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * External automation surface (Tasker, Bixby Routines, adb, anything that
 * can send a broadcast). Mirrors what BeyondPod's command intents offered:
 *
 *   adb shell am broadcast -a com.stepcast.app.command.TOGGLE \
 *       -n com.stepcast.app/.playback.CommandReceiver
 *
 * Actions: PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS, SEEK_BACK, SEEK_FORWARD,
 * DONE (mark played + delete + advance), REFRESH (all feeds), and
 * START_SMART_PLAY with string extra "smartplay" = the SmartPlay's name
 * (case-insensitive).
 */
class CommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val app = context.applicationContext as? StepcastApplication ?: return
        if (action == ACTION_REFRESH) {
            RefreshWorker.refreshNow(context.applicationContext)
            return
        }
        if (action == ACTION_REFRESH_CATEGORY) {
            // resolved case-insensitively inside the worker; the receiver
            // just hands off — network work can't live in a broadcast
            val category = intent.getStringExtra("category")
                ?: intent.getStringExtra("name")
            if (!category.isNullOrBlank()) {
                RefreshWorker.refreshCategoryNow(context.applicationContext, category)
            }
            return
        }
        val smartPlayName = intent.getStringExtra("smartplay")
            ?: intent.getStringExtra("name")
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                handle(context.applicationContext, app, action, smartPlayName)
            } catch (_: Exception) {
                // automation must never crash the app process
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(
        context: Context,
        app: StepcastApplication,
        action: String,
        smartPlayName: String?
    ) {
        if (action == ACTION_START_SMART_PLAY) {
            // ONE custom command; the service queues AND starts playback
            // entirely on its side. The old approach (setMediaItems with
            // bare ids + prepare + play from here) raced the controller
            // release: the per-episode id resolution was still in flight
            // when the grace delay ended, so the queue filled but playback
            // never started.
            val name = smartPlayName ?: return
            withController(context) { controller ->
                controller.sendCustomCommand(
                    SessionCommand(PlaybackService.ACTION_START_SMARTPLAY, Bundle.EMPTY),
                    Bundle().apply {
                        putString(PlaybackService.KEY_SMARTPLAY_NAME, name)
                    }
                )
            }
            return
        }
        withController(context) { controller ->
            when (action) {
                // internal (Settings toggle): re-apply notification buttons now
                ACTION_REFRESH_NOTIF_BUTTONS -> controller.sendCustomCommand(
                    SessionCommand(PlaybackService.ACTION_REFRESH_NOTIF_BUTTONS, Bundle.EMPTY),
                    Bundle.EMPTY
                )
                ACTION_PLAY -> { controller.play(); null }
                ACTION_PAUSE -> { controller.pause(); null }
                ACTION_TOGGLE -> {
                    if (controller.isPlaying) controller.pause() else controller.play(); null
                }
                ACTION_NEXT -> { controller.seekToNextMediaItem(); null }
                ACTION_PREVIOUS -> { controller.seekToPreviousMediaItem(); null }
                ACTION_SEEK_BACK -> { controller.seekBack(); null }
                ACTION_SEEK_FORWARD -> { controller.seekForward(); null }
                ACTION_DONE -> controller.sendCustomCommand(
                    SessionCommand(PlaybackService.ACTION_DONE_DELETE, Bundle.EMPTY),
                    Bundle.EMPTY
                )
                else -> null
            }
        }
    }

    private suspend fun withController(
        context: Context,
        command: (MediaController) -> ListenableFuture<SessionResult>?
    ) {
        val token = SessionToken(
            context, ComponentName(context, PlaybackService::class.java)
        )
        val controller = MediaController.Builder(context, token).buildAsync().await()
        try {
            val future = command(controller)
            if (future != null) {
                // wait for the service to finish the command (e.g. SmartPlay:
                // queue fill + play) before releasing the controller — releasing
                // the last client mid-start can let the OS destroy the service
                // and playback never begins. Bounded so a broadcast can't hang.
                withTimeoutOrNull(8_000) { future.await() }
            } else {
                delay(300) // transport-only command; let it dispatch
            }
        } finally {
            controller.release()
        }
    }

    companion object {
        private const val PREFIX = "com.stepcast.app.command."
        const val ACTION_PLAY = PREFIX + "PLAY"
        const val ACTION_PAUSE = PREFIX + "PAUSE"
        const val ACTION_TOGGLE = PREFIX + "TOGGLE"
        const val ACTION_NEXT = PREFIX + "NEXT"
        const val ACTION_PREVIOUS = PREFIX + "PREVIOUS"
        const val ACTION_SEEK_BACK = PREFIX + "SEEK_BACK"
        const val ACTION_SEEK_FORWARD = PREFIX + "SEEK_FORWARD"
        const val ACTION_DONE = PREFIX + "DONE"
        const val ACTION_REFRESH = PREFIX + "REFRESH"
        const val ACTION_REFRESH_CATEGORY = PREFIX + "REFRESH_CATEGORY"
        const val ACTION_START_SMART_PLAY = PREFIX + "START_SMART_PLAY"
        const val ACTION_REFRESH_NOTIF_BUTTONS = PREFIX + "REFRESH_NOTIF_BUTTONS"
    }
}
