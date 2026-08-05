package com.example.shelfplayer.domain.usecase

import app.cash.turbine.test
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.domain.FakeLibraryRepository
import com.example.shelfplayer.domain.FakeProfileRepository
import com.example.shelfplayer.domain.TEST_LIBRARY
import com.example.shelfplayer.domain.TEST_PROFILE
import com.example.shelfplayer.domain.book
import com.example.shelfplayer.domain.library
import com.example.shelfplayer.domain.library.BookSortOrder
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryUseCaseTest {

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

    @Test
    fun `books are sorted by the requested order`() = runTest {
        val useCase = ObserveLibraryBooksUseCase(
            FakeProfileRepository(),
            FakeLibraryRepository(books = books),
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
        val useCase = RefreshLibraryUseCase(FakeProfileRepository(), libraries)

        val result = useCase()

        assertEquals(AppResult.Success(7), result)
        assertEquals(listOf(TEST_PROFILE), libraries.refreshedProfiles)
    }

    /** PRODUCT_SPEC 5.2 — a privileged operation always names its profile; it never guesses one. */
    @Test
    fun `refresh without an active profile fails instead of guessing`() = runTest {
        val libraries = FakeLibraryRepository()
        val useCase = RefreshLibraryUseCase(FakeProfileRepository(active = null), libraries)

        val result = useCase()

        assertIs<AppResult.Failure>(result)
        assertIs<AppError.Authentication>(result.error)
        assertTrue(libraries.refreshedProfiles.isEmpty())
    }
}
