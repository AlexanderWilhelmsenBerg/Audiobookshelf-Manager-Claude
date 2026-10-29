package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.domain.lock.ProfileLockGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackResumptionCallbacksTest {
    @Test
    fun `metadata description opens no session and consumes no diagnostic or passive Play`() = runTest {
        val fixture = Fixture()
        val result = fixture.callback.describe(fixture.resolution.capture())
        assertEquals("resume-description", result.mediaItems.single().mediaId)
        assertEquals(0, fixture.opened)
        assertEquals(ColdResumeDiagnosticState.Armed, fixture.diagnostic.state.value)
    }

    @Test
    fun `accepted cold playback returns media and consumes diagnostic`() = runTest {
        val fixture = Fixture()
        val result = fixture.callback.forPlayback(fixture.resolution.capture())
        assertEquals("resolved", result.mediaItems.single().mediaId)
        assertEquals(0L, result.startPositionMs)
        assertEquals(1, fixture.opened)
        assertEquals(1, fixture.accepted)
        assertEquals(ColdResumeDiagnosticState.Consumed, fixture.diagnostic.state.value)
    }

    @Test
    fun `locked cold playback opens nothing and creates no passive intent`() = runTest {
        val fixture = Fixture().apply { locked = true }
        assertTrue(fixture.callback.forPlayback(fixture.resolution.capture()).mediaItems.isEmpty())
        assertEquals(0, fixture.opened)
    }

    @Test
    fun `candidate refresh superseded by Stop cannot publish a cold queue`() = runTest {
        val fixture = Fixture()
        fixture.candidateRead = { fixture.generation++ }
        assertFailsWith<CancellationException> { fixture.callback.forPlayback(fixture.resolution.capture()) }
        assertEquals(0, fixture.opened)
        assertEquals(ColdResumeDiagnosticState.Armed, fixture.diagnostic.state.value)
    }

    @Test
    fun `superseded empty candidate cannot clear newer listener media`() = runTest {
        val fixture = Fixture().apply { missing = true }
        fixture.candidateRead = { fixture.generation++ }
        assertFailsWith<CancellationException> { fixture.callback.forPlayback(fixture.resolution.capture()) }
        assertEquals(0, fixture.opened)
    }

    @Test
    fun `metadata from a superseded profile request is withheld`() = runTest {
        val fixture = Fixture()
        fixture.metadataRead = { fixture.profileGeneration += 2 }
        assertFailsWith<CancellationException> { fixture.callback.describe(fixture.resolution.capture()) }
        assertEquals(0, fixture.opened)
    }

    @Test
    fun `Stop during a lock lookup cannot answer with an empty successful playback queue`() = runTest {
        val fixture = Fixture()
        fixture.beforeLock = {
            fixture.generation++
            fixture.locked = true
        }
        assertFailsWith<CancellationException> { fixture.callback.forPlayback(fixture.resolution.capture()) }
        assertEquals(0, fixture.opened)
    }

    private class Fixture {
        var generation = 0L
        var profileGeneration = 0L
        var opened = 0
        var accepted = 0
        var locked = false
        var missing = false
        var candidateRead: suspend () -> Unit = {}
        var metadataRead: suspend () -> Unit = {}
        var beforeLock: suspend () -> Unit = {}
        val diagnostic = object : ColdResumeDiagnostic {
            override val state = MutableStateFlow(ColdResumeDiagnosticState.Armed)
            override fun arm() {
                state.value = ColdResumeDiagnosticState.Armed
            }
            override fun consumeForColdPlaybackResumption(): Boolean {
                if (state.value != ColdResumeDiagnosticState.Armed) return false
                state.value = ColdResumeDiagnosticState.Consumed
                return true
            }
        }
        private val identity = Any()
        val resolution = ControllerQueueResolution(
            commandGeneration = { generation },
            profileGeneration = { profileGeneration },
            explicitSelectionSequence = { 0L },
            serviceOwner = { identity },
            activeProfileId = { PROFILE },
            isProfileLocked = { locked },
            prepare = { _, _ ->
                opened++
                CarPostConnectRestorer.Prepared(
                    session(),
                    MediaItems.Queue(MediaItem.Builder().setMediaId("resolved").build(), 42_000L),
                )
            },
            accept = { _, allowed, install ->
                if (allowed()) {
                    accepted++
                    install()
                    true
                } else {
                    false
                }
            },
        )
        val callback = PlaybackResumptionCallbacks(
            lastPlayedBookId = {
                candidateRead()
                BOOK.takeUnless { missing }
            },
            resumeItem = {
                metadataRead()
                MediaItem.Builder().setMediaId("resume-description").build()
            },
            resolution = resolution,
            lock = ProfileLockGuard {
                beforeLock()
                locked
            },
            coldResumeStartPosition = ColdResumeStartPosition(diagnostic),
            logger = object : Logger {
                override fun log(event: LogEvent) = Unit
            },
        )
    }

    private companion object {
        val PROFILE = ProfileId("first")
        val BOOK = LibraryItemId("book")
        fun session() = PlaybackSession(
            id = "remote-test", profileId = PROFILE, bookId = BOOK, title = "Test book",
            author = null, coverUrl = null, startAt = 42.seconds, duration = 1.hours,
            tracks = emptyList(), chapters = emptyList(),
        )
    }
}
