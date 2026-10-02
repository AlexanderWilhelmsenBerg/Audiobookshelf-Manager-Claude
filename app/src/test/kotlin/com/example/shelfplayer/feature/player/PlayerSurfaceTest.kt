package com.example.shelfplayer.feature.player

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerSurfaceTest {

    @Test
    fun `expand and collapse own one presentation state`() {
        val surface = PlayerSurface()

        assertFalse(surface.isExpanded.value)

        surface.expand()
        assertTrue(surface.isExpanded.value)

        surface.collapse()
        assertFalse(surface.isExpanded.value)
    }
}
