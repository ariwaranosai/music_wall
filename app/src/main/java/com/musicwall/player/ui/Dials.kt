package com.musicwall.player.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.musicwall.player.playback.MeterFeed
import com.musicwall.player.playback.MeterScale
import com.musicwall.player.playback.RestingLevel
import com.musicwall.player.playback.StereoLevel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

private const val Sweep = 55f
private const val WarningMark = -3

internal fun followMeterLevel(current: Float, target: Float, seconds: Float): Float {
    if (abs(target - current) <= .001f) return target
    val timeConstant = if (target > current) .045f else .18f
    return target + (current - target) * exp(-seconds / timeConstant)
}

@Composable
internal fun Dials(
    playing: Boolean,
    modifier: Modifier = Modifier,
    appearance: MeterAppearance,
    subdued: Boolean = false,
) {
    val displayed = remember { mutableStateOf(RestingLevel) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, playing) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previousFrame = 0L
            while (playing || displayed.value != RestingLevel) {
                withFrameNanos { now ->
                    if (previousFrame != 0L) {
                        val seconds = (now - previousFrame) / 1_000_000_000f
                        val target = if (playing) MeterFeed.levels.value else RestingLevel
                        val current = displayed.value
                        displayed.value = StereoLevel(
                            followMeterLevel(current.left, target.left, seconds),
                            followMeterLevel(current.right, target.right, seconds),
                        )
                    }
                    previousFrame = now
                }
            }
        }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Dial("L", { displayed.value.left }, appearance, subdued, Modifier.weight(1f))
        Dial("R", { displayed.value.right }, appearance, subdued, Modifier.weight(1f))
    }
}

@Composable
private fun Dial(
    channel: String,
    position: () -> Float,
    appearance: MeterAppearance,
    subdued: Boolean,
    modifier: Modifier,
) {
    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE } }
    val shape = appearance.shape
    val needle = if (channel == "L") appearance.leftNeedle else appearance.rightNeedle
    val insetPivot = appearance.pivotHeight < 1f
    Box(
        modifier.fillMaxWidth().aspectRatio(appearance.aspectRatio)
            .shadow(appearance.elevation, shape)
            .clip(shape)
            .background(appearance.face)
            .border(appearance.borderWidth, appearance.outline, shape)
            .semantics { contentDescription = if (channel == "L") "左声道电平表" else "右声道电平表" },
    ) {
        Canvas(Modifier.matchParentSize().graphicsLayer()) {
            val unit = size.width / 520
            val pivot = Offset(size.width / 2, size.height * appearance.pivotHeight)
            fun point(radius: Int, position: Float): Offset {
                val angle = position * (Sweep * PI / 180).toFloat()
                return pivot + Offset(radius * sin(angle) * unit, -radius * cos(angle) * unit)
            }

            appearance.grid?.let { grid ->
                val step = size.width / 20
                if (step > 0f) {
                    for (index in 1..19) {
                        val x = index * step
                        drawLine(grid, Offset(x, step), Offset(x, size.height - step))
                    }
                    for (index in 1 until (size.height / step).toInt()) {
                        val y = index * step
                        drawLine(grid, Offset(step, y), Offset(size.width - step, y))
                    }
                }
                val inset = size.minDimension * .055f
                val length = size.width * .14f
                drawLine(needle, Offset(inset, unit), Offset(inset + length, unit), 3f * unit)
                drawLine(
                    needle,
                    Offset(size.width - inset - length, size.height - unit),
                    Offset(size.width - inset, size.height - unit),
                    3f * unit,
                )
            }

            val radius = 225 * unit
            val arcOrigin = pivot - Offset(radius, radius)
            val arcBounds = Size(radius * 2, radius * 2)
            val warningStart = MeterScale.first { it.first == WarningMark }.second * Sweep
            drawArc(appearance.marks, -90f - Sweep, Sweep + warningStart, false, arcOrigin, arcBounds, style = Stroke(2.2f * unit))
            drawArc(appearance.warning, -90f + warningStart, Sweep - warningStart, false, arcOrigin, arcBounds, style = Stroke(4.5f * unit))

            val native = drawContext.canvas.nativeCanvas
            labelPaint.textAlign = Paint.Align.CENTER
            labelPaint.textSize = 22 * unit
            for ((dbfs, tickPosition) in MeterScale) {
                val ink = if (dbfs >= WarningMark) appearance.warning else appearance.marks
                drawLine(ink, point(224, tickPosition), point(if (dbfs == 0) 191 else 201, tickPosition), 3f * unit)
                val label = point(251, tickPosition)
                labelPaint.color = ink.toArgb()
                native.drawText(dbfs.toString(), label.x, label.y, labelPaint)
            }

            labelPaint.color = appearance.marks.toArgb()
            labelPaint.textSize = 25 * unit
            val unitLabelX = if (insetPivot) pivot.x + 58 * unit else pivot.x
            native.drawText("dBFS", unitLabelX, pivot.y - 65 * unit, labelPaint)
            if (appearance.grid != null) labelPaint.color = needle.toArgb()
            labelPaint.textSize = 20 * unit
            native.drawText(channel, 28 * unit, 35 * unit, labelPaint)
        }

        Canvas(
            Modifier.matchParentSize().graphicsLayer {
                transformOrigin = TransformOrigin(.5f, appearance.pivotHeight)
                rotationZ = position() * Sweep
            },
        ) {
            val unit = size.width / 520
            val pivot = Offset(size.width / 2, size.height * appearance.pivotHeight)
            val tip = pivot - Offset(0f, 211 * unit)
            val shadow = Offset(2 * unit, 0f)
            drawLine(Color(0x70422016), pivot + shadow, tip + shadow, 6 * unit, StrokeCap.Round)
            if (appearance.grid != null) {
                drawLine(needle.copy(alpha = .15f), pivot, tip, 13 * unit, StrokeCap.Round)
            }
            drawLine(needle, pivot, tip, 3.4f * unit, StrokeCap.Round)
            drawCircle(appearance.hub, (if (insetPivot) 15 else 27) * unit, pivot)
            if (insetPivot) drawCircle(needle, 6.5f * unit, pivot)
        }

        if (subdued) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .16f)))
    }
}
