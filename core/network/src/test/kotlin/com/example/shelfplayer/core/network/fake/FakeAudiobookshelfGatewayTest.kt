package com.example.shelfplayer.core.network.fake

import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.SeriesSequence
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerCapabilities
import com.example.shelfplayer.core.model.ServerCapability
import com.example.shelfplayer.core.model.library.BookSnapshot
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.network.fixture.FixtureLibraryLoader
import com.example.shelfplayer.core.testing.RecordingLogSink
import com.example.shelfplayer.core.testing.TestAppClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC 20 Phase 0 — the fake gateway is the Phase 0 data source, so it gets the same test
 * treatment a real one will.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeAudiobookshelfGatewayTest {

    private val sink = RecordingLogSink()
    private val gateway = FakeAudiobookshelfGateway(
        loader = FixtureLibraryLoader(),
        clock = TestAppClock(),
        logger = RedactingLogger(sink, DefaultRedactor(RedactionPolicy.Default)),
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private val fixtureProfile = ProfileId("fixture-profile")

    @Test
    fun `exposes the fixture server and profile`() = runTest {
        val server = gateway.account.currentServer()
        assertIs<AppResult.Success<Server>>(server)
        assertEquals("fixture-server", server.value.id.value)
        assertTrue(server.value.isFixture)

        val profile = gateway.account.currentProfile()
        assertIs<AppResult.Success<Profile>>(profile)
        assertEquals(fixtureProfile, profile.value.id)
        assertTrue(profile.value.isFixture)
    }

    /** PRODUCT_SPEC SYNC-001 — an unknown capability is unsupported, never assumed. */
    @Test
    fun `resolves only the capabilities the fixture declares`() = runTest {
        val result = gateway.capabilities.resolve()
        assertIs<AppResult.Success<ServerCapabilities>>(result)

        assertTrue(result.value.supports(ServerCapability.PlaybackSession))
        assertTrue(result.value.supports(ServerCapability.RangeDownload))
        assertFalse(result.value.supports(ServerCapability.SourceFileDelete))
        assertFalse(result.value.supports(ServerCapability.UserManagement))
    }

    @Test
    fun `lists the fixture libraries in display order`() = runTest {
        val result = gateway.library.listLibraries(fixtureProfile)
        assertIs<AppResult.Success<List<Library>>>(result)

        assertEquals(listOf("lib-fiction", "lib-nonfiction"), result.value.map { it.id.value })
        assertEquals(5, result.value.first().bookCount)
    }

    @Test
    fun `returns books with tracks, chapters and parsed series sequences`() = runTest {
        val result = gateway.library.listBooks(fixtureProfile, LibraryId("lib-fiction"))
        assertIs<AppResult.Success<List<BookSnapshot>>>(result)

        val first = result.value.single { it.book.id.value == "book-voyage-1" }
        assertEquals(2, first.tracks.size)
        assertEquals(3, first.chapters.size)
        assertEquals(
            SeriesSequence.Numeric("1", 1.0),
            first.book.seriesMemberships.single().sequence,
        )

        // PRODUCT_SPEC PLAY-003: an excluded server track is stored but not counted as playable.
        val tenth = result.value.single { it.book.id.value == "book-voyage-10" }
        assertEquals(3, tenth.tracks.size)
        assertEquals(2, tenth.book.trackCount)
        assertTrue(tenth.tracks.any { it.isExcluded })
    }

    /** PRODUCT_SPEC 11.3 — track offsets describe one continuous book timeline. */
    @Test
    fun `multi-file track offsets are contiguous`() = runTest {
        val result = gateway.library.listBooks(fixtureProfile, LibraryId("lib-fiction"))
        assertIs<AppResult.Success<List<BookSnapshot>>>(result)

        val playable = result.value
            .single { it.book.id.value == "book-voyage-1" }
            .tracks
            .filterNot { it.isExcluded }
            .sortedBy { it.index }

        var expectedOffset = kotlin.time.Duration.ZERO
        playable.forEach { track ->
            assertEquals(expectedOffset, track.startOffset)
            expectedOffset += track.duration
        }
        assertEquals(
            result.value.single { it.book.id.value == "book-voyage-1" }.book.duration,
            expectedOffset,
        )
    }

    /** PRODUCT_SPEC 5.2 — content is never returned for a profile the connection does not serve. */
    @Test
    fun `refuses a call for a profile the fixture does not serve`() = runTest {
        val result = gateway.library.listLibraries(ProfileId("someone-else"))

        assertIs<AppResult.Failure>(result)
        assertIs<AppError.Authorization>(result.error)
    }

    /** PRODUCT_SPEC 14.5 — even a rejection must not name the profile it rejected. */
    @Test
    fun `rejection is logged without exposing the requested profile`() = runTest {
        gateway.library.listLibraries(ProfileId("someone-else"))

        assertFalse(sink.text.contains("someone-else"))
        assertTrue(sink.text.contains("requestedProfile="))
    }

    @Test
    fun `a missing fixture resource is a typed compatibility error, not a crash`() {
        val result = FixtureLibraryLoader().load("fixtures/does-not-exist.json")

        assertIs<AppResult.Failure>(result)
        assertIs<AppError.ApiCompatibility>(result.error)
    }
}
