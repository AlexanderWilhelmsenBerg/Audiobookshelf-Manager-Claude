package com.example.shelfplayer.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-0016 — reading a book's position and length off the player.
 *
 * Since a book is one timeline window, both are simply what the player says. These exist for the one thing
 * that is *not* simple: Media3 reports an unknown duration as [C.TIME_UNSET], which is `Long.MIN_VALUE + 1`.
 * Handing that to a `Duration` produces a length of roughly minus three hundred million years, and every
 * caller that forgot to check would show it. One place to forget instead of six.
 *
 * Main thread only, as every [Player] read must be.
 */

/** Where the listener is in the book. */
internal fun Player.bookPosition(): Duration = currentPosition.coerceAtLeast(0).milliseconds

/** How long the book is, or [Duration.ZERO] while the player does not know yet. */
internal fun Player.bookDuration(): Duration = duration.orZeroWhenUnset()

/** [Duration.ZERO] for [C.TIME_UNSET] or any negative, which no caller of ours can use. */
internal fun Long.orZeroWhenUnset(): Duration = if (this == C.TIME_UNSET || this < 0) Duration.ZERO else milliseconds
