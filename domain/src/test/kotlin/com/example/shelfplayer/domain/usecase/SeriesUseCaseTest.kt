package com.example.shelfplayer.domain.usecase

import app.cash.turbine.test
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.domain.FakeLibraryRepository
import com.example.shelfplayer.domain.FakeProfileRepository
import com.example.shelfplayer.domain.TEST_LIBRARY
import com.example.shelfplayer.domain.book
import com.example.shelfplayer.domain.membership
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PRODUCT_SPEC LIB-003 / TC-16 — browsing by series, and opening one. */
@OptIn(ExperimentalCoroutinesApi::class)
class SeriesUseCaseTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val voyage = listOf(
        book(id = "b-10", title = "The Last Sounding", sequence = "10"),
        book(id = "b-1", title = "The Salt Harbour", sequence = "1"),
        book(id = "b-2", title = "The Weather Glass", sequence = "2"),
    )

    private val standalone = book(id = "b-solo", title = "A Quiet Tide")

    @Test
    fun `a library's books are grouped into series in sequence order`() = runTest {
        val useCase = ObserveSeriesShelvesUseCase(
            FakeProfileRepository(),
            FakeLibraryRepository(books = voyage + standalone),
            testDispatcher,
        )

        useCase(libraryId = TEST_LIBRARY).test {
            val shelf = awaitItem().single()
            assertEquals("The Long Voyage", shelf.series.name)
            assertEquals(
                listOf("The Salt Harbour", "The Weather Glass", "The Last Sounding"),
                shelf.books.map { it.title },
            )
        }
    }

    /** PRODUCT_SPEC 5.2 — nothing is grouped for nobody. A signed-out app shows no series. */
    @Test
    fun `series are empty while no profile is active`() = runTest {
        val profiles = FakeProfileRepository().apply { signOut() }
        val useCase = ObserveSeriesShelvesUseCase(profiles, FakeLibraryRepository(books = voyage), testDispatcher)

        useCase(libraryId = TEST_LIBRARY).test { assertTrue(awaitItem().isEmpty()) }
    }

    /** The series axis is scoped to the library being browsed, exactly as the book axis is. */
    @Test
    fun `a series in another library is not listed`() = runTest {
        val useCase = ObserveSeriesShelvesUseCase(
            FakeProfileRepository(),
            FakeLibraryRepository(books = voyage),
            testDispatcher,
        )

        useCase(libraryId = LibraryId("library-2")).test { assertTrue(awaitItem().isEmpty()) }
    }

    @Test
    fun `searching the series axis matches a series by name`() = runTest {
        val useCase = ObserveSeriesShelvesUseCase(
            FakeProfileRepository(),
            FakeLibraryRepository(books = voyage),
            testDispatcher,
        )

        useCase(libraryId = TEST_LIBRARY, query = "voyage").test { assertEquals(1, awaitItem().size) }
        useCase(libraryId = TEST_LIBRARY, query = "no such series").test { assertTrue(awaitItem().isEmpty()) }
    }

    @Test
    fun `opening a series returns its books in order`() = runTest {
        val useCase = ObserveSeriesUseCase(
            FakeProfileRepository(),
            FakeLibraryRepository(books = voyage + standalone),
            testDispatcher,
        )

        useCase(SeriesId("series-1")).test {
            val shelf = requireNotNull(awaitItem())
            assertEquals(
                listOf("The Salt Harbour", "The Weather Glass", "The Last Sounding"),
                shelf.books.map { it.title },
            )
        }
    }

    /**
     * PRODUCT_SPEC 5.2 — a series whose books this profile can no longer see resolves to nothing.
     *
     * `observeAccessibleBooks` is already filtered by the grant, so a revoked library empties the
     * series with it rather than leaving a titled screen with no books under it.
     */
    @Test
    fun `a series with no visible books resolves to null`() = runTest {
        val useCase = ObserveSeriesUseCase(
            FakeProfileRepository(),
            FakeLibraryRepository(books = listOf(standalone)),
            testDispatcher,
        )

        useCase(SeriesId("series-1")).test { assertNull(awaitItem()) }
    }

    /** PRODUCT_SPEC LIB-003 — the position shown is the one belonging to the series being opened. */
    @Test
    fun `opening one of a book's two series uses that series' own sequence`() = runTest {
        val omnibus = book(
            id = "omnibus",
            title = "Omnibus",
            memberships = listOf(
                membership(seriesId = "voyage", name = "The Long Voyage", sequence = "3"),
                membership(seriesId = "collected", name = "Collected Editions", sequence = "1", isPrimary = false),
            ),
        )
        val useCase = ObserveSeriesUseCase(
            FakeProfileRepository(),
            FakeLibraryRepository(books = listOf(omnibus)),
            testDispatcher,
        )

        useCase(SeriesId("collected")).test {
            val shelf = requireNotNull(awaitItem())
            val inThisSeries = shelf.books.single().seriesMemberships.single { it.series.id == shelf.series.id }
            assertEquals("1", inThisSeries.sequence.raw)
        }
    }
}
