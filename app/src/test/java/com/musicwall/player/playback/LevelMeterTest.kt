package com.musicwall.player.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.AudioProcessingPipeline
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelMeterTest {
    @Test
    fun pipelineCanPollForOutputBeforeTheFirstPcmBuffer() {
        val pipeline = AudioProcessingPipeline(ImmutableList.of(StereoMeter()))
        pipeline.configure(AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        pipeline.flush(StreamMetadata.DEFAULT)

        assertTrue(pipeline.isOperational)
        assertEquals(0, pipeline.output.remaining())
    }

    @Test
    fun mapsSilenceAndFullScaleToDialEndpoints() {
        assertEquals(-1f, meterPosition(0.0, 0), .001f)
        assertEquals(-1f, meterPosition(0.0, 4), .001f)
        assertEquals(1f, meterPosition(64.0, 64), .001f)
    }

    @Test
    fun computesRmsFromTheWindowSize() {
        val lowerAmplitude = meterPosition(.64, 64)
        assertEquals(lowerAmplitude, meterPosition(1.28, 128), .001f)
        assertTrue(lowerAmplitude > meterPosition(.01, 64))
        assertTrue(lowerAmplitude < 1f)
        // A loud passage still leaves room for stronger passages.
        assertEquals(-.05f, meterPosition(Math.pow(10.0, -1.2) * 64, 64), .001f)
        assertEquals(.39f, meterPosition(Math.pow(10.0, -.6) * 64, 64), .001f)
        assertEquals(.67f, meterPosition(Math.pow(10.0, -.3) * 64, 64), .001f)
    }

    @Test
    fun separatesFrequencyBandsAndPreservesStereoPcm() = runBlocking {
        val processor = StereoMeter()
        val sampleRate = 48_000
        processor.configure(AudioFormat(sampleRate, 2, C.ENCODING_PCM_16BIT))
        processor.flush(StreamMetadata.DEFAULT)
        val frames = 2048
        val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { index ->
            val time = index.toDouble() / sampleRate
            val bass = 12_500 * sin(2 * PI * 187.5 * time)
            val treble = 5_500 * sin(2 * PI * 3_000 * time)
            input.putShort((bass + treble).roundToInt().toShort())
            input.putShort((-bass).roundToInt().toShort())
        }
        input.flip()
        val original = ByteArray(input.remaining()).also { input.duplicate().get(it) }
        val spectrum = async(start = CoroutineStart.UNDISPATCHED) { MeterFeed.spectrum.filterNotNull().first() }

        processor.queueInput(input)

        val output = processor.output
        val bytes = ByteArray(output.remaining()).also(output::get)
        assertArrayEquals(original, bytes)
        val levels = MeterFeed.levels.value
        assertTrue(levels.left > levels.right)
        val bands = withTimeout(1_000) { spectrum.await() }
        assertEquals(24, bands.size)
        assertTrue("Out-of-phase bass should remain visible", bands[5] > .7f)
        assertTrue("The higher tone should appear in its own band", bands[18] > .6f)
        assertEquals(0f, bands[14], .02f)
        processor.reset()
    }
}
