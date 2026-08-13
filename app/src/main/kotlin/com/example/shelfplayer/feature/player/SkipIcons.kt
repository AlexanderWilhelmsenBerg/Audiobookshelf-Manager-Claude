package com.example.shelfplayer.feature.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-007 — the skip glyph for a configurable interval.
 *
 * ### Why this needs any thought
 *
 * Material's skip icons have the number drawn *into* them: `Replay30` is a circular arrow with "30" inside
 * it. That is exactly right while the interval is hardcoded at thirty and exactly wrong the moment it is
 * configurable — a button reading "30" that jumps forty-five seconds is worse than one with no number at
 * all, because the user has no reason to distrust it.
 *
 * Material ships 5, 10 and 30 in both directions and nothing else. So: the numbered glyph when it tells the
 * truth, and a plain replay/fast-forward arrow when it would not. The seconds are always in the content
 * description either way, which is what a screen reader announces and what the numberless case relies on.
 */
internal object SkipIcons {

    fun back(interval: Duration): ImageVector = when (interval.inWholeSeconds) {
        FIVE -> Icons.Filled.Replay5
        TEN -> Icons.Filled.Replay10
        THIRTY -> Icons.Filled.Replay30
        // A plain double-arrow rather than a wrong number. Not a mirrored `Replay30`: the owner reported
        // exactly that once and the "30" rendered backwards.
        else -> Icons.Filled.FastRewind
    }

    fun forward(interval: Duration): ImageVector = when (interval.inWholeSeconds) {
        FIVE -> Icons.Filled.Forward5
        TEN -> Icons.Filled.Forward10
        THIRTY -> Icons.Filled.Forward30
        else -> Icons.Filled.FastForward
    }

    private const val FIVE = 5L
    private const val TEN = 10L
    private const val THIRTY = 30L
}
