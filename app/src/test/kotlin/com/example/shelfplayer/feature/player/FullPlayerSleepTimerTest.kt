package com.example.shelfplayer.feature.player

import com.example.shelfplayer.core.model.playback.SleepTimerMode
import com.example.shelfplayer.core.model.playback.SleepTimerState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * BW-SLEEP-01 — owner-state smoke beside the full screen's real Compose coverage.
 *
 * Concrete FullPlayer countdown text, accessibility semantics and tap behavior live in
 * `PlayerAccessibilityScreenTest`, the repository's established Robolectric FullPlayer harness. This
 * lightweight case keeps the state assumption explicit without creating a second Compose instrumentation owner.
 */
class FullPlayerSleepTimerTest {

    @Test
    fun `active owner state carries the remaining duration projected by the player`() {
        val timer = SleepTimerState(
            mode = SleepTimerMode.Fixed(30.minutes),
            remaining = 7.minutes,
            isFading = false,
        )

        assertTrue(timer.isActive)
        assertEquals(7.minutes, timer.remaining)
    }
}
