package com.musicwall.player.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverProgressTest {
    @Test
    fun progressRunsClockwiseAroundRoundedCover() {
        val track = CoverProgressTrack(200f, 6f, 25f)
        val positions = listOf(
            Offset(100f, 6f) to 0f,
            Offset(194f, 100f) to .25f,
            Offset(100f, 194f) to .5f,
            Offset(6f, 100f) to .75f,
        )
        for ((point, fraction) in positions) {
            val hit = track.hit(point)
            assertEquals(fraction, hit.fraction, .0001f)
            assertEquals(0f, hit.distance, .0001f)
        }
        assertTrue(track.hit(Offset(100f, 100f)).distance > 22f)
    }

    @Test
    fun draggingPastTheTopSeamClampsAtStartOrEnd() {
        assertEquals(1f, dragProgress(.98f, .98f, .03f), .0001f)
        assertEquals(0f, dragProgress(.03f, .03f, .98f), .0001f)
        assertEquals(.95f, dragProgress(1f, .03f, .98f), .0001f)
        assertEquals(.48f, dragProgress(.4f, .4f, .48f), .0001f)
    }
}
