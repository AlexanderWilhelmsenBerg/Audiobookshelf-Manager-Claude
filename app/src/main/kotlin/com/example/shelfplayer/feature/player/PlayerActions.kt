package com.example.shelfplayer.feature.player

import androidx.compose.runtime.Immutable
import com.example.shelfplayer.core.model.playback.AudioOutput
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
 * PRODUCT_SPEC PLAY-002 — the outputs this book can be sent to, and the callback that sends it.
 *
 * The same bundling as [SkipControls] and for the same reason: the list and the action that acts on it must
 * not be able to disagree, and a screen or a preview names one object rather than two.
 */
@Immutable
data class OutputControls(
    val outputs: List<AudioOutput>,
    /**
     * PRODUCT_SPEC PLAY-002 — the id the listener *chose*, or `null` for *Automatic*.
     *
     * Separate from [AudioOutput.isActive], which is where the platform says the audio actually went. They
     * are two facts and a device run proved they can disagree: choosing the phone speaker while a headset
     * is connected leaves the sound in the headset on some devices, and the app has no way to force it. The
     * menu ticks the choice and labels the route, so the disagreement is visible rather than a tick that
     * quietly lies.
     */
    val selectedId: String?,
    val onSelect: (String?) -> Unit,
) {
    companion object {
        /** For a preview, or a test not exercising the chooser. */
        val Inert = OutputControls(outputs = emptyList(), selectedId = null, onSelect = {})
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
    /** PRODUCT_SPEC PLAY-003 — opens the history pane. */
    val onOpenHistory: () -> Unit = {},
    /** PRODUCT_SPEC 11.1 — opens the bookmark list. */
    val onOpenBookmarks: () -> Unit = {},
    /**
     * PRODUCT_SPEC 11.1 — keeps wherever the listener is, without opening anything.
     *
     * A long press on the bookmark control rather than a second button: the row is already five controls
     * wide, and "keep this spot" and "show me what I kept" are the same idea at two depths.
     */
    val onAddBookmark: () -> Unit = {},
    val onCollapse: () -> Unit,
    /** PRODUCT_SPEC PLAY-001 — what the listener presses when playback stopped on an error. */
    val onRetry: () -> Unit = {},
)
