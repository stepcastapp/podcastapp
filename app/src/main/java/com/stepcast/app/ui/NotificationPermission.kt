package com.stepcast.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.content.ContextCompat

/**
 * POST_NOTIFICATIONS is asked for IN CONTEXT — when the user turns on
 * new-episode alerts, or after their first subscription with a one-line
 * explanation — never cold on first launch (Play's guidance, and a
 * cold prompt is the one most people deny). Playback's media
 * notification is exempt and never needs it.
 */
object NotificationPermission {

    fun granted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    private const val PREFS = "stepcast_flags"
    private const val KEY_ASKED = "notifPermissionAsked"

    /** Whether the one-time explained ask already happened. */
    fun asked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ASKED, true).apply()
    }
}

/** Returns a function that asks for the permission (no-op when not needed). */
@Composable
fun rememberNotificationPermissionRequest(onResult: (Boolean) -> Unit = {}): (Context) -> Unit {
    val callback = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> callback.value(granted) }
    return { context ->
        if (NotificationPermission.granted(context)) {
            callback.value(true)
        } else {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
