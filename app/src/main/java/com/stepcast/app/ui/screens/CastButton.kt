package com.stepcast.app.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.google.android.gms.cast.framework.CastState
import com.stepcast.app.R
import com.stepcast.app.playback.CastSupport

/**
 * Cast to a TV / speaker. Hidden without Google Play services or when no
 * Cast device is on the network. The stock MediaRouteButton insists on a
 * FragmentActivity; this opens the same MediaRouter dialogs directly.
 */
@Composable
fun CastButton() {
    val context = LocalContext.current
    val castContext = remember { CastSupport.castContext(context) } ?: return
    var state by remember { mutableIntStateOf(castContext.castState) }
    DisposableEffect(castContext) {
        val listener = com.google.android.gms.cast.framework.CastStateListener { state = it }
        castContext.addCastStateListener(listener)
        onDispose { castContext.removeCastStateListener(listener) }
    }
    if (state == CastState.NO_DEVICES_AVAILABLE) return
    val connected = state == CastState.CONNECTED || state == CastState.CONNECTING
    IconButton(onClick = {
        // MediaRouter dialogs are AppCompat dialogs: give them that theme
        val themed = android.view.ContextThemeWrapper(
            context, androidx.appcompat.R.style.Theme_AppCompat_DayNight_Dialog
        )
        val selector = castContext.mergedSelector ?: return@IconButton
        runCatching {
            if (connected) {
                androidx.mediarouter.app.MediaRouteControllerDialog(themed).show()
            } else {
                androidx.mediarouter.app.MediaRouteChooserDialog(themed).apply {
                    routeSelector = selector
                }.show()
            }
        }
    }) {
        Icon(
            if (connected) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
            contentDescription = stringResource(R.string.cast),
            tint = if (connected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}
