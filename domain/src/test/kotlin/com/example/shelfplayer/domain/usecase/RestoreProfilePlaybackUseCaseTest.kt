package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
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
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * PRODUCT_SPEC 6.5 step 6 — *"The new profile's last player state is restored paused."*
 *
 * ### What these are actually protecting
 *
 * Two clauses that pull against the obvious implementation. 6.5.3 says playback pauses by default and 6.5.8
 * says continuing across a switch is *not supported in version 1*, so the restore must **arm and never
 * play** — which is why `ApplyStartupModeUseCase` could not be reused despite doing a similar thing on a
 * cold start: it honours `StartupMode`, and `ResumeOnOpen` would start audio here.
 *
 * And the book has to be the *incoming* profile's. The rule used to live in `AutoLibrary.lastPlayed()`,
 * which resolved the active profile itself and so could not answer this question at all.
 */
class RestoreProfilePlaybackUseCaseTest {

    private val player = RecordingStartupPlayer()

    @Test
    fun `the incoming profile's most recently played unfinished book is armed`() = runTest {
        val library = FakeLibraryRepository(
            listOf(
                playedBook("older", at = "2026-08-01T10:00:00Z"),
                playedBook("newest", at = "2026-08-20T10:00:00Z"),
                playedBook("middle", at = "2026-08-10T10:00:00Z"),
            ),
        )

        useCase(library)(TEST_PROFILE)

        assertEquals(listOf(LibraryItemId("newest")), player.armed)
    }

    /**
     * **Armed, never played.** 6.5.3 and 6.5.8 both forbid audio starting from a switch.
     *
     * The assertion that `played` is empty is the one with teeth: `StartupPlayer` offers both verbs, and the
     * wrong one is one character away.
     */
    @Test
    fun `nothing is ever played`() = runTest {
        val library = FakeLibraryRepository(listOf(playedBook("resume", at = "2026-08-20T10:00:00Z")))

        useCase(library)(TEST_PROFILE)

        assertTrue(player.played.isEmpty(), "a switch must never start audio")
    }

    /** A finished book has nothing left to resume, so it is not offered. */
    @Test
    fun `a finished book is not restored`() = runTest {
        val library = FakeLibraryRepository(listOf(playedBook("done", at = "2026-08-20T10:00:00Z", finished = true)))

        useCase(library)(TEST_PROFILE)

        assertTrue(player.armed.isEmpty())
    }

    /** A book with no progress row was never playing, so there is nothing to come back to. */
    @Test
    fun `a book with no progress is not restored`() = runTest {
        val library = FakeLibraryRepository(listOf(book("untouched")))

        useCase(library)(TEST_PROFILE)

        assertTrue(player.armed.isEmpty())
    }

    /** An account with nothing played is silent rather than a failure — there is nothing wrong. */
    @Test
    fun `an account with an empty library restores nothing and does not fail`() = runTest {
        val library = FakeLibraryRepository(emptyList())

        useCase(library)(TEST_PROFILE)

        assertTrue(player.armed.isEmpty())
        assertTrue(player.played.isEmpty())
    }

    /**
     * The library is read **for the profile named**, not for whoever is active.
     *
     * This runs immediately after a switch, so naming the profile is what makes it independent of whether
     * the selection has reached every reader yet — the same reasoning behind the explicit owner on the
     * progress and bookmark writes (R-49, R-50).
     */
    @Test
    fun `the library is read for the profile that was switched to`() = runTest {
        val library = FakeLibraryRepository(listOf(playedBook("theirs", at = "2026-08-20T10:00:00Z")))

        useCase(library)(OTHER)

        assertEquals(listOf(OTHER), library.accessibleBooksRequestedFor)
    }

    private fun useCase(library: FakeLibraryRepository) = RestoreProfilePlaybackUseCase(
        library = library,
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
