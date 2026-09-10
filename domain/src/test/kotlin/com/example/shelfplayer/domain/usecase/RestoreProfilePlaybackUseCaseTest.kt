package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.MediaProgress
import com.example.shelfplayer.core.testing.RecordingLogSink
import com.example.shelfplayer.domain.FakeLibraryRepository
import com.example.shelfplayer.domain.TEST_PROFILE
import com.example.shelfplayer.domain.TEST_SERVER
import com.example.shelfplayer.domain.book
import com.example.shelfplayer.domain.playback.StartupPlayer
import com.example.shelfplayer.domain.repository.RememberedBookRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * PRODUCT_SPEC 6.5 step 6 / BW-PLAY-01 — *"The new profile's last player state is restored paused."*
 *
 * These tests pin the distinction #115 introduces: the book identity belongs to this physical BookWave
 * install, while server progress timestamps may move independently on another client. The incoming profile's
 * remembered id chooses the book; its Room progress remains presentation/resume evidence only.
 */
class RestoreProfilePlaybackUseCaseTest {

    private val player = RecordingStartupPlayer()

    /** A remote client advancing B later must not replace locally remembered A. */
    @Test
    fun `the incoming profile's remembered book wins over newer remote progress`() = runTest {
        val library = FakeLibraryRepository(
            listOf(
                playedBook("local-a", at = "2026-08-01T10:00:00Z"),
                playedBook("remote-b", at = "2026-09-10T10:00:00Z"),
            ),
        )

        useCase(library, remembered = LibraryItemId("local-a"))(TEST_PROFILE)

        assertEquals(listOf(LibraryItemId("local-a")), player.armed)
    }

    /**
     * **Armed, never played.** 6.5.3 and 6.5.8 both forbid audio starting from a switch.
     */
    @Test
    fun `nothing is ever played`() = runTest {
        val library = FakeLibraryRepository(listOf(playedBook("resume", at = "2026-08-20T10:00:00Z")))

        useCase(library, remembered = LibraryItemId("resume"))(TEST_PROFILE)

        assertTrue(player.played.isEmpty(), "a switch must never start audio")
    }

    /** A finished remembered book has nothing left to resume, so it is not offered. */
    @Test
    fun `a finished remembered book is not restored`() = runTest {
        val library = FakeLibraryRepository(listOf(playedBook("done", at = "2026-08-20T10:00:00Z", finished = true)))

        useCase(library, remembered = LibraryItemId("done"))(TEST_PROFILE)

        assertTrue(player.armed.isEmpty())
    }

    /** A missing progress row is not resumable even if the durable identity still names the book. */
    @Test
    fun `a remembered book with no progress is not restored`() = runTest {
        val library = FakeLibraryRepository(listOf(book("untouched")))

        useCase(library, remembered = LibraryItemId("untouched"))(TEST_PROFILE)

        assertTrue(player.armed.isEmpty())
    }

    /** No durable local ownership means no heuristic server-recency backfill. */
    @Test
    fun `newer server progress does not invent remembered identity`() = runTest {
        val library = FakeLibraryRepository(listOf(playedBook("remote-only", at = "2026-09-10T10:00:00Z")))

        useCase(library, remembered = null)(TEST_PROFILE)

        assertTrue(player.armed.isEmpty())
    }

    /** An id that is no longer accessible for the incoming profile must not cross the authorization boundary. */
    @Test
    fun `an inaccessible remembered book is not restored`() = runTest {
        val library = FakeLibraryRepository(listOf(playedBook("visible", at = "2026-08-20T10:00:00Z")))

        useCase(library, remembered = LibraryItemId("revoked"))(TEST_PROFILE)

        assertTrue(player.armed.isEmpty())
    }

    /**
     * Both the durable identity and the library are read **for the profile named**, not whoever is active.
     */
    @Test
    fun `the remembered identity and library are read for the profile switched to`() = runTest {
        val library = FakeLibraryRepository(listOf(playedBook("theirs", at = "2026-08-20T10:00:00Z")))
        val remembered = RecordingRememberedBooks(mapOf(OTHER to LibraryItemId("theirs")))

        useCase(library, remembered)(OTHER)

        assertEquals(listOf(OTHER), remembered.requestedFor)
        assertEquals(listOf(OTHER), library.accessibleBooksRequestedFor)
        assertEquals(listOf(LibraryItemId("theirs")), player.armed)
    }

    private fun useCase(library: FakeLibraryRepository, remembered: LibraryItemId?) =
        useCase(library, RecordingRememberedBooks(mapOf(TEST_PROFILE to remembered)))

    private fun useCase(
        library: FakeLibraryRepository,
        remembered: RememberedBookRepository,
    ) = RestoreProfilePlaybackUseCase(
        library = library,
        rememberedBooks = remembered,
        player = player,
        logger = RedactingLogger(RecordingLogSink(), DefaultRedactor(RedactionPolicy.Default)),
    )

    private fun playedBook(id: String, at: String, finished: Boolean = false): Book {
        val base = book(id)
        return base.copy(
            progress = MediaProgress(
                serverId = TEST_SERVER,
                profileId = TEST_PROFILE,
                bookId = LibraryItemId(id),
                position = 10.minutes,
                duration = 60.minutes,
                isFinished = finished,
                updatedAt = Instant.parse(at),
                hasUnsyncedChanges = false,
            ),
        )
    }

    private class RecordingRememberedBooks(
        private val values: Map<ProfileId, LibraryItemId?>,
    ) : RememberedBookRepository {
        val requestedFor = mutableListOf<ProfileId>()

        override fun observe(profileId: ProfileId): Flow<LibraryItemId?> {
            requestedFor += profileId
            return flowOf(values[profileId])
        }

        override suspend fun remember(profileId: ProfileId, bookId: LibraryItemId): AppResult<Unit> =
            AppResult.Success(Unit)
    }

    private class RecordingStartupPlayer : StartupPlayer {
        val armed = mutableListOf<LibraryItemId>()
        val played = mutableListOf<LibraryItemId>()

        override suspend fun arm(bookId: LibraryItemId) {
            armed += bookId
        }

        override suspend fun play(bookId: LibraryItemId) {
            played += bookId
        }
    }

    private companion object {
        val OTHER = ProfileId("profile-2")
    }
}
