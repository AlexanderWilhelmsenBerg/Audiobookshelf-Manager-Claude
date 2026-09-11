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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #138 — the second half of Media3 cold playback resumption must use the shared #93 policy.
 *
 * Media3 1.11 owns the empty-session half: controller Play calls `onPlaybackResumption`, installs the
 * returned item at its returned start position, prepares it, then issues Play on the now-loaded player.
 * `ResumeFreshnessPlayerTest.empty session Play preserves Media3 playback resumption` protects the first
 * half. These tests protect the loaded Play that follows: cold resumption deliberately has no fresh-start
 * exemption, so the installed `/play` position is only the local side of the normal freshness decision.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ColdPlaybackResumptionFreshnessTest {

    @Test
    fun `cold loaded Play adopts stored progress when play session starts at zero`() = runTest {
        val stored = 1_023.04.seconds
        val fixture = fixture(
            serverStart = Duration.ZERO,
            loadedPosition = Duration.ZERO,
            check = { _, _ -> ExternalSessionCheck.Ahead(stored) },
        )

        val preparation = fixture.afterMedia3InstalledColdItem()

        val ready = assertIs<ResumePlayPreparation.Ready>(preparation)
        val adopted = assertIs<ResumeFreshnessDecision.Adopt>(ready.plan.decision)
        assertEquals(stored, adopted.position)
        assertEquals(FreshnessEvidenceSource.Rest, adopted.source)
    }

    @Test
    fun `debug zero install leaves trusted progress for the coordinator to adopt`() = runTest {
        val trusted = 25.minutes
        val diagnostic = ArmedColdResumeDiagnostic()
        val installedPositionMs = ColdResumeStartPosition(diagnostic)
            .forPlaybackResumption(trusted.inWholeMilliseconds)

        assertEquals(0L, installedPositionMs)
        assertEquals(ColdResumeDiagnosticState.Consumed, diagnostic.state.value)

        val fixture = fixture(
            serverStart = trusted,
            loadedPosition = installedPositionMs.milliseconds,
            check = { _, _ -> ExternalSessionCheck.Ahead(trusted) },
        )

        val preparation = fixture.afterMedia3InstalledColdItem()

        val ready = assertIs<ResumePlayPreparation.Ready>(preparation)
        val adopted = assertIs<ResumeFreshnessDecision.Adopt>(ready.plan.decision)
        assertEquals(trusted, fixture.serverStart, "the real opened session evidence must remain untouched")
        assertEquals(trusted, adopted.position)
        assertEquals(FreshnessEvidenceSource.Rest, adopted.source)
    }

    @Test
    fun `cold loaded Play preserves intentional remote rewind to zero`() = runTest {
        val fixture = fixture(
            serverStart = 10.minutes,
            loadedPosition = 10.minutes,
            check = { _, _ -> ExternalSessionCheck.Ahead(Duration.ZERO) },
        )

        val preparation = fixture.afterMedia3InstalledColdItem()

        val adopted = assertIs<ResumeFreshnessDecision.Adopt>(
            assertIs<ResumePlayPreparation.Ready>(preparation).plan.decision,
        )
        assertEquals(
            Duration.ZERO,
            adopted.position,
            "resume freshness is bidirectional; this is not max(local, remote)",
        )
    }

    @Test
    fun `cold loaded Play falls back to installed position when freshness lookup is unavailable`() = runTest {
        val fixture = fixture(
            serverStart = 10.minutes,
            loadedPosition = 10.minutes,
            check = { _, _ -> ExternalSessionCheck.Unavailable },
        )

        val preparation = fixture.afterMedia3InstalledColdItem()

        val current = assertIs<ResumeFreshnessDecision.Current>(
            assertIs<ResumePlayPreparation.Ready>(preparation).plan.decision,
        )
        assertEquals(FreshnessEvidenceSource.LocalUnverified, current.source)
    }

    @Test
    fun `cold loaded Play does not seek for ordinary small drift`() = runTest {
        val fixture = fixture(
            serverStart = 10.minutes,
            loadedPosition = 10.minutes,
            check = { _, _ -> ExternalSessionCheck.Ahead(11.minutes) },
        )

        val preparation = fixture.afterMedia3InstalledColdItem()

        assertIs<ResumeFreshnessDecision.Current>(
            assertIs<ResumePlayPreparation.Ready>(preparation).plan.decision,
        )
    }

    private suspend fun Fixture.afterMedia3InstalledColdItem(): ResumePlayPreparation {
        // `BookChanges.onBookOpened` stages the `/play` position and the item transition promotes it.
        // Model that order here before Media3's automatic loaded-item Play reaches the forwarding player.
        baseline.stageServerPosition(BOOK, serverStart)
        coordinator.onSessionOpened(serverSession(serverStart), initialPlayWillFollow = false)
        baseline.onBookClosed()
        coordinator.invalidate(ResumeInvalidation.MediaChanged)

        assertFalse(
            coordinator.consumeFreshStart(),
            "cold Media3 resumption must not mint the direct-play fresh-session exemption",
        )
        return coordinator.preparePlay()
    }

    private fun TestScope.fixture(
        serverStart: Duration,
        loadedPosition: Duration,
        check: suspend (LibraryItemId, AcknowledgedPause?) -> ExternalSessionCheck,
    ): Fixture {
        val baseline = ResumeBaseline()
        val coordinator = ResumeFreshnessCoordinator(
            playback = FakePlaybackRepository(check),
            profiles = FakeProfileRepository(PROFILE),
            baseline = baseline,
            realtime = RealtimeProgressEvidenceStore(),
            logger = NO_OP_LOGGER,
            applicationScope = backgroundScope,
            mainDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        coordinator.attach(player(loadedPosition))
        return Fixture(coordinator, baseline, serverStart)
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

    private fun serverSession(startAt: Duration) = PlaybackSession(
        id = "session-cold",
        profileId = PROFILE,
        bookId = BOOK,
        title = "Test book",
        author = null,
        coverUrl = null,
        startAt = startAt,
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

    private data class Fixture(
        val coordinator: ResumeFreshnessCoordinator,
        val baseline: ResumeBaseline,
        val serverStart: Duration,
    )

    private class ArmedColdResumeDiagnostic : ColdResumeDiagnostic {
        private val mutableState = MutableStateFlow(ColdResumeDiagnosticState.Armed)
        override val state: StateFlow<ColdResumeDiagnosticState> = mutableState

        override fun arm() {
            mutableState.value = ColdResumeDiagnosticState.Armed
        }

        override fun consumeForColdPlaybackResumption(): Boolean {
            if (mutableState.value != ColdResumeDiagnosticState.Armed) return false
            mutableState.value = ColdResumeDiagnosticState.Consumed
            return true
        }
    }

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
        val NO_OP_LOGGER = object : Logger {
            override fun log(event: LogEvent) = Unit
        }
    }
}
