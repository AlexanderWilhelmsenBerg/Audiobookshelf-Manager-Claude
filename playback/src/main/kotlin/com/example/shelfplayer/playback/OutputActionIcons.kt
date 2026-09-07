package com.example.shelfplayer.playback

/**
 * PRODUCT_SPEC PLAY-002 — which of each output action's two glyphs to publish.
 *
 * ADR-0029 §8: the lit variant is the only way the car's player can say where the audio is going, because
 * the host draws the title, the byline and these two icons and nothing else the app supplies.
 *
 * ### Why this is a named function rather than two `if`s at the call site
 *
 * It was two `if`s at the call site, and they **vanished in a revert without anything failing**. The
 * drawables were still there, `OutputButtons.onCar`/`onHeadset` still computed the state, the build was
 * green, and the feature was dead — a review caught it by searching for callers of the resources. That is
 * `docs/risks.md` R-100's shape exactly: an edit that no longer applies looks identical to one that does.
 *
 * A function with a test is what makes the wiring assertable. `OutputActionIconsTest` fails if either
 * mapping is inverted or dropped, which is the failure the call site could not express.
 */
internal object OutputActionIcons {

    /** Lit when the book is not on a headset, which on a connected car is the car. See [OutputButtons]. */
    fun car(state: OutputButtons): Int = if (state.onCar) R.drawable.ic_car_output_active else R.drawable.ic_car_output

    /** Lit when the book is coming out of a headset, held or chosen. */
    fun headset(state: OutputButtons): Int =
        if (state.onHeadset) R.drawable.ic_headset_output_active else R.drawable.ic_headset_output
}
