package com.example.shelfplayer.playback

import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.testing.TestAppClock
import kotlinx.coroutines.CoroutineDispatcher

/** Explicit fixture factory; production always receives the Hilt singleton. */
@Suppress("UNUSED_PARAMETER")
internal fun volumeEffectsForTest(
    dispatcher: CoroutineDispatcher,
    clock: AppClock = TestAppClock(),
): PlaybackVolumeEffects = PlaybackVolumeEffects(clock)
