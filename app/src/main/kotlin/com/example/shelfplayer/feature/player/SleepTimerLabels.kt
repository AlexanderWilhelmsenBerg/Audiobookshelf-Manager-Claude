package com.example.shelfplayer.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.playback.SleepTimerMode
import com.example.shelfplayer.core.model.playback.SleepTimerOutcome
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-008 — how a timer's numbers and outcomes read.
 *
 * Kept together, and out of the composables that use them, because the same three labels appear on the
 * mini player, in the sheet and in the settings history — and three copies of "round the minutes up"
 * is three chances to round one of them down.
 */

/**
 * "12 min" while there are minutes; "45 s" in the last one.
 *
 * Rounding **up** is deliberate: a timer with 61 seconds left that says "1 min", then says "1 min"
 * again a second later, reads as stuck. Rounding up counts 2 → 1 → seconds and never repeats itself.
 */
@Composable
@ReadOnlyComposable
fun Duration.asShortLabel(): String {
    val seconds = inWholeSeconds.coerceAtLeast(0)
    if (seconds < SECONDS_PER_MINUTE) return stringResource(R.string.sleep_timer_seconds, seconds.toInt())
    val minutes = (seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE
    return stringResource(R.string.sleep_timer_minutes, minutes.toInt())
}

@Composable
@ReadOnlyComposable
fun SleepTimerMode.label(): String = when (this) {
    is SleepTimerMode.Fixed -> stringResource(R.string.sleep_timer_minutes, length.inWholeMinutes.toInt())
    SleepTimerMode.EndOfChapter -> stringResource(R.string.sleep_timer_end_of_chapter)
}

/** A `null` outcome is a timer that has not ended, which is a state and not a missing value. */
@Composable
@ReadOnlyComposable
fun SleepTimerOutcome?.label(): String = when (this) {
    SleepTimerOutcome.Expired -> stringResource(R.string.sleep_timer_outcome_expired)
    SleepTimerOutcome.Cancelled -> stringResource(R.string.sleep_timer_outcome_cancelled)
    SleepTimerOutcome.PlaybackStopped -> stringResource(R.string.sleep_timer_outcome_playbackstopped)
    SleepTimerOutcome.Abandoned -> stringResource(R.string.sleep_timer_outcome_abandoned)
    null -> stringResource(R.string.sleep_timer_outcome_running)
}

private const val SECONDS_PER_MINUTE = 60L
