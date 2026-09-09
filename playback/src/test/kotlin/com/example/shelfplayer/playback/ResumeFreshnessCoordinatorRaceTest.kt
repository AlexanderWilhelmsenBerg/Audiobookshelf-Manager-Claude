package com.example.shelfplayer.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.AcknowledgedPause
import com.example.shelfplayer.core.model.playback.ExternalSessionCheck
import com.example.shelfplayer.domain.playback.ResumeBaseline
import com.example.shelfplayer.domain.realtime.RealtimeProgressEvidenceStore
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Issue #91 — ownership races and fresh-start semantics at the real coordinator boundary.
 *
 * Race tests suspend the real REST freshness call after [ResumeFreshnessCoordinator.preparePlay] has allocated
 * its request generation. A newer command then takes ownership before the server answer is released. The old
 * Play must resolve as [ResumePlayPreparation.Superseded], which is the contract consumed by PlaybackService.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ResumeFreshnessCoordinatorRaceTest {

    @Test
    fun `pause seek stop media skip and auto rewind beat a suspended REST freshness check`() = runTest {
        listOf(
            ResumeInvalidation.Pause,
            ResumeInvalidation.Seek,
            ResumeInvalidation.Stop,
            ResumeInvalidation.MediaChanged,
            ResumeInvalidation.NotificationSkip,
            ResumeInvalidation.AutoRewind,
        ).forEach { origin ->
            assertSuspendedRestIsSuperseded(this, origin)
        }
    }

    @Test
    fun `a second Play supersedes an older suspended Play`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var calls = 0
        val fixture = fixture(
            check = { _, _ ->
                calls += 1
                if (calls == 1) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                ExternalSessionCheck.Current
            },
        )

        val first = async(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.preparePlay() }
        firstStarted.await()

        val second = fixture.coordinator.preparePlay()
        assertIs<ResumePlayPreparation.Ready>(second)

        releaseFirst.complete(Unit)
        assertIs<ResumePlayPreparation.Superseded>(first.await())
    }

    @Test
    fun `fresh server start survives exactly its initial media install and then bypasses once`() = runTest {
        var checks = 0
        val fixture = fixture(
            check = { _, _ ->
                checks += 1
                ExternalSessionCheck.Current
            },
        )
        fixture.coordinator.onSessionOpened(serverSession(), initialPlayWillFollow = true)

        assertFalse(
            fixture.coordinator.consumeFreshStart(),
            "the server token is not usable before the newly opened item has been installed",
        )

        fixture.coordinator.onSessionOpened(serverSession(), initialPlayWillFollow = true)
        fixture.coordinator.invalidate(ResumeInvalidation.MediaChanged)

        assertTrue(fixture.coordinator.consumeFreshStart())
        assertFalse(fixture.coordinator.consumeFreshStart())
        assertEquals(0, checks, "the immediate first Play must trust the fresh server session")

        // Once audio has moved, the fresh session's staged baseline is no longer a pause. A real pause and
        // server acknowledgement establishes the next freshness boundary.
        fixture.baseline.onLocalMove()
        val generation = fixture.baseline.onPaused(BOOK, 12.minutes)
        assertTrue(fixture.baseline.onPositionAccepted(BOOK, 12.minutes, generation))

        assertIs<ResumePlayPreparation.Ready>(fixture.coordinator.preparePlay())
        assertEquals(1, checks)
    }

    @Test
    fun `generic set media open cannot leave a fresh exemption for a later Play`() = runTest {
        var checks = 0
        val fixture = fixture(
            check = { _, _ ->
                checks += 1
                ExternalSessionCheck.Ahead(20.minutes)
            },
        )

        // PlaybackService's generic onSetMediaItems/open path is arm-only: installing the returned item is
        // a MediaChanged, but Media3 does not promise a Play as part of that operation.
        fixture.coordinator.onSessionOpened(serverSession(), initialPlayWillFollow = false)
        fixture.coordinator.invalidate(ResumeInvalidation.MediaChanged)

        assertFalse(fixture.coordinator.consumeFreshStart())
        val laterPlay = assertIs<ResumePlayPreparation.Ready>(fixture.coordinator.preparePlay())
        assertIs<ResumeFreshnessDecision.Adopt>(laterPlay.plan.decision)
        assertEquals(1, checks, "the later Play must reconcile freshness instead of consuming stale state")
    }

    @Test
    fun `pause and seek before initial Play cancel the fresh exemption`() = runTest {
        val fixture = fixture(check = { _, _ -> ExternalSessionCheck.Current })

        listOf(ResumeInvalidation.Pause, ResumeInvalidation.Seek).forEach { origin ->
            fixture.coordinator.onSessionOpened(serverSession(), initialPlayWillFollow = true)
            fixture.coordinator.invalidate(ResumeInvalidation.MediaChanged)
            fixture.coordinator.invalidate(origin)
            assertFalse(fixture.coordinator.consumeFreshStart(), "$origin must cancel the initial-Play token")
        }
    }

    @Test
    fun `a second media replacement before first Play cancels the fresh exemption`() = runTest {
        val fixture = fixture(check = { _, _ -> ExternalSessionCheck.Current })
        fixture.coordinator.onSessionOpened(serverSession(), initialPlayWillFollow = true)

        fixture.coordinator.invalidate(ResumeInvalidation.MediaChanged)
        fixture.coordinator.invalidate(ResumeInvalidation.MediaChanged)

        assertFalse(fixture.coordinator.consumeFreshStart())
    }

    @Test
    fun `wrong book or profile cannot consume a fresh exemption`() = runTest {
        val fixture = fixture(check = { _, _ -> ExternalSessionCheck.Current })

        fixture.coordinator.onSessionOpened(
            serverSession(bookId = LibraryItemId("book-b")),
            initialPlayWillFollow = true,
        )
        fixture.coordinator.invalidate(ResumeInvalidation.MediaChanged)
        assertFalse(fixture.coordinator.consumeFreshStart())

        fixture.coordinator.onSessionOpened(
            serverSession(profileId = ProfileId("profile-b")),
            initialPlayWillFollow = true,
        )
        fixture.coordinator.invalidate(ResumeInvalidation.MediaChanged)
        assertFalse(fixture.coordinator.consumeFreshStart())
    }

    @Test
    fun `armed server session does not get the immediate Play exemption`() = runTest {
        var checks = 0
        val fixture = fixture(
            check = { _, _ ->
                checks += 1
                ExternalSessionCheck.Current
            },
        )
        fixture.coordinator.onSessionOpened(serverSession(), initialPlayWillFollow = false)
        fixture.coordinator.invalidate(ResumeInvalidation.MediaChanged)

        assertFalse(fixture.coordinator.consumeFreshStart())
        assertIs<ResumePlayPreparation.Ready>(fixture.coordinator.preparePlay())
        assertEquals(1, checks)
    }

    @Test
    fun `offline session without server baseline keeps newer local position instead of adopting older server`() =
        runTest {
            var checks = 0
            val fixture = fixture(
                acknowledgedBaseline = false,
                localPosition = 60.minutes,
                check = { _, _ ->
                    checks += 1
                    ExternalSessionCheck.Ahead(30.minutes)
                },
            )
            fixture.coordinator.onSessionOpened(serverSession(id = ""), initialPlayWillFollow = true)

            assertFalse(
                fixture.coordinator.consumeFreshStart(),
                "blank id is local evidence, not a fresh server session",
            )
            val preparation = assertIs<ResumePlayPreparation.Ready>(fixture.coordinator.preparePlay())
            val current = assertIs<ResumeFreshnessDecision.Current>(preparation.plan.decision)

            assertEquals(FreshnessEvidenceSource.LocalUnverified, current.source)
            assertEquals(0, checks, "without a server-acknowledged pause there is nothing safe to compare remotely")
        }

    private suspend fun assertSuspendedRestIsSuperseded(scope: TestScope, origin: ResumeInvalidation) {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = scope.fixture(
            check = { _, _ ->
                started.complete(Unit)
                release.await()
                ExternalSessionCheck.Current
            },
        )

        val preparing = scope.async(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.preparePlay() }
        started.await()
        fixture.coordinator.invalidate(origin)
        release.complete(Unit)

        assertIs<ResumePlayPreparation.Superseded>(preparing.await())
    }

    private fun TestScope.fixture(
        acknowledgedBaseline: Boolean = true,
        localPosition: Duration = BASELINE_POSITION,
        check: suspend (LibraryItemId, AcknowledgedPause?) -> ExternalSessionCheck,
    ): Fixture {
        val baseline = ResumeBaseline()
        if (acknowledgedBaseline) {
            val generation = baseline.onPaused(BOOK, BASELINE_POSITION)
            assertTrue(baseline.onPositionAccepted(BOOK, BASELINE_POSITION, generation))
        }
        val coordinator = ResumeFreshnessCoordinator(
            playback = FakePlaybackRepository(check),
            profiles = FakeProfileRepository(PROFILE),
            baseline = baseline,
            realtime = RealtimeProgressEvidenceStore(),
            logger = NO_OP_LOGGER,
            applicationScope = backgroundScope,
            mainDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        coordinator.attach(player(localPosition))
        return Fixture(coordinator, baseline)
    }

    private fun player(position: Duration): Player {
        val extras = Bundle().apply { putString(MediaItems.KEY_OWNER_PROFILE_ID, PROFILE.value) }
        val item = MediaItem.Builder()
            .setMediaId(BOOK.value)
            .setMediaMetadata(MediaMetadata.Builder().setExtras(extras).build())
            .build()
        return Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getMediaItemCount" -> 1
                "getCurrentMediaItem" -> item
                "getCurrentPosition" -> position.inWholeMilliseconds
                else -> defaultValue(method.returnType)
            }
        } as Player
    }

    private fun serverSession(
        id: String = "session-a",
        profileId: ProfileId = PROFILE,
        bookId: LibraryItemId = BOOK,
    ) = PlaybackSession(
        id = id,
        profileId = profileId,
        bookId = bookId,
        title = "Test book",
        author = null,
        coverUrl = null,
        startAt = BASELINE_POSITION,
        duration = 120.minutes,
        tracks = emptyList(),
        chapters = emptyList(),
    )

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    private data class Fixture(val coordinator: ResumeFreshnessCoordinator, val baseline: ResumeBaseline)

    private class FakePlaybackRepository(
        private val check: suspend (LibraryItemId, AcknowledgedPause?) -> ExternalSessionCheck,
    ) : PlaybackRepository {
        override suspend fun openSession(bookId: LibraryItemId): AppResult<PlaybackSession> = error("unused")

        override suspend fun recordPosition(
            bookId: LibraryItemId,
            position: Duration,
            duration: Duration,
            owner: ProfileId?,
        ): AppResult<Unit> = error("unused")

        override suspend fun setFinished(
            bookId: LibraryItemId,
            isFinished: Boolean,
            position: Duration,
        ): AppResult<Unit> = error("unused")

        override suspend fun checkServerPosition(
            bookId: LibraryItemId,
            baseline: AcknowledgedPause?,
        ): ExternalSessionCheck = check(bookId, baseline)
    }

    private class FakeProfileRepository(private var active: ProfileId?) : ProfileRepository {
        override fun observeProfiles(): Flow<List<Profile>> = emptyFlow()

        override fun observeServers(): Flow<List<Server>> = emptyFlow()

        override fun observeActiveProfile(): Flow<Profile?> = emptyFlow()

        override suspend fun activeProfileId(): ProfileId? = active

        override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> {
            active = profileId
            return AppResult.Success(Unit)
        }
    }

    private companion object {
        val PROFILE = ProfileId("profile-a")
        val BOOK = LibraryItemId("book-a")
        val BASELINE_POSITION = 10.minutes
        val NO_OP_LOGGER = object : Logger {
            override fun log(event: LogEvent) = Unit
        }
    }
}
