package com.musicwall.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeterMotionTest {
    @Test
    fun elapsedTimeControlsMotionAcrossRefreshRates() {
        val oneFrame = followMeterLevel(-1f, .4f, .016f)
        val twoFrames = followMeterLevel(followMeterLevel(-1f, .4f, .008f), .4f, .008f)
        assertEquals(oneFrame, twoFrames, .0001f)
    }

    @Test
    fun needleRisesQuickerThanItFallsAndSettlesAtRest() {
        val rise = followMeterLevel(-1f, 1f, .016f) + 1f
        val fall = 1f - followMeterLevel(1f, -1f, .016f)
        assertTrue(rise > fall)
        assertTrue(rise < 2f)
        assertEquals(-1f, followMeterLevel(-.9995f, -1f, .016f), 0f)
    }
}
