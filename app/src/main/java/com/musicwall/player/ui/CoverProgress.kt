package com.musicwall.player.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class ProgressHit(val fraction: Float, val distance: Float)

/** The progress starts at the center of the top edge and runs clockwise. */
internal class CoverProgressTrack(side: Float, inset: Float, radius: Float) {
    private val end = (side - inset).coerceAtLeast(inset + 1f)
    private val corner = radius.coerceIn(0f, (end - inset) / 2f)
    private val points = buildList {
        val start = Offset(side / 2f, inset)
        add(start)
        for ((index, center) in listOf(
            Offset(end - corner, inset + corner),
            Offset(end - corner, end - corner),
            Offset(inset + corner, end - corner),
            Offset(inset + corner, inset + corner),
        ).withIndex()) {
            for (piece in 0..16) {
                val angle = ((index - 1) + piece / 16.0) * PI / 2
                val point = center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * corner
                if (last() != point) add(point)
            }
        }
        add(start)
    }
    private val distances = FloatArray(points.size).apply {
        for (index in 1 until size) {
            this[index] = this[index - 1] + (points[index] - points[index - 1]).getDistance()
        }
    }
    private val circumference = distances.last()

    fun hit(point: Offset): ProgressHit {
        var closest = Float.POSITIVE_INFINITY
        var along = 0f
        for (index in 1 until points.size) {
            val start = points[index - 1]
            val edge = points[index] - start
            val offset = point - start
            val lengthSquared = edge.getDistanceSquared()
            if (lengthSquared == 0f) continue
            val projection = ((offset.x * edge.x + offset.y * edge.y) / lengthSquared).coerceIn(0f, 1f)
            val distance = (offset - edge * projection).getDistanceSquared()
            if (distance < closest) {
                closest = distance
                along = distances[index - 1] + (distances[index] - distances[index - 1]) * projection
            }
        }
        return ProgressHit(along / circumference, sqrt(closest))
    }

    fun trace(path: Path, fraction: Float): Offset {
        path.reset()
        path.moveTo(points[0].x, points[0].y)
        val target = fraction.coerceIn(0f, 1f) * circumference
        if (target == 0f) return points[0]
        for (index in 1 until points.size) {
            val end = points[index]
            if (target <= distances[index]) {
                val length = distances[index] - distances[index - 1]
                val start = points[index - 1]
                val tip = start + (end - start) * ((target - distances[index - 1]) / length)
                path.lineTo(tip.x, tip.y)
                return tip
            }
            path.lineTo(end.x, end.y)
        }
        return points.last()
    }
}

internal fun dragProgress(current: Float, previous: Float, hit: Float): Float {
    val movement = hit - previous
    val delta = when {
        movement > .5f -> movement - 1f
        movement < -.5f -> movement + 1f
        else -> movement
    }
    return (current + delta).coerceIn(0f, 1f)
}

@Composable
internal fun CoverProgress(
    fraction: Float,
    elapsed: String,
    enabled: Boolean,
    trackId: String,
    appearance: AppAppearance,
    onPreview: (Float?) -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val currentFraction = rememberUpdatedState(fraction)
    val preview = rememberUpdatedState(onPreview)
    val seek = rememberUpdatedState(onSeek)
    val colors = appearance.colors
    val start = colors.primary
    val end = if (appearance.meters.grid != null) appearance.meters.rightNeedle else start
    val density = LocalDensity.current
    BoxWithConstraints(modifier.aspectRatio(1f)) {
        val side = constraints.maxWidth.toFloat()
        val track = remember(side, density) {
            with(density) { CoverProgressTrack(side, 6.dp.toPx(), 24.dp.toPx()) }
        }
        val gestures = if (enabled) {
            Modifier.pointerInput(track, trackId) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val first = track.hit(down.position)
                    if (first.distance > 22.dp.toPx()) return@awaitEachGesture
                    down.consume()
                    var previous = first.fraction
                    var value = if (first.fraction < .002f && currentFraction.value > .98f) 1f else first.fraction
                    preview.value(value)
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                ?: break
                            if (change.isConsumed) break
                            val hit = track.hit(change.position).fraction
                            value = dragProgress(value, previous, hit)
                            previous = hit
                            preview.value(value)
                            change.consume()
                            if (!change.pressed) {
                                seek.value(value)
                                break
                            }
                        }
                    } finally {
                        preview.value(null)
                    }
                }
            }
        } else Modifier

        Box(
            Modifier.fillMaxSize().then(gestures)
                .semantics {
                    contentDescription = "封面进度环"
                    stateDescription = elapsed
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    if (enabled) {
                        setProgress {
                            seek.value(it.coerceIn(0f, 1f))
                            true
                        }
                    } else disabled()
                }
                .drawWithCache {
                    val border = Path().also { track.trace(it, 1f) }
                    val range = Path()
                    val brush = Brush.linearGradient(listOf(start, end))
                    val stroke = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val glow = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    onDrawBehind {
                        drawPath(border, colors.outlineVariant, style = Stroke(1.35.dp.toPx()))
                        val progress = currentFraction.value
                        val thumb = track.trace(range, progress)
                        if (progress > 0f) {
                            drawPath(range, end.copy(alpha = .13f), style = glow)
                            drawPath(range, brush, style = stroke)
                        }
                        if (enabled) drawCircle(end, 4.8.dp.toPx(), thumb)
                    }
                }
                .padding(16.dp),
        ) { content() }
    }
}
