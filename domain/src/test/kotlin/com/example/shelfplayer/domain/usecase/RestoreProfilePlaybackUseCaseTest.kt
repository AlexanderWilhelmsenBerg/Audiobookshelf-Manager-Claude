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
import com.example.shelfplayer.domain.FakeProfileRepository
import com.example.shelfplayer.domain.TEST_PROFILE
import com.example.shelfplayer.domain.TEST_SERVER
import com.example.shelfplayer.domain.book
import com.example.shelfplayer.domain.playback.StartupPlayer
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.repository.RememberedBookRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
    private val profiles = GenerationProfiles()

    @Test
    fun `remote progress recency does not replace the locally remembered book`() = runTest {
        val library = FakeLibraryRepository(
            listOf(
                playedBook("local-a", at = "2026-08-01T10:00:00Z"),
                playedBook("remote-b", at = "2026-09-12T10:00:00Z"),
            ),
        )

        useCase(library, rememberedId = LibraryItemId("local-a"))(TEST_PROFILE)

        assertEquals(listOf(LibraryItemId("local-a")), player.armed)
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

        useCase(library, rememberedId = LibraryItemId("resume"))(TEST_PROFILE)

        assertTrue(player.played.isEmpty(), "a switch must never start audio")
    }

    /** A finished book has nothing left to resume, so it is not offered. */
    @Test
    fun `a finished book is not restored`() = runTest {
        val library = FakeLibraryRepository(listOf(playedBook("done", at = "2026-08-20T10:00:00Z", finished = true)))

        useCase(library, rememberedId = LibraryItemId("done"))(TEST_PROFILE)

        assertTrue(player.armed.isEmpty())
    }

    /** A remembered identity survives even when the cached progress projection is temporarily absent. */
    @Test
    fun `a remembered book with no progress is restored paused`() = runTest {
        val library = FakeLibraryRepository(listOf(book("untouched")))

        useCase(library, rememberedId = LibraryItemId("untouched"))(TEST_PROFILE)

        assertEquals(listOf(LibraryItemId("untouched")), player.armed)
        assertTrue(player.played.isEmpty(), "restoring a profile must still remain paused")
    }

    /** Owner-approved: the incoming profile resumes by Android Auto's rule, so server progress is enough. */
    @Test
    fun `without a remembered book the newest unfinished Continue book is armed`() = runTest {
        val library = FakeLibraryRepository(
            listOf(
                playedBook("older", at = "2026-08-01T10:00:00Z"),
                playedBook("newest", at = "2026-09-12T10:00:00Z"),
                playedBook("newest-but-done", at = "2026-09-30T10:00:00Z", finished = true),
            ),
        )

        useCase(library)(TEST_PROFILE)

        assertEquals(listOf(LibraryItemId("newest")), player.armed)
        assertTrue(player.played.isEmpty(), "a fallback candidate is armed, never played")
    }

    @Test
    fun `a finished remembered book falls back to the newest unfinished Continue book`() = runTest {
        val library = FakeLibraryRepository(
            listOf(
                playedBook("done", at = "2026-09-30T10:00:00Z", finished = true),
                playedBook("other", at = "2026-08-01T10:00:00Z"),
            ),
        )

        useCase(library, rememberedId = LibraryItemId("done"))(TEST_PROFILE)

        assertEquals(listOf(LibraryItemId("other")), player.armed)
    }

    @Test
    fun `only finished or unplayed books means nothing is armed`() = runTest {
        val library = FakeLibraryRepository(
            listOf(playedBook("done", at = "2026-09-30T10:00:00Z", finished = true), book("untouched")),
        )

        useCase(library)(TEST_PROFILE)

        assertTrue(player.armed.isEmpty())
        assertTrue(player.played.isEmpty())
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

        useCase(library, rememberedId = LibraryItemId("theirs"))(OTHER)

        assertEquals(listOf(OTHER), library.accessibleBooksRequestedFor)
    }

    /**
     * AUTH-002: finishing B's delayed remembered-book read after switching to C must not arm B.
     * Deferred gates make the profile boundary deterministic rather than relying on a delay.
     */
    @Test
    fun `late remembered lookup cannot arm the departed profile`() = runTest {
        val (reached, release, remembered) = gatedRememberedBook(LibraryItemId("remembered"))
        val restore = async(start = CoroutineStart.UNDISPATCHED) {
            useCase(FakeLibraryRepository(listOf(book("remembered"))), rememberedBooks = remembered)(TEST_PROFILE)
        }
        reached.await()
        profiles.setActiveProfile(OTHER)
        release.complete(Unit)
        restore.await()

        assertTrue(player.armed.isEmpty(), "an outgoing lookup must never arm C's playback")
        assertTrue(player.played.isEmpty(), "profile restoration must not play")
    }

    /** AUTH-002: a conflated A→B→A active-profile identity is not proof the old generation is valid. */
    @Test
    fun `switching away and back rejects an older restoration generation`() = runTest {
        val (reached, release, remembered) = gatedRememberedBook(LibraryItemId("remembered"))
        val restore = async(start = CoroutineStart.UNDISPATCHED) {
            useCase(FakeLibraryRepository(listOf(book("remembered"))), rememberedBooks = remembered)(TEST_PROFILE)
        }
        reached.await()
        profiles.setActiveProfile(OTHER)
        profiles.setActiveProfile(TEST_PROFILE)
        release.complete(Unit)
        restore.await()

        assertTrue(player.armed.isEmpty(), "an earlier generation must not publish after A-B-A")
        assertTrue(player.played.isEmpty())
    }

    private fun gatedRememberedBook(
        id: LibraryItemId,
    ): Triple<CompletableDeferred<Unit>, CompletableDeferred<Unit>, RememberedBookRepository> {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val remembered = object : RememberedBookRepository by FakeRememberedBooks(id) {
            override suspend fun rememberedBook(profileId: ProfileId): LibraryItemId? {
                reached.complete(Unit)
                release.await()
                return id
            }
        }
        return Triple(reached, release, remembered)
    }

    private fun useCase(
        library: FakeLibraryRepository,
        rememberedId: LibraryItemId? = null,
        rememberedBooks: RememberedBookRepository = FakeRememberedBooks(rememberedId),
    ) =
        RestoreProfilePlaybackUseCase(
            library = library,
            rememberedBooks = rememberedBooks,
            profileRepository = profiles,
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

    private class FakeRememberedBooks(private val rememberedId: LibraryItemId?) : RememberedBookRepository {
        override suspend fun rememberedBook(profileId: ProfileId): LibraryItemId? = rememberedId

        override fun observeRememberedBook(profileId: ProfileId): Flow<LibraryItemId?> = flowOf(rememberedId)

        override suspend fun remember(profileId: ProfileId, bookId: LibraryItemId): AppResult<Unit> =
            AppResult.Success(Unit)

        override suspend fun forget(profileId: ProfileId): AppResult<Unit> = AppResult.Success(Unit)
    }

    private class GenerationProfiles : ProfileRepository {
        private val delegate = FakeProfileRepository()
        private var generation = 0L

        override fun observeProfiles() = delegate.observeProfiles()

        override fun observeServers() = delegate.observeServers()

        override fun observeActiveProfile() = delegate.observeActiveProfile()

        override suspend fun activeProfileId(): ProfileId? = delegate.activeProfileId()

        override fun activeProfileGeneration(): Long = generation

        override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> {
            val old = delegate.activeProfileId()
            return delegate.setActiveProfile(profileId).also { if (old != profileId) generation++ }
        }
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
