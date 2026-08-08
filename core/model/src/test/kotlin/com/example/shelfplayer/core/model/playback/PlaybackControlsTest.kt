package com.example.shelfplayer.core.model.playback

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-006 / PLAY-007 — the values these types exist to make impossible to get wrong.
 *
 * The speed's grid is the interesting one. It is stored, incremented, sliced by a slider and compared for
 * chip selection, and every one of those breaks if `0.05 × 37` is not exactly the same float as the stored
 * `1.85`. Comparing two nearly-equal floats fails in a way that is very hard to read in a test report, so it
 * is tested here where the failure names itself.
 */
class PlaybackControlsTest {

    @Test
    fun `a speed is clamped to the range the requirement gives`() {
        assertEquals(PlaybackSpeed.MIN, PlaybackSpeed.of(0.1f).value)
        assertEquals(PlaybackSpeed.MAX, PlaybackSpeed.of(9f).value)
    }

    /** PLAY-007: "0.05 increments". A slider's `1.8499999` has to become the step, not stay beside it. */
    @Test
    fun `a speed snaps to the nearest step`() {
        assertEquals(PlaybackSpeed.of(1.85f), PlaybackSpeed.of(1.8499999f))
        assertEquals(PlaybackSpeed.of(1.85f), PlaybackSpeed.of(1.86f))
        assertEquals(PlaybackSpeed.of(1.9f), PlaybackSpeed.of(1.88f))
    }

    /**
     * Stepping up and back down returns to where it started.
     *
     * The property that matters for the plus/minus buttons: an implementation that accumulated float error
     * would drift off the grid after a dozen taps and stay there.
     */
    @Test
    fun `stepping up and down is a round trip`() {
        var speed = PlaybackSpeed.Normal
        repeat(12) { speed = speed.increased() }
        repeat(12) { speed = speed.decreased() }

        assertEquals(PlaybackSpeed.Normal, speed)
    }

    @Test
    fun `stepping stops at the ends rather than wrapping`() {
        assertEquals(PlaybackSpeed.of(PlaybackSpeed.MAX), PlaybackSpeed.of(PlaybackSpeed.MAX).increased())
        assertEquals(PlaybackSpeed.of(PlaybackSpeed.MIN), PlaybackSpeed.of(PlaybackSpeed.MIN).decreased())
    }

    /** The label is what the button shows, so trailing zeros are trimmed and nothing reads as `1.7000001`. */
    @Test
    fun `labels are trimmed to what the step size needs`() {
        assertEquals("1", PlaybackSpeed.of(1.0f).label())
        assertEquals("1.5", PlaybackSpeed.of(1.5f).label())
        assertEquals("1.85", PlaybackSpeed.of(1.85f).label())
        assertEquals("0.5", PlaybackSpeed.of(0.5f).label())
        assertEquals("3", PlaybackSpeed.of(3.0f).label())
    }

    @Test
    fun `every preset is on the grid and labels cleanly`() {
        PlaybackSpeed.Presets.forEach { preset ->
            assertEquals(preset, PlaybackSpeed.of(preset.value), preset.label())
            assertFalse(preset.label().contains("0000"), preset.label())
        }
    }

    @Test
    fun `only normal speed is the default`() {
        assertTrue(PlaybackSpeed.Normal.isDefault)
        assertFalse(PlaybackSpeed.of(1.05f).isDefault)
    }

    /** PLAY-007: "independently configurable from 5–120 seconds". */
    @Test
    fun `skip intervals are clamped to the range`() {
        val clamped = SkipIntervals.of(back = 1.seconds, forward = 500.seconds)

        assertEquals(5.seconds, clamped.back)
        assertEquals(120.seconds, clamped.forward)
    }

    /**
     * The default departs from PLAY-007's 15/30 at the project owner's request — ADR-0015.
     *
     * Asserted rather than left implicit: a future change back to the requirement's own numbers should be a
     * deliberate edit to this test, not something that happens because somebody read the spec and not the ADR.
     */
    @Test
    fun `the default skip is thirty each way`() {
        assertEquals(30.seconds, SkipIntervals.Default.back)
        assertEquals(30.seconds, SkipIntervals.Default.forward)
    }

    @Test
    fun `every skip preset is inside the range`() {
        SkipIntervals.Presets.forEach { preset ->
            assertTrue(preset in SkipIntervals.Range, preset.toString())
        }
    }

    /**
     * PLAY-006: "invalid combinations are rejected: minimum must not exceed maximum; start thresholds must
     * not exceed minimum".
     *
     * Every built-in preset is checked, because `DefaultLoadControl.Builder` asserts the same relationships
     * and an invalid preset would crash the service on the next play rather than warn anybody.
     */
    @Test
    fun `every buffer preset is a valid load control`() {
        BufferPreset.entries.forEach { preset ->
            assertTrue(preset.isValid, preset.name)
        }
    }

    /** PLAY-006: "the default is Automatic". */
    @Test
    fun `the buffer default is automatic and an unknown name falls back to it`() {
        assertEquals(BufferPreset.Automatic, BufferPreset.Default)
        assertEquals(BufferPreset.Automatic, BufferPreset.byNameOrDefault("Enormous"))
        assertEquals(BufferPreset.Automatic, BufferPreset.byNameOrDefault(null))
        assertEquals(BufferPreset.High, BufferPreset.byNameOrDefault("High"))
    }

    /** The requirement's own numbers, so a typo in the enum is a failing test rather than a slow stream. */
    @Test
    fun `the presets carry the durations the requirement lists`() {
        assertEquals(15.seconds to 30.seconds, BufferPreset.Low.minimumBuffer to BufferPreset.Low.maximumBuffer)
        assertEquals(
            30.seconds to 60.seconds,
            BufferPreset.Standard.minimumBuffer to BufferPreset.Standard.maximumBuffer,
        )
        assertEquals(60.seconds to 180.seconds, BufferPreset.High.minimumBuffer to BufferPreset.High.maximumBuffer)
        assertEquals(
            120.seconds to 300.seconds,
            BufferPreset.VeryHigh.minimumBuffer to BufferPreset.VeryHigh.maximumBuffer,
        )
    }

    /** PLAY-009: "disabled by default", and a disabled setting rewinds nothing whatever the pause was. */
    @Test
    fun `auto-rewind is off by default and rewinds nothing while off`() {
        assertFalse(AutoRewind.Default.isEnabled)
        assertEquals(kotlin.time.Duration.ZERO, AutoRewind.Default.amountFor(120.seconds))
    }
}
