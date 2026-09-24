package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.ShakeSensitivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShakeSensitivityTest {

    @Test
    fun `normal preserves the legacy threshold and high is easier than low`() {
        assertEquals(12.0, ShakeSensitivity.Normal.movementThreshold())
        assertTrue(ShakeSensitivity.High.movementThreshold() < ShakeSensitivity.Normal.movementThreshold())
        assertTrue(ShakeSensitivity.Normal.movementThreshold() < ShakeSensitivity.Low.movementThreshold())
    }
}
