package com.stepcast.app.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Stepcast's signature progress indicator: a rounded border tracing the
 * artwork's edge, starting at 12 o'clock and filling clockwise with the
 * played fraction over a faint track.
 */
fun Modifier.progressBorder(
    fraction: Float,
    color: Color,
    trackColor: Color = Color.White.copy(alpha = 0.25f),
    strokeWidth: Dp = 3.dp,
    cornerRadius: Dp = 10.dp
): Modifier = drawWithContent {
    drawContent()

    val stroke = strokeWidth.toPx()
    val inset = stroke / 2f
    val left = inset
    val top = inset
    val right = size.width - inset
    val bottom = size.height - inset
    if (right <= left || bottom <= top) return@drawWithContent
    val cr = (cornerRadius.toPx() - inset).coerceAtLeast(1f)
    val cx = size.width / 2f

    // Hand-built clockwise path from top-center so the fill genuinely
    // starts at 12 o'clock regardless of platform path conventions.
    val path = Path().apply {
        moveTo(cx, top)
        lineTo(right - cr, top)
        arcTo(Rect(right - 2 * cr, top, right, top + 2 * cr), -90f, 90f, false)
        lineTo(right, bottom - cr)
        arcTo(Rect(right - 2 * cr, bottom - 2 * cr, right, bottom), 0f, 90f, false)
        lineTo(left + cr, bottom)
        arcTo(Rect(left, bottom - 2 * cr, left + 2 * cr, bottom), 90f, 90f, false)
        lineTo(left, top + cr)
        arcTo(Rect(left, top, left + 2 * cr, top + 2 * cr), 180f, 90f, false)
        close()
    }

    val style = Stroke(width = stroke, cap = StrokeCap.Round)
    drawPath(path, trackColor, style = style)

    val f = fraction.coerceIn(0f, 1f)
    if (f > 0f) {
        val measure = PathMeasure().apply { setPath(path, false) }
        val segment = Path()
        if (measure.getSegment(0f, measure.length * f, segment, true)) {
            drawPath(segment, color, style = style)
        }
    }
}
