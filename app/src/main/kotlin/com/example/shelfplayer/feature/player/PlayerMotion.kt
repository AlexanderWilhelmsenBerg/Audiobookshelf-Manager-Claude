package com.example.shelfplayer.feature.player

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween

/*
 * Hallmark Slice 1 — one calm motion vocabulary for the two presentations of the live player.
 *
 * Motion explains hierarchy here; it is not decoration. Every spec is a non-overshooting tween so the
 * mini-player chrome and the full-player overlay cannot drift back toward the spring/bounce behavior this
 * slice removes. Compose animation specs also participate in Android's system animation-duration scaling.
 */
internal object PlayerMotion {
    private const val MICRO_MILLIS = 140
    private const val STANDARD_MILLIS = 200
    private const val LARGE_ENTER_MILLIS = 260
    private const val LARGE_EXIT_MILLIS = 220
    private const val PREDICTIVE_CANCEL_MILLIS = 180

    fun <T> micro(): TweenSpec<T> = tween(
        durationMillis = MICRO_MILLIS,
        easing = FastOutSlowInEasing,
    )

    fun <T> standard(): TweenSpec<T> = tween(
        durationMillis = STANDARD_MILLIS,
        easing = FastOutSlowInEasing,
    )

    fun <T> largeEnter(): TweenSpec<T> = tween(
        durationMillis = LARGE_ENTER_MILLIS,
        easing = LinearOutSlowInEasing,
    )

    fun <T> largeExit(): TweenSpec<T> = tween(
        durationMillis = LARGE_EXIT_MILLIS,
        easing = FastOutLinearInEasing,
    )

    fun <T> predictiveCancel(): TweenSpec<T> = tween(
        durationMillis = PREDICTIVE_CANCEL_MILLIS,
        easing = FastOutSlowInEasing,
    )
}
