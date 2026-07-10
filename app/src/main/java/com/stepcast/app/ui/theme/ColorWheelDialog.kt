package com.stepcast.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import com.stepcast.app.R
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Classic HSV picker: a hue/saturation wheel plus a brightness slider.
 * Backs the "Custom" accent — the picked color feeds [customSpec]-derived
 * theme roles via [ThemePrefs.setCustomAccent].
 */
@Composable
fun ColorWheelDialog(
    initialArgb: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit
) {
    val initialHsv = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initialArgb, it) }
    }
    var hue by remember { mutableStateOf(initialHsv[0]) }
    var sat by remember { mutableStateOf(initialHsv[1]) }
    var value by remember { mutableStateOf(initialHsv[2].coerceIn(0.35f, 1f)) }
    val picked = Color.hsv(hue, sat, value)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pick_an_accent)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(240.dp)
                        .pointerInput(Unit) {
                            fun update(position: Offset) {
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val dx = position.x - cx
                                val dy = position.y - cy
                                val radius = min(cx, cy)
                                val degrees = Math
                                    .toDegrees(atan2(dy, dx).toDouble())
                                    .toFloat()
                                hue = (degrees + 360f) % 360f
                                sat = (sqrt(dx * dx + dy * dy) / radius)
                                    .coerceIn(0f, 1f)
                            }
                            detectDragGestures(
                                onDragStart = { update(it) }
                            ) { change, _ ->
                                change.consume()
                                update(change.position)
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { position ->
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val dx = position.x - cx
                                val dy = position.y - cy
                                val radius = min(cx, cy)
                                val degrees = Math
                                    .toDegrees(atan2(dy, dx).toDouble())
                                    .toFloat()
                                hue = (degrees + 360f) % 360f
                                sat = (sqrt(dx * dx + dy * dy) / radius)
                                    .coerceIn(0f, 1f)
                            }
                        }
                ) {
                    Canvas(Modifier.size(240.dp)) {
                        val radius = size.minDimension / 2f
                        // hue around the rim; sweep angle 0 sits at 3 o'clock,
                        // matching the atan2 mapping above
                        drawCircle(
                            Brush.sweepGradient(
                                List(13) { i -> Color.hsv((i * 30f) % 360f, 1f, 1f) }
                            ),
                            radius = radius
                        )
                        // saturation falls to white at the center
                        drawCircle(
                            Brush.radialGradient(
                                listOf(Color.White, Color(0x00FFFFFF))
                            ),
                            radius = radius
                        )
                        // brightness dims the whole wheel
                        drawCircle(
                            Color.Black.copy(alpha = 1f - value),
                            radius = radius
                        )
                        // selection marker
                        val angleRad = Math.toRadians(hue.toDouble())
                        val markerCenter = Offset(
                            center.x + (sat * radius * cos(angleRad)).toFloat(),
                            center.y + (sat * radius * sin(angleRad)).toFloat()
                        )
                        drawCircle(
                            Color.Black.copy(alpha = 0.6f),
                            radius = 10.dp.toPx(),
                            center = markerCenter,
                            style = Stroke(4.dp.toPx())
                        )
                        drawCircle(
                            Color.White,
                            radius = 8.dp.toPx(),
                            center = markerCenter,
                            style = Stroke(3.dp.toPx())
                        )
                    }
                }
                Text(
                    stringResource(R.string.brightness),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                )
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = 0.35f..1f
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(picked)
                    )
                    Text(
                        stringResource(R.string.s_06x).format(picked.toArgb() and 0xFFFFFF),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(picked.toArgb()) }) { Text(stringResource(R.string.use_this_color)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
