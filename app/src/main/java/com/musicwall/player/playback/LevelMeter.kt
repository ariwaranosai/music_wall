package com.musicwall.player.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.BaseAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10

data class StereoLevel(val left: Float, val right: Float)

internal val RestingLevel = StereoLevel(-1f, -1f)

object MeterFeed {
    private val reading = MutableStateFlow(RestingLevel)
    private val spectrumReading = MutableStateFlow<FloatArray?>(null)
    val levels = reading.asStateFlow()
    internal val spectrum = spectrumReading.asStateFlow()
    internal val spectrumActive: Boolean get() = spectrumReading.subscriptionCount.value > 0

    internal fun publish(level: StereoLevel) {
        reading.value = level
    }

    internal fun publishSpectrum(bands: FloatArray) { spectrumReading.value = bands }

    internal fun clear() {
        reading.value = RestingLevel
        spectrumReading.value = null
    }
}

internal val MeterScale = listOf(
    -48 to -1f, -36 to -.77f, -24 to -.53f, -18 to -.30f, -12 to -.05f,
    -9 to .17f, -6 to .39f, -3 to .67f, 0 to 1f,
)

internal const val LevelWindowsPerSecond = 40

internal fun meterPosition(sumOfSquares: Double, count: Int): Float {
    if (count == 0 || sumOfSquares <= 0.0) return -1f
    val dbfs = 10 * log10(sumOfSquares / count)
    if (dbfs <= MeterScale.first().first) return -1f
    if (dbfs >= MeterScale.last().first) return 1f
    val high = MeterScale.indexOfFirst { it.first >= dbfs }
    val (lowerDb, lowerPosition) = MeterScale[high - 1]
    val (higherDb, higherPosition) = MeterScale[high]
    val ratio = ((dbfs - lowerDb) / (higherDb - lowerDb)).toFloat()
    return lowerPosition + (higherPosition - lowerPosition) * ratio
}

/** Reads both channels and passes every PCM byte through unchanged. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StereoMeter : BaseAudioProcessor() {
    private var leftEnergy = 0.0
    private var rightEnergy = 0.0
    private var samples = 0
    private var spectrum: AudioSpectrum? = null

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        resetWindow()
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount >= 1) {
            inputAudioFormat
        } else {
            AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Media3 can queue its shared empty buffer while polling for output.
        if (!inputBuffer.hasRemaining()) return

        val audio = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frameSize = inputAudioFormat.bytesPerFrame
        val sampleRight = if (inputAudioFormat.channelCount > 1) 2 else 0
        val window = (inputAudioFormat.sampleRate / LevelWindowsPerSecond).coerceAtLeast(1)
        val analyzer = spectrum?.takeIf { MeterFeed.spectrumActive }
        if (analyzer == null) spectrum?.reset()
        while (audio.remaining() >= frameSize) {
            val index = audio.position()
            val left = audio.getShort(index) / 32768.0
            val right = audio.getShort(index + sampleRight) / 32768.0
            leftEnergy += left * left
            rightEnergy += right * right
            analyzer?.add(left.toFloat(), right.toFloat())
            samples++
            audio.position(index + frameSize)
            if (samples >= window) {
                MeterFeed.publish(
                    StereoLevel(meterPosition(leftEnergy, samples), meterPosition(rightEnergy, samples)),
                )
                resetWindow()
            }
        }
        replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
    }

    override fun onFlush(streamMetadata: StreamMetadata) {
        resetWindow()
        spectrum = if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.sampleRate > 0) {
            AudioSpectrum(inputAudioFormat.sampleRate, MeterFeed::publishSpectrum)
        } else null
        MeterFeed.clear()
    }

    override fun onReset() {
        resetWindow()
        spectrum = null
        MeterFeed.clear()
    }

    private fun resetWindow() {
        leftEnergy = 0.0
        rightEnergy = 0.0
        samples = 0
    }
}
