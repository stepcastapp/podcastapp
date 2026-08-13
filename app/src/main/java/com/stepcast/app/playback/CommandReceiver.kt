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
                // hard-bounded: goAsync gives roughly a 10s budget before
                // the system may kill the receiver — a cold-start controller
                // connect plus the 8s command wait could blow past it
                withTimeoutOrNull(7_000) {
                    handle(context.applicationContext, app, action, smartPlayName)
                }
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
        com.stepcast.app.data.PlaybackJournal.log(
            "automation",
            "action=${action.substringAfterLast('.')}" +
                (smartPlayName?.let { " name=$it" } ?: "")
        )
        if (action == ACTION_START_SMART_PLAY) {
            // NOT an activity-start route: launching PlaybackTrampolineActivity
            // from here was tried and confirmed dead — a bare BroadcastReceiver
            // has no background-activity-start allowlist either (a *different*
            // restriction than the foreground-service one below, but just as
            // strict), so context.startActivity() from this receiver is
            // silently blocked by the OS and the activity's onCreate never
            // even runs.
            val name = smartPlayName ?: return
            startSmartPlay(context, name)
            return
        }
        withController(context) { controller ->
            when (action) {
                // internal (Settings toggle): re-apply notification buttons now
                ACTION_REFRESH_NOTIF_BUTTONS -> controller.sendCustomCommand(
                    SessionCommand(PlaybackService.ACTION_REFRESH_NOTIF_BUTTONS, Bundle.EMPTY),
                    Bundle.EMPTY
                )
                // resumeStepcastPlayback: targeted play through the bound
                // controller when our session is alive (a global media key
                // resumes whichever app played most recently), media-key
                // revival only when it's dead. Pause never needs FGS.
                ACTION_PLAY -> {
                    if (!controller.isPlaying) {
                        com.stepcast.app.widget.resumeStepcastPlayback(context, controller)
                    }
                    null
                }
                ACTION_PAUSE -> { controller.pause(); null }
                ACTION_TOGGLE -> {
                    if (controller.isPlaying) {
                        controller.pause()
                    } else {
                        com.stepcast.app.widget.resumeStepcastPlayback(context, controller)
                    }
                    null
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

    /**
     * Fills the queue via the bound controller (as before), but doesn't let
     * go the instant the custom command "succeeds" — that result only means
     * the queue filled and play() was requested, not that the service
     * actually reached foreground. Releasing the last bound client right
     * then let the OS kill the still-not-foreground service outright
     * (journal: pwr reason=focus-loss immediately followed by a full
     * "pos destroy", not just a pause). So: hold the controller, check
     * whether playback actually landed, and if not, fall back to the
     * system media-key pipeline — the same FGS-start exemption
     * resumeStepcastPlayback's fallback already relies on (see
     * dispatchPlayMediaKey) — before releasing. The key carries no
     * SmartPlay name, but none is needed by then: the queue is already
     * filled, so a plain resume is all the fallback has to do.
     */
    private suspend fun startSmartPlay(context: Context, name: String) {
        val token = SessionToken(
            context, ComponentName(context, PlaybackService::class.java)
        )
        val controller = MediaController.Builder(context, token).buildAsync().await()
        try {
            val future = controller.sendCustomCommand(
                SessionCommand(PlaybackService.ACTION_START_SMARTPLAY, Bundle.EMPTY),
                Bundle().apply { putString(PlaybackService.KEY_SMARTPLAY_NAME, name) }
            )
            val result = withTimeoutOrNull(4_000) { future.await() }
            com.stepcast.app.data.PlaybackJournal.log(
                "automation",
                "result=" + (result?.resultCode?.toString() ?: "timeout")
            )
            delay(1_000)
            if (!controller.isPlaying) {
                com.stepcast.app.data.PlaybackJournal.log(
                    "automation", "smartplay queued but not playing; falling back to media key"
                )
                com.stepcast.app.widget.dispatchPlayMediaKey(context)
                delay(1_000)
            }
        } finally {
            controller.release()
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
                val result = withTimeoutOrNull(8_000) { future.await() }
                com.stepcast.app.data.PlaybackJournal.log(
                    "automation",
                    "result=" + (result?.resultCode?.toString() ?: "timeout")
                )
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
