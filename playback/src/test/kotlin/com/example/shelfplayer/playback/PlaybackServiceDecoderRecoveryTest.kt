package com.example.shelfplayer.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.playback.BufferPreset
import com.example.shelfplayer.core.model.playback.FocusBehaviour
import com.example.shelfplayer.core.model.playback.PlaybackEqualizer
import com.example.shelfplayer.core.model.playback.PlaybackSettings
import com.example.shelfplayer.core.testing.TestAppClock
import com.example.shelfplayer.domain.lock.ProfileLockGuard
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaybackServiceDecoderRecoveryTest {
    @Test
    fun `prepare leaves the failed decoder stopped and the next Play swaps the session player once`() = runTest {
        Fixture(this).use { fixture ->
            fixture.failDecoder()
            val prepare = fixture.controller.handlePrepare()
            runCurrent()
            assertEquals(false, prepare.get())
            assertEquals(Player.STATE_IDLE, fixture.original.playbackState)
            assertEquals(0, fixture.replacements.size)

            val play = fixture.controller.handleSetPlayWhenReady(true)
            runCurrent()
            val replacement = fixture.replacements.single()
            assertEquals(true, play.get())
            assertNotSame(fixture.controller, fixture.session.player)
            assertSame(replacement, fixture.service.playbackField<ExoPlayer>("player"))
            assertEquals("fixture", fixture.session.player.currentMediaItem?.mediaId)
            assertEquals(42_000L, replacement.currentPosition)
            assertEquals(1.25f, replacement.playbackParameters.speed)
            assertTrue(replacement.playWhenReady)

            assertNull(fixture.controller.handleSetPlayWhenReady(false).get())
            assertTrue(replacement.playWhenReady)
        }
    }

    @Test
    fun `an automatic resume leaves the failed decoder stopped`() = runTest {
        Fixture(this).use { fixture ->
            fixture.failDecoder()
            assertFalse(fixture.service.governor.resume(explicit = false, bypassFreshness = true) { true })
            assertEquals(Player.STATE_IDLE, fixture.original.playbackState)
            assertTrue(fixture.replacements.isEmpty())
        }
    }

    @Test
    fun `newer Pause cancels recovery waiting to check the account lock`() = runTest {
        Fixture(this).use { fixture ->
            fixture.failDecoder()
            val lock = CompletableDeferred<Boolean>()
            fixture.service.lock = ProfileLockGuard { lock.await() }

            val play = fixture.controller.handleSetPlayWhenReady(true)
            runCurrent()
            assertFalse(play.isDone)
            fixture.controller.handleSetPlayWhenReady(false)
            lock.complete(false)
            runCurrent()

            assertEquals(false, play.get())
            assertSame(fixture.controller, fixture.session.player)
            assertTrue(fixture.replacements.isEmpty())
        }
    }

    private class Fixture(scope: TestScope) : AutoCloseable {
        private val context = RuntimeEnvironment.getApplication()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val clock = TestAppClock()
        private val logger = object : Logger {
            override fun log(event: LogEvent) = Unit
        }
        private val registry = LiveOutputEndpointRegistry()
        private val volume = volumeEffectsForTest(dispatcher, clock)
        private val router = AudioOutputRouter(
            context,
            logger,
            scope.backgroundScope,
            dispatcher,
            registry,
            volumeEffects = volume,
        )
        val service: PlaybackService = Robolectric.buildService(PlaybackService::class.java).get()
        val original = ExoPlayer.Builder(context).build()
        val replacements = mutableListOf<ExoPlayer>()
        val controller: ResumeFreshnessPlayer
        val session: MediaLibrarySession

        init {
            PlaybackServiceFixtureSetup(
                service, router, registry, original, volume, scope, dispatcher, clock, logger,
                profiles = object : ProfileRepository by unusedPlaybackDependency() {
                    override suspend fun activeProfileId(): ProfileId = ProfileId("post-connect-profile")
                    override fun activeProfileGeneration(): Long = 0
                },
            ).configure()
            PlaybackServiceSelectionFixtureSupport(
                context, dispatcher, scope.backgroundScope, clock, logger, service, original, volume, {},
            ).configurePostConnectRace()
            val settings = playbackDependencyProxy<PlaybackSettingsRepository> { method ->
                check(method == "observeSettings")
                flowOf(PlaybackSettings.Default)
            }
            service.metrics = PlaybackMetricsRecorder(clock)
            service.history = playbackDependencyProxy<PlaybackHistoryRepository> { Unit }
            service.playbackRepository = object : PlaybackRepository by unusedPlaybackDependency() {
                override suspend fun recordPosition(
                    bookId: LibraryItemId,
                    position: Duration,
                    duration: Duration,
                    owner: ProfileId?,
                ): AppResult<Unit> = AppResult.Success(Unit)
            }
            service.autoRewind = AutoRewindController(settings, clock, service.history, logger, scope.backgroundScope)
            service.players = object : PlayerFactory {
                override fun create(buffer: BufferPreset, focus: FocusBehaviour): ExoPlayer =
                    ExoPlayer.Builder(context).build().also(replacements::add)

                override fun setEqualizer(equalizer: PlaybackEqualizer) = Unit

                override fun bitmapLoader(): BitmapLoader = error("The fixture owns the session")
            }
            original.setMediaItem(fixtureItem(), 42_000L)
            original.setPlaybackSpeed(1.25f)
            router.attach(original)
            service.installPositionCheckpointHandlers()
            service.sleepTimer.attach(original)
            controller = service.createSessionPlayer(original)
            session = MediaLibrarySession.Builder(context, controller, object : MediaLibrarySession.Callback {}).build()
            service.setPlaybackField("session", session)
        }

        fun failDecoder() {
            val type = PlaybackService::class.java.declaredClasses.single { it.simpleName == "PlayerEvents" }
            val events = type.getDeclaredConstructor(PlaybackService::class.java)
                .apply { isAccessible = true }.newInstance(service) as Player.Listener
            events.onPlayerError(
                ExoPlaybackException.createForUnexpected(
                    IllegalStateException("fixture decoder failed"),
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                ),
            )
        }

        override fun close() {
            session.release()
            router.detach()
            service.resumeFreshness.attach(null)
            service.sessionSync.detach()
            service.sleepTimer.attach(null)
            replacements.forEach(ExoPlayer::release)
            if (replacements.isEmpty()) original.release()
        }

        private fun fixtureItem(): MediaItem {
            val extras = Bundle().apply { putString(MediaItems.KEY_OWNER_PROFILE_ID, "post-connect-profile") }
            return playbackFixtureItem("fixture").buildUpon()
                .setMediaMetadata(MediaMetadata.Builder().setExtras(extras).build())
                .build()
        }
    }
}
