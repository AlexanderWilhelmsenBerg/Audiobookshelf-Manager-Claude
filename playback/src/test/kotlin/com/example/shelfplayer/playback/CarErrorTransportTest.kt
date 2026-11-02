package com.example.shelfplayer.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CarErrorTransportTest {
    @Test
    fun `actual service Car Play selects Car after error despite retained raw Play intent`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            val failed = failedPlayer(fixture.player)
            route(fixture, failed, true)
            assertNull(
                fixture.service.audioOutputs.explicitSelection.value,
                "unattributed Play cannot guess Car or headset",
            )
            fixture.fromCarController { route(fixture, failed, false) }
            assertNull(fixture.service.audioOutputs.explicitSelection.value, "Pause cannot choose a destination")
            fixture.fromCarController { route(fixture, failed, true) }
            assertEquals(
                OutputIntentOwner.Destination.Car,
                fixture.service.audioOutputs.explicitSelection.value?.destination,
            )
            assertFalse(fixture.player.playWhenReady, "route selection alone must not issue Play")
            assertEquals(0, fixture.rawPlayCalls)
        }
    }

    @Test
    fun `Car retry after decoder failure cannot steal an explicitly locked headset`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.armLogicalLock()
            val selected = fixture.service.audioOutputs.explicitSelection.value
            fixture.fromCarController { route(fixture, failedPlayer(fixture.player), true) }
            assertEquals(selected, fixture.service.audioOutputs.explicitSelection.value)
            assertEquals(0, fixture.rawPlayCalls)
        }
    }

    private fun failedPlayer(delegate: ExoPlayer): ExoPlayer = Proxy.newProxyInstance(
        ExoPlayer::class.java.classLoader,
        arrayOf(ExoPlayer::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getPlayWhenReady" -> true

            "getPlayerError" -> ExoPlaybackException.createForUnexpected(
                IllegalStateException("private"),
                PlaybackException.ERROR_CODE_DECODING_FAILED,
            )

            else -> method.invoke(delegate, *args.orEmpty())
        }
    } as ExoPlayer

    private fun route(fixture: PlaybackServiceCommandContinuityFixture, player: ExoPlayer, play: Boolean) {
        PlaybackService::class.java.getDeclaredMethod(
            "routeExplicitCarPlay",
            Boolean::class.javaPrimitiveType,
            ExoPlayer::class.java,
        ).apply { isAccessible = true }.invoke(fixture.service, play, player)
    }
}
