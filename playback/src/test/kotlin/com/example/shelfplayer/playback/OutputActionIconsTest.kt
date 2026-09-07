package com.example.shelfplayer.playback

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * PRODUCT_SPEC PLAY-002 / ADR-0029 §8 — the lit output glyph is published when the route is live.
 *
 * **This test exists because the wiring it covers disappeared once and nothing noticed.** The two `if`s
 * that chose between the glyphs were removed by a revert of an adjacent commit; the drawables remained,
 * the state remained, the build was green, and no head unit could ever have shown the indicator. A review
 * found it by searching for callers of the resources. `docs/risks.md` R-100 is the same failure — an edit
 * that stopped applying is indistinguishable from one that still does, unless something asserts it.
 *
 * The first case is therefore the important one: **the two resources must actually differ.** Without it,
 * every assertion below would pass against a build where both branches returned the same id, which is
 * exactly what a JVM unit test reading generated `R` fields could silently produce.
 */
class OutputActionIconsTest {

    @Test
    fun `the lit and unlit resources are different resources`() {
        assertNotEquals(R.drawable.ic_car_output, R.drawable.ic_car_output_active)
        assertNotEquals(R.drawable.ic_headset_output, R.drawable.ic_headset_output_active)
    }

    @Test
    fun `the headset glyph is lit while the book is on a headset`() {
        val onHeadset = OutputButtons(
            showCar = true,
            showHeadset = true,
            headsetName = "Earbuds",
            onHeadset = true,
            onCar = false,
        )

        assertEquals(R.drawable.ic_headset_output_active, OutputActionIcons.headset(onHeadset))
        assertEquals(R.drawable.ic_car_output, OutputActionIcons.car(onHeadset))
    }

    @Test
    fun `the car glyph is lit while the book is not on a headset`() {
        val onCar = OutputButtons(
            showCar = true,
            showHeadset = true,
            headsetName = null,
            onHeadset = false,
            onCar = true,
        )

        assertEquals(R.drawable.ic_car_output_active, OutputActionIcons.car(onCar))
        assertEquals(R.drawable.ic_headset_output, OutputActionIcons.headset(onCar))
    }

    /**
     * **Neither lit is a legitimate state**, and a review is why: `onCar` used to be `!onHeadset`, which
     * drew a confident car glyph while the phone speaker carried the audio. Both flags false is what the
     * indicator now says when it does not know, and the icons must render that rather than guess.
     */
    @Test
    fun `neither glyph is lit when neither route is known to have the audio`() {
        val unknown = OutputButtons(
            showCar = true,
            showHeadset = true,
            headsetName = null,
            onHeadset = false,
            onCar = false,
        )

        assertEquals(R.drawable.ic_car_output, OutputActionIcons.car(unknown))
        assertEquals(R.drawable.ic_headset_output, OutputActionIcons.headset(unknown))
    }
}
