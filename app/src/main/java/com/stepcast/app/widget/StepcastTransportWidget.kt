package com.stepcast.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.stepcast.app.R
import com.stepcast.app.ui.MainActivity

/**
 * TRANSPORT: one row tall, full width — the ring around the artwork carries
 * progress (there's no room for a separate bar at this height, and it reads
 * fine at a glance), seek-back/play-pause/seek-forward alongside it. A
 * narrower placement drops the seek pair the same way PLAYER's transport row
 * does, down to just the ring and play/pause.
 */
class StepcastTransportWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(100.dp, 40.dp),
            DpSize(180.dp, 40.dp)
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val state = widgetStateFrom(context, currentState())
            val opacity = widgetOpacity(context, id)
            val width = androidx.glance.LocalSize.current.width
            val showSeek = width >= 180.dp
            GlanceTheme {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(widgetBackgroundColor(opacity))
                        .cornerRadius(20.dp)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .clickable(actionStartActivity<MainActivity>())
                ) {
                    RingArtwork(state.art, state.progress, sizeDp = 32)
                    if (showSeek) {
                        Spacer(GlanceModifier.width(8.dp))
                        TransportButton(
                            R.drawable.ic_notif_replay,
                            "Seek back",
                            opacity,
                            actionRunCallback<SeekBackAction>(),
                            sizeDp = 32
                        )
                    }
                    Spacer(GlanceModifier.width(8.dp))
                    PlayPauseButton(state.isPlaying, opacity, sizeDp = 32)
                    if (showSeek) {
                        Spacer(GlanceModifier.width(8.dp))
                        TransportButton(
                            R.drawable.ic_notif_forward,
                            "Seek forward",
                            opacity,
                            actionRunCallback<SeekForwardAction>(),
                            sizeDp = 32
                        )
                    }
                }
            }
        }
    }
}

class StepcastTransportWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StepcastTransportWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        removeWidgetOpacityPrefs(context, appWidgetIds)
    }
}

/**
 * Artwork with a circular progress ring wrapped around it instead of a
 * separate bar — Glance has no arc/canvas drawing composable, so the ring is
 * composited onto a plain Bitmap with android.graphics.Canvas and displayed
 * like any other artwork image. Fixed working resolution: Glance scales the
 * bitmap to fit [sizeDp] regardless of its native pixel size, the same way
 * ArtworkOrGlyph's decoded file bitmap is never resized to match sizeDp
 * either.
 */
@Composable
internal fun RingArtwork(art: Bitmap?, progress: Float, sizeDp: Int) {
    val working = 160
    val stroke = working / 16f
    Image(
        provider = ImageProvider(ringArtworkBitmap(art, progress, working, stroke)),
        contentDescription = null,
        modifier = GlanceModifier.size(sizeDp.dp)
    )
}

private fun ringArtworkBitmap(art: Bitmap?, progress: Float, size: Int, stroke: Float): Bitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val artInset = stroke * 1.6f
    val artDiameter = (size - artInset * 2).coerceAtLeast(1f)
    val artRect = RectF(artInset, artInset, artInset + artDiameter, artInset + artDiameter)
    val artPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    if (art != null) {
        val side = artDiameter.toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(art, side, side, true)
        artPaint.shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    } else {
        artPaint.color = Color.argb(51, 255, 255, 255)
    }
    canvas.drawOval(artRect, artPaint)

    val ringInset = stroke / 2f
    val ringRect = RectF(ringInset, ringInset, size - ringInset, size - ringInset)
    val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(64, 255, 255, 255)
    }
    canvas.drawOval(ringRect, trackPaint)

    val clamped = progress.coerceIn(0f, 1f)
    if (clamped > 0f) {
        val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(235, 255, 255, 255)
        }
        canvas.drawArc(ringRect, -90f, 360f * clamped, false, arcPaint)
    }
    return bitmap
}
