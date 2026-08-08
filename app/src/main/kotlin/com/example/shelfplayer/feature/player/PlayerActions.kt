package com.example.shelfplayer.feature.player

import androidx.compose.runtime.Immutable
import kotlin.time.Duration

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
    val onSkipBy: (Duration) -> Unit,
    val onOpenSleepTimer: () -> Unit,
    val onOpenChapters: () -> Unit,
    val onCollapse: () -> Unit,
)
