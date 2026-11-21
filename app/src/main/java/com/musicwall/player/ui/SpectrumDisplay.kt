package com.musicwall.player.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.musicwall.player.playback.MeterFeed
import com.musicwall.player.playback.SpectrumBandCount
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.exp

private const val Rows = 18
private const val ReflectionRows = 7

@Composable
internal fun SpectrumDisplay(
    motion: SpectrumMotion,
    running: Boolean,
    appearance: SpectrumAppearance,
    modifier: Modifier = Modifier,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, motion, running) {
        if (!running) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var target: FloatArray? = null
            launch(start = CoroutineStart.UNDISPATCHED) {
                MeterFeed.spectrum.collect { bands ->
                    target = bands
                    if (bands == null) motion.clear()
                }
            }
            var previous = 0L
            while (isActive) {
                withFrameNanos { now ->
                    if (previous != 0L) {
                        val seconds = (now - previous).coerceAtMost(100_000_000L) / 1_000_000_000f
                        target?.let { motion.advance(it, seconds) }
                    }
                    previous = now
                }
            }
        }
    }

    Spacer(
        modifier.semantics { contentDescription = "实时音频频谱" }.drawWithCache {
            val insetX = 7.dp.toPx().coerceAtMost(size.width * .07f)
            val insetY = 5.dp.toPx().coerceAtMost(size.height * .05f)
            val baseline = size.height * .75f
            val column = (size.width - 2f * insetX) / SpectrumBandCount
            val row = (baseline - insetY) / Rows
            val reflectionRow = (size.height - baseline - insetY) / ReflectionRows
            val blockWidth = column - minOf(1.7.dp.toPx(), column * .18f)
            val blockHeight = row - minOf(1.dp.toPx(), row * .24f)
            val reflectionHeight = reflectionRow - minOf(1.dp.toPx(), reflectionRow * .24f)
            val starts = FloatArray(SpectrumBandCount) { index -> insetX + index * column }
            val colors = List(Rows) { index ->
                val palette = appearance.palette
                val stop = index.toFloat() / (Rows - 1) * palette.lastIndex
                val lower = stop.toInt().coerceAtMost(palette.lastIndex - 1)
                lerp(palette[lower], palette[lower + 1], stop - lower)
            }
            val reflection = List(ReflectionRows) { index ->
                val fade = 1f - index.toFloat() / ReflectionRows
                lerp(colors[index], appearance.reflectionTint, .65f).copy(alpha = .36f * fade * fade)
            }
            val path = Path()
            val opacity = if (running) 1f else .53f
            onDrawBehind {
                val frame = motion.frame
                for (index in 0 until Rows) {
                    path.rewind()
                    val y = baseline - (index + 1) * row
                    for (band in starts.indices) {
                        val coverage = (frame.levels[band] * Rows - index).coerceIn(0f, 1f)
                        if (coverage >= 1f) {
                            path.addRect(Rect(starts[band], y, starts[band] + blockWidth, y + blockHeight))
                        } else if (coverage > 0f || index == 0) {
                            drawRect(
                                colors[index], Offset(starts[band], y), Size(blockWidth, blockHeight),
                                alpha = maxOf(.10f, coverage) * opacity,
                            )
                        }
                    }
                    if (!path.isEmpty) drawPath(path, colors[index], alpha = opacity)
                }
                for (index in 0 until ReflectionRows) {
                    path.rewind()
                    val y = baseline + index * reflectionRow
                    for (band in starts.indices) {
                        if (frame.levels[band] * Rows >= index + 1) {
                            path.addRect(Rect(starts[band], y, starts[band] + blockWidth, y + reflectionHeight))
                        }
                    }
                    if (!path.isEmpty) drawPath(path, reflection[index], alpha = opacity)
                }
                for (band in starts.indices) {
                    if (frame.peaks[band] * Rows < 2f) continue
                    val marker = (ceil(frame.peaks[band] * Rows).toInt() - 1).coerceIn(1, Rows - 1)
                    val y = baseline - (marker + 1) * row
                    drawRect(appearance.peak, Offset(starts[band], y), Size(blockWidth, blockHeight), alpha = opacity)
                }
            }
        },
    )
}

internal data class SpectrumFrame(val levels: FloatArray, val peaks: FloatArray)

internal class SpectrumMotion {
    private val hold = FloatArray(SpectrumBandCount)
    var frame by mutableStateOf(emptyFrame())
        private set

    fun advance(target: FloatArray, seconds: Float) {
        val previous = frame
        val attack = 1f - exp(-seconds / .035f)
        val release = 1f - exp(-seconds / .16f)
        val levels = FloatArray(SpectrumBandCount)
        val peaks = FloatArray(SpectrumBandCount)
        for (band in levels.indices) {
            val difference = target[band] - previous.levels[band]
            val next = previous.levels[band] + difference * if (difference >= 0f) attack else release
            levels[band] = if (next < .004f) 0f else next
            val peak = previous.peaks[band]
            if (levels[band] >= peak) hold[band] = .26f
            else hold[band] = (hold[band] - seconds).coerceAtLeast(0f)
            peaks[band] = maxOf(levels[band], if (hold[band] > 0f) peak else peak - seconds * .92f)
        }
        if (!levels.contentEquals(previous.levels) || !peaks.contentEquals(previous.peaks)) {
            frame = SpectrumFrame(levels, peaks)
        }
    }

    fun clear() {
        hold.fill(0f)
        frame = emptyFrame()
    }

    private fun emptyFrame() = SpectrumFrame(FloatArray(SpectrumBandCount), FloatArray(SpectrumBandCount))
}
