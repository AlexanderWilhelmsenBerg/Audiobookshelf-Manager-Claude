package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.ShakeSensitivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShakeSensitivityTest {

    @Test
    fun `gentle movement rejects rest sensor noise and one sample but detects sustained sideways movement`() {
        val detector = GentleMotionDetector(ShakeSensitivity.UltraHigh.movementThreshold())
        repeat(40) { sample ->
            assertFalse(detector.detect(if (sample % 2 == 0) 0.001 else -0.001, 0.0, 9.81, sample * 66L))
        }
        assertFalse(detector.detect(0.1, 0.0, 9.81, 2_640))
        assertFalse(detector.detect(0.1, 0.0, 9.81, 2_706))
        assertTrue(detector.detect(0.1, 0.0, 9.81, 2_772))
    }

    @Test
    fun `registration starts a new gravity estimate instead of interpreting gravity as motion`() {
        val detector = GentleMotionDetector(ShakeSensitivity.UltraHigh.movementThreshold())
        repeat(40) { sample -> assertFalse(detector.detect(9.81, 0.0, 0.0, sample * 66L)) }
        assertFalse(detector.detect(Double.NaN, 0.0, 0.0, 3_000))
    }

    @Test
    fun `extra high and ultra high detect successively gentler movement`() {
        assertTrue(ShakeSensitivity.ExtraHigh.movementThreshold() < ShakeSensitivity.High.movementThreshold())
        assertTrue(ShakeSensitivity.UltraHigh.movementThreshold() < ShakeSensitivity.ExtraHigh.movementThreshold())
        assertTrue(ShakeSensitivity.UltraHigh.movementThreshold() <= 0.03)
    }

    @Test
    fun `normal preserves the legacy threshold and high is easier than low`() {
        assertEquals(12.0, ShakeSensitivity.Normal.movementThreshold())
        assertTrue(ShakeSensitivity.High.movementThreshold() < ShakeSensitivity.Normal.movementThreshold())
        assertTrue(ShakeSensitivity.Normal.movementThreshold() < ShakeSensitivity.Low.movementThreshold())
    }
}
