package com.example.shelfplayer.playback

import androidx.media3.session.CommandButton

/**
 * PRODUCT_SPEC PLAY-002 / PLAY-007 — the order the button list is published in, which is what decides who
 * gets the car's control bar.
 *
 * ### Order is a mechanism here, not a presentation detail
 *
 * Android Auto is served by Media3's legacy stub, and
 * `CommandButton.getCustomLayoutFromMediaButtonPreferences` resolves a contested slot by walking the
 * published list and taking the **first** enabled button whose slot chain names it. So "the output actions
 * come before the skips" is the entire implementation of the owner's request after a device run: *"I need
 * them more than seek forward and back."* Reverse the two lines and the car silently goes back to showing
 * skips, with nothing failing to compile.
 *
 * ### Why this is a named function rather than the order of two statements
 *
 * Because it was the order of two statements, and a red-check proved that worthless: reverting the
 * ordering in `PlaybackService` broke **no test**, since the conversion tests build their own lists.
 * `docs/risks.md` R-100's shape again, and the third time on this branch that a policy was tested while its
 * wiring could change silently — `OutputActionIcons` exists for exactly the same reason.
 *
 * `MediaButtonLayoutTest` asserts the ordering *and* runs the real Media3 conversion over the result, so
 * the property that matters — an output action reaches the bar — fails a build when this changes.
 */
internal object MediaButtonLayout {

    /**
     * Publishes [outputActions] first so they win the two contested primary slots, then [skipActions] as
     * the fallback occupants of whatever the outputs did not take, then [overflowActions].
     *
     * **A skip is never dropped by losing.** Every skip declares overflow as the second link in its chain,
     * so a displaced one is relocated rather than discarded — pass 2 of the conversion emits a losing
     * button only if its chain contains overflow. That is asserted, because without it the skips would
     * vanish from every surface rather than move.
     */
    fun inPriorityOrder(
        outputActions: List<CommandButton>,
        skipActions: List<CommandButton>,
        overflowActions: List<CommandButton>,
    ): List<CommandButton> = outputActions + skipActions + overflowActions
}
