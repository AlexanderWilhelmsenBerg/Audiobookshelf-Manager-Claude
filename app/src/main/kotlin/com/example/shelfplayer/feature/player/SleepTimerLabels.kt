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

/**
 * `29:31`, ticking every second.
 *
 * The minutes-only label reads as **stuck**: it changes once a minute, so a listener watching it for a
 * few seconds sees a number that does not move and concludes the timer is not running. A countdown has
 * to be seen to count, which means seconds on the face of it.
 *
 * Hours are folded into the minutes — `90:00` rather than `1:30:00` — because the longest preset is
 * ninety minutes and a three-part clock for that is harder to read at a glance than a two-part one.
 */
@Composable
@ReadOnlyComposable
fun Duration.asCountdownLabel(): String {
    val total = inWholeSeconds.coerceAtLeast(0)
    return stringResource(
        R.string.sleep_timer_countdown,
        total / SECONDS_PER_MINUTE,
        total % SECONDS_PER_MINUTE,
    )
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

/**
 * `1:04:12` on a long book, `12:30` on a short one. Hours only when there are any.
 *
 * Shared by the player's elapsed/remaining pair and by the chapter list, so a chapter's start time and
 * the position you land on after tapping it are formatted by the same function — two formatters would
 * eventually disagree about rounding, and the user would see a chapter start at `12:30` and the player
 * land on `12:29`.
 */
@Composable
@ReadOnlyComposable
fun Duration.asChapterClock(): String {
    val total = inWholeSeconds.coerceAtLeast(0)
    val hours = total / SECONDS_PER_HOUR
    val minutes = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = total % SECONDS_PER_MINUTE
    return if (hours > 0) {
        stringResource(R.string.player_clock_hours, hours, minutes, seconds)
    } else {
        stringResource(R.string.player_clock, minutes, seconds)
    }
}

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
