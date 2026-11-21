package com.musicwall.player.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

private val BandEdges = intArrayOf(
    35, 65, 90, 115, 145, 180, 220, 270, 330, 410, 510, 630, 780,
    970, 1200, 1500, 1900, 2400, 3000, 3900, 5000, 6500, 8500, 11300, 18000,
)

internal val SpectrumBandCount = BandEdges.size - 1

internal class AudioSpectrum(sampleRate: Int, private val onBands: (FloatArray) -> Unit) {
    // Keep roughly 40–50 ms in each FFT at common output rates, with half-window overlap.
    private val size = when {
        sampleRate <= 24_000 -> 1024
        sampleRate <= 50_000 -> 2048
        sampleRate <= 100_000 -> 4096
        sampleRate <= 200_000 -> 8192
        else -> 16384
    }
    private val half = size / 2
    private val left = FloatArray(size)
    private val right = FloatArray(size)
    private val real = FloatArray(size)
    private val imaginary = FloatArray(size)
    private val window = FloatArray(size) { (.5 - .5 * cos(2 * PI * it / size)).toFloat() }
    private val reverse = IntArray(size) { Integer.reverse(it) ushr (32 - Integer.numberOfTrailingZeros(size)) }
    private val cosines = FloatArray(half) { cos(-2 * PI * it / size).toFloat() }
    private val sines = FloatArray(half) { sin(-2 * PI * it / size).toFloat() }
    private val bins = IntArray(half) { bin ->
        val frequency = bin.toDouble() * sampleRate / size
        BandEdges.indexOfFirst { it > frequency } - 1
    }
    private var count = 0

    fun add(leftSample: Float, rightSample: Float) {
        left[count] = leftSample
        right[count] = rightSample
        count++
        if (count == size) {
            onBands(transform())
            left.copyInto(left, startIndex = half)
            right.copyInto(right, startIndex = half)
            count = half
        }
    }

    fun reset() { count = 0 }

    private fun transform(): FloatArray {
        for (index in 0 until size) {
            real[reverse[index]] = left[index] * window[index]
            imaginary[reverse[index]] = right[index] * window[index]
        }
        var span = 2
        while (span <= size) {
            val stride = size / span
            for (base in 0 until size step span) {
                for (offset in 0 until span / 2) {
                    val first = base + offset
                    val second = first + span / 2
                    val cosine = cosines[offset * stride]
                    val sine = sines[offset * stride]
                    val r = real[second] * cosine - imaginary[second] * sine
                    val i = real[second] * sine + imaginary[second] * cosine
                    real[second] = real[first] - r
                    imaginary[second] = imaginary[first] - i
                    real[first] += r
                    imaginary[first] += i
                }
            }
            span *= 2
        }

        val bands = FloatArray(SpectrumBandCount)
        for (bin in 1 until half) {
            val band = bins[bin]
            if (band < 0) continue
            // A complex FFT of L + iR combines the channels' power in each mirrored bin pair.
            val mirror = size - bin
            val power = real[bin] * real[bin] + imaginary[bin] * imaginary[bin] +
                real[mirror] * real[mirror] + imaginary[mirror] * imaginary[mirror]
            bands[band] = maxOf(bands[band], power)
        }
        for (band in bands.indices) {
            val power = bands[band] * (4f / (size * size))
            val db = if (power > 0f) 10f * log10(power) else -100f
            val highBandLift = band / (SpectrumBandCount - 1f) * 7f
            bands[band] = ((db + highBandLift + 63f) / 48f).coerceIn(0f, 1f)
        }
        return bands
    }
}
