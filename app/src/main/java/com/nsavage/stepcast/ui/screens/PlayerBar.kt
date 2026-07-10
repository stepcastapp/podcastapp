package com.nsavage.stepcast.ui.screens

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import com.nsavage.stepcast.R
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.vector.ImageVector
import com.nsavage.stepcast.data.AppSettings
import com.nsavage.stepcast.ui.PlayerConnection
import com.nsavage.stepcast.ui.PlayerUiState
import com.nsavage.stepcast.ui.progressBorder

/** Best-matching Material glyph for the configured seek-back increment. */
internal fun seekBackIcon(seconds: Int): ImageVector = when (seconds) {
    5 -> Icons.Rounded.Replay5
    10 -> Icons.Rounded.Replay10
    30 -> Icons.Rounded.Replay30
    else -> Icons.Rounded.Replay
}

/** Best-matching Material glyph for the configured seek-forward increment. */
internal fun seekForwardIcon(seconds: Int): ImageVector = when (seconds) {
    5 -> Icons.Rounded.Forward5
    10 -> Icons.Rounded.Forward10
    30 -> Icons.Rounded.Forward30
    else -> Icons.Rounded.FastForward
}

/** Floating signal-colored pill above the nav bar. Tap or swipe up to expand. */
@Composable
fun PlayerBar(state: PlayerUiState, player: PlayerConnection, onExpand: () -> Unit) {
    val progress by player.progress.collectAsState()
    val view = androidx.compose.ui.platform.LocalView.current
    // pointerInput(Unit) never restarts — read the callback via live state
    val currentOnExpand by androidx.compose.runtime.rememberUpdatedState(onExpand)
    Surface(
        onClick = onExpand,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 6.dp,
        modifier = Modifier
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .pointerInput(Unit) {
                var triggered = false
                detectVerticalDragGestures(
                    onDragStart = { triggered = false }
                ) { _, dragAmount ->
                    if (!triggered && dragAmount < -12f) {
                        triggered = true
                        currentOnExpand()
                    }
                }
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            AsyncImage(
                model = state.artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .progressBorder(
                        fraction = progress.fraction,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onPrimaryContainer
                            .copy(alpha = 0.35f),
                        strokeWidth = 3.5.dp
                    )
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 10.dp)
            ) {
                Text(
                    state.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    state.podcastTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = player::seekBack) {
                Icon(
                    seekBackIcon(AppSettings.seekBackSeconds),
                    contentDescription = stringResource(
                        R.string.back_seconds_compact_cd, AppSettings.seekBackSeconds
                    )
                )
            }
            IconButton(onClick = {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                player.togglePlayPause()
            }) {
                Icon(
                    if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(R.string.play_pause),
                    modifier = Modifier.size(30.dp)
                )
            }
            IconButton(onClick = player::seekForward) {
                Icon(
                    seekForwardIcon(AppSettings.seekForwardSeconds),
                    contentDescription = stringResource(
                        R.string.forward_seconds_compact_cd, AppSettings.seekForwardSeconds
                    )
                )
            }
            IconButton(onClick = {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                player.skipToNextAndDelete()
            }) {
                Icon(
                    Icons.Rounded.DeleteSweep,
                    contentDescription = stringResource(R.string.done_mark_played_delete_next)
                )
            }
        }
    }
}
