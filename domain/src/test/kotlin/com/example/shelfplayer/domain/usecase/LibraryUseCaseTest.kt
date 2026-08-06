package com.example.shelfplayer.domain.usecase

import app.cash.turbine.test
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.domain.FakeAuthRepository
import com.example.shelfplayer.domain.FakeLibraryRepository
import com.example.shelfplayer.domain.FakeProfileRepository
import com.example.shelfplayer.domain.TEST_INSTANT
import com.example.shelfplayer.domain.TEST_LIBRARY
import com.example.shelfplayer.domain.TEST_PROFILE
import com.example.shelfplayer.domain.book
import com.example.shelfplayer.domain.library
import com.example.shelfplayer.domain.library.BookSortOrder
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryUseCaseTest {

    /**
     * The use cases sort off the main thread; this test runs everything on one.
     *
     * `UnconfinedTestDispatcher` keeps `flowOn` from adding a hop the assertions would have to wait for,
     * without pretending the production dispatcher is the main one.
     */
    private val testDispatcher = UnconfinedTestDispatcher()

    private val books = listOf(
        book(id = "b-10", title = "The Last Sounding", sequence = "10"),
        book(id = "b-1", title = "The Salt Harbour", sequence = "1", tags = listOf("favourites")),
        book(id = "b-2", title = "The Weather Glass", sequence = "2", narrators = listOf("Peter Nkemelu")),
    )

    @Test
    fun `libraries are empty while no profile is active`() = runTest {
        val profiles = FakeProfileRepository(active = null)
        val useCase = ObserveLibrariesUseCase(profiles, FakeLibraryRepository())

        useCase().test {
            assertEquals(emptyList(), awaitItem())
        }
    }

    @Test
    fun `libraries follow the active profile`() = runTest {
        val profiles = FakeProfileRepository()
        val useCase = ObserveLibrariesUseCase(
            profiles,
            FakeLibraryRepository(libraries = listOf(library(bookCount = 3))),
        )

        useCase().test {
            assertEquals(3, awaitItem().single().bookCount)
            profiles.signOut()
            assertEquals(emptyList(), awaitItem())
        }
    }

    /**
     * PRODUCT_SPEC LIB-002 — the shelf spans every library the profile can see.
     *
     * A book from a second library appears without the user having chosen a library first: that is the
     * whole point of the screen the app opens on.
     */
    @Test
    fun `the shelf spans libraries and opens on what was played last`() = runTest {
        val shelf = listOf(
            book(id = "fiction-cold", title = "Untouched"),
            book(id = "fiction-old", title = "Older", playedAt = TEST_INSTANT.minusSeconds(600)),
            book(
                id = "other-library",
                title = "From another library",
                libraryId = LibraryId("library-2"),
                playedAt = TEST_INSTANT,
            ),
        )
        val useCase = ObserveAccessibleBooksUseCase(
            FakeProfileRepository(),
            FakeLibraryRepository(books = shelf),
            testDispatcher,
        )

        useCase().test {
            assertEquals(
                listOf("other-library", "fiction-old", "fiction-cold"),
                awaitItem().map { it.id.value },
            )
        }
    }

    @Test
    fun `the shelf is empty while no profile is active`() = runTest {
        val useCase = ObserveAccessibleBooksUseCase(
            FakeProfileRepository(active = null),
            FakeLibraryRepository(books = books),
            testDispatcher,
        )

        useCase().test {
            assertEquals(emptyList(), awaitItem())
        }
    }

    /** The shelf and a single library share one search predicate, so they cannot answer differently. */
    @Test
    fun `the shelf searches the same fields a library does`() = runTest {
        val repository = FakeLibraryRepository(books = books)

        ObserveAccessibleBooksUseCase(FakeProfileRepository(), repository, testDispatcher)("Nkemelu").test {
            assertEquals(listOf("b-2"), awaitItem().map { it.id.value })
        }
        ObserveLibraryBooksUseCase(FakeProfileRepository(), repository, testDispatcher)(
            TEST_LIBRARY,
            query = "Nkemelu",
        ).test {
            assertEquals(listOf("b-2"), awaitItem().map { it.id.value })
        }
    }

    @Test
    fun `books are sorted by the requested order`() = runTest {
        val useCase = ObserveLibraryBooksUseCase(
            FakeProfileRepository(),
            FakeLibraryRepository(books = books),
            testDispatcher,
        )

        useCase(TEST_LIBRARY, order = BookSortOrder.SeriesSequenceAscending).test {
            assertEquals(listOf("b-1", "b-2", "b-10"), awaitItem().map { it.id.value })
        }
    }

    /** PRODUCT_SPEC LIB-002 — search matches title, author, narrator, series and tags. */
    @Test
    fun `search matches every documented field`() = runTest {
        val useCase = ObserveLibraryBooksUseCase(
            FakeProfileRepository(),
            FakeLibraryRepository(books = books),
            testDispatcher,
        )

        suspend fun matchesFor(query: String): List<String> {
            var result = emptyList<String>()
            useCase(TEST_LIBRARY, query = query).test {
                result = awaitItem().map { it.id.value }
            }
            return result
        }

        assertEquals(listOf("b-1"), matchesFor("salt harbour"))
        assertEquals(listOf("b-1"), matchesFor("favourites"))
        assertEquals(listOf("b-2"), matchesFor("Nkemelu"))
        assertEquals(listOf("b-10", "b-1", "b-2"), matchesFor("Marisol"))
        assertEquals(listOf("b-10", "b-1", "b-2"), matchesFor("Long Voyage"))
        assertEquals(emptyList(), matchesFor("nothing here"))
    }

    @Test
    fun `search is case-insensitive and ignores surrounding whitespace`() = runTest {
        val useCase = ObserveLibraryBooksUseCase(
            FakeProfileRepository(),
            FakeLibraryRepository(books = books),
            testDispatcher,
        )

        useCase(TEST_LIBRARY, query = "   WEATHER   ").test {
            assertEquals(listOf("b-2"), awaitItem().map { it.id.value })
        }
    }

    @Test
    fun `book details are null when the item is not cached`() = runTest {
        val useCase = ObserveBookDetailsUseCase(
            FakeProfileRepository(),
            FakeLibraryRepository(books = books),
        )

        useCase(LibraryItemId("missing")).test {
            assertNull(awaitItem())
        }
    }

    @Test
    fun `refresh delegates to the repository for the active profile`() = runTest {
        val libraries = FakeLibraryRepository().apply { refreshResult = AppResult.Success(7) }
        val useCase = refreshUseCase(libraries)

        val result = useCase()

        assertEquals(AppResult.Success(7), result)
        assertEquals(listOf(TEST_PROFILE), libraries.refreshedProfiles)
    }

    /** PRODUCT_SPEC 5.2 — a privileged operation always names its profile; it never guesses one. */
    @Test
    fun `refresh without an active profile fails instead of guessing`() = runTest {
        val libraries = FakeLibraryRepository()
        val auth = FakeAuthRepository()
        val useCase = RefreshLibraryUseCase(FakeProfileRepository(active = null), libraries, auth)

        val result = useCase()

        assertIs<AppResult.Failure>(result)
        assertIs<AppError.Authentication>(result.error)
        assertTrue(libraries.refreshedProfiles.isEmpty())
        assertTrue(auth.renewedProfiles.isEmpty(), "there is no session to renew without a profile")
    }

    // --- PRODUCT_SPEC AUTH-004 -------------------------------------------------------------------

    /** An expired session is renewed and the refresh retried, without the user seeing anything. */
    @Test
    fun `an expired session is renewed once and the refresh retried`() = runTest {
        val libraries = FakeLibraryRepository().apply {
            queueRefreshResults(AppResult.Failure(AppError.Authentication()), AppResult.Success(4))
        }
        val auth = FakeAuthRepository()

        val result = refreshUseCase(libraries, auth)()

        assertEquals(AppResult.Success(4), result)
        assertEquals(listOf(TEST_PROFILE), auth.renewedProfiles)
        assertEquals(2, libraries.refreshedProfiles.size)
    }

    /**
     * PRODUCT_SPEC AUTH-004 — "the app never loops login requests".
     *
     * A session that cannot be renewed is reported as the original authentication failure after exactly
     * one attempt. The profile has already been marked by the session layer, so the next step belongs to
     * the user, not to another retry.
     */
    @Test
    fun `a session that cannot be renewed is reported once, not retried`() = runTest {
        val libraries = FakeLibraryRepository().apply {
            refreshResult = AppResult.Failure(AppError.Authentication())
        }
        val auth = FakeAuthRepository().apply { willRenewInto(SessionStatus.ReauthenticationRequired) }

        val result = refreshUseCase(libraries, auth)()

        assertIs<AppResult.Failure>(result)
        assertIs<AppError.Authentication>(result.error)
        assertEquals(1, auth.renewedProfiles.size, "renewal must be attempted exactly once")
        assertEquals(1, libraries.refreshedProfiles.size, "the refresh must not be retried")
    }

    /** A renewal that could not even be attempted must not turn into a retry loop either. */
    @Test
    fun `a renewal that fails outright reports the original failure`() = runTest {
        val libraries = FakeLibraryRepository().apply {
            refreshResult = AppResult.Failure(AppError.Authentication())
        }
        val auth = FakeAuthRepository().apply {
            willFailToRenew(AppError.Validation(summary = "That profile is no longer saved."))
        }

        val result = refreshUseCase(libraries, auth)()

        assertIs<AppError.Authentication>(assertIs<AppResult.Failure>(result).error)
        assertEquals(1, libraries.refreshedProfiles.size)
    }

    /**
     * The retry is not itself retried: a `401` on the second attempt — a token revoked between the two
     * calls — is returned as it is rather than starting the cycle again.
     */
    @Test
    fun `a second authentication failure after a successful renewal is not renewed again`() = runTest {
        val libraries = FakeLibraryRepository().apply {
            refreshResult = AppResult.Failure(AppError.Authentication())
        }
        val auth = FakeAuthRepository()

        val result = refreshUseCase(libraries, auth)()

        assertIs<AppResult.Failure>(result)
        assertEquals(1, auth.renewedProfiles.size)
        assertEquals(2, libraries.refreshedProfiles.size)
    }

    /** Only a `401` triggers a renewal. A network failure is not a session problem. */
    @Test
    fun `a failure that is not an authentication failure never renews the session`() = runTest {
        val libraries = FakeLibraryRepository().apply { refreshResult = AppResult.Failure(AppError.Network()) }
        val auth = FakeAuthRepository()

        val result = refreshUseCase(libraries, auth)()

        assertIs<AppError.Network>(assertIs<AppResult.Failure>(result).error)
        assertTrue(auth.renewedProfiles.isEmpty())
        assertEquals(1, libraries.refreshedProfiles.size)
    }

    private fun refreshUseCase(libraries: FakeLibraryRepository, auth: FakeAuthRepository = FakeAuthRepository()) =
        RefreshLibraryUseCase(FakeProfileRepository(), libraries, auth)
}
