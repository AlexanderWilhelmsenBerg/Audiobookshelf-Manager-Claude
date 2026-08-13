package com.example.shelfplayer.feature.player

import androidx.compose.runtime.Immutable
import com.example.shelfplayer.core.model.playback.SkipIntervals
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-007 — the skip buttons: how far they go, and what they call.
 *
 * The interval travels with the callbacks because the two must not disagree. A button labelled from one
 * value and wired to another is the bug this bundle makes unrepresentable — and it is the bug a configurable
 * interval invites, because the label and the action are read from different places in the tree.
 */
@Immutable
data class SkipControls(val intervals: SkipIntervals, val onBack: () -> Unit, val onForward: () -> Unit) {
    companion object {
        /** For a preview or a test that is not exercising the skips. */
        val Inert = SkipControls(SkipIntervals.Default, onBack = {}, onForward = {})
    }
}

/**
 * What the full player can do, as one parameter.
 *
 * The same shape `HomeActions` uses, and for the same reason: the screen had grown past the parameter
 * limit one callback at a time, and every new control would push it further. Bundling them also means a
 * preview or a test names one object rather than seven no-op lambdas.
 *
 * `@Immutable` so Compose can skip the player when only the playback state changed — the callbacks are
 * method references on a view model that outlives every recomposition.
 */
@Immutable
data class PlayerActions(
    val onTogglePlayPause: () -> Unit,
    val onSeekTo: (Duration) -> Unit,
    /** PRODUCT_SPEC PLAY-007 — opens the speed chooser. */
    val onOpenSpeed: () -> Unit,
    val onOpenSleepTimer: () -> Unit,
    val onOpenChapters: () -> Unit,
    val onCollapse: () -> Unit,
    /** PRODUCT_SPEC PLAY-001 — what the listener presses when playback stopped on an error. */
    val onRetry: () -> Unit = {},
)
