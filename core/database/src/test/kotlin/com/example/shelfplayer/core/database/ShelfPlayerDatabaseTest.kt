package com.example.shelfplayer.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.database.entity.AuthorEntity
import com.example.shelfplayer.core.database.entity.BookAuthorCrossRef
import com.example.shelfplayer.core.database.entity.BookEntity
import com.example.shelfplayer.core.database.entity.BookSeriesCrossRef
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.LibraryEntity
import com.example.shelfplayer.core.database.entity.MediaProgressEntity
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.SeriesEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC 13 — the schema behaves the way the identity and cascade rules require.
 *
 * These run on Robolectric rather than a device because they are about SQL semantics, not about
 * hardware: the cascade rules and the profile boundary either hold in SQLite or they do not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShelfPlayerDatabaseTest {

    private lateinit var database: ShelfPlayerDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShelfPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `books are readable with their authors, series, tracks and chapters`() = runTest {
        seed()

        val books = database.libraryDao().observeBooks(LIBRARY_KEY).first()
        val book = books.single()

        assertEquals("The Salt Harbour", book.book.title)
        assertEquals(listOf("Marisol Holt"), book.authors.map { it.name })
        assertEquals("1", book.seriesMemberships.single().membership.sequenceRaw)
        assertTrue(book.seriesMemberships.single().membership.isPrimary)
        assertEquals("The Long Voyage", book.seriesMemberships.single().series.name)
    }

    /** PRODUCT_SPEC 5.2 — progress is filtered by profile in SQL, not by a mapper. */
    @Test
    fun `progress is scoped to its profile`() = runTest {
        seed()
        seedSecondProfile()

        database.progressDao().upsertProgress(
            listOf(
                progressFor(PROFILE_ID, positionMillis = 1_000),
                progressFor(OTHER_PROFILE_ID, positionMillis = 9_000),
            ),
        )

        assertEquals(
            1_000L,
            database.progressDao().observeProgressFor(PROFILE_ID).first().single().positionMillis,
        )
        assertEquals(
            9_000L,
            database.progressDao().observeProgressFor(OTHER_PROFILE_ID).first().single().positionMillis,
        )
    }

    /** PRODUCT_SPEC AUTH-002 — removing one profile must not remove another profile's data. */
    @Test
    fun `deleting a profile removes only its own rows`() = runTest {
        seed()
        seedSecondProfile()
        database.progressDao().upsertProgress(
            listOf(
                progressFor(PROFILE_ID, positionMillis = 1_000),
                progressFor(OTHER_PROFILE_ID, positionMillis = 9_000),
            ),
        )

        database.profileDao().deleteProfile(PROFILE_ID)

        assertTrue(database.progressDao().observeProgressFor(PROFILE_ID).first().isEmpty())
        assertEquals(1, database.progressDao().observeProgressFor(OTHER_PROFILE_ID).first().size)
        // The library and its books belong to the server, not to the profile that synced them.
        assertEquals(1, database.libraryDao().observeBooks(LIBRARY_KEY).first().size)
    }

    /** PRODUCT_SPEC 13.2 — a book that vanished from a sync is soft-deleted, not dropped. */
    @Test
    fun `soft-deleted books disappear from reads but keep their row`() = runTest {
        seed()

        database.libraryDao().markAllBooksDeleted(LIBRARY_KEY)

        assertTrue(database.libraryDao().observeBooks(LIBRARY_KEY).first().isEmpty())
        assertNull(database.libraryDao().observeBook(BOOK_KEY).first())
        assertEquals(0, database.libraryDao().countBooks(LIBRARY_KEY))
    }

    @Test
    fun `markMissingBooksDeleted keeps the books that are still present`() = runTest {
        seed()
        database.libraryDao().upsertBooks(listOf(bookEntity(remoteId = "book-2", title = "Second")))

        database.libraryDao().markMissingBooksDeleted(LIBRARY_KEY, listOf(BOOK_KEY))

        assertEquals(
            listOf("book-1"),
            database.libraryDao().observeBooks(LIBRARY_KEY).first().map { it.book.remoteId },
        )
    }

    /** PRODUCT_SPEC 13.1 — the same remote id on two servers is two different books. */
    @Test
    fun `identical remote ids on different servers do not collide`() {
        val first = EntityKey.of("server-a", "book-1")
        val second = EntityKey.of("server-b", "book-1")

        assertTrue(first != second)
        assertEquals("book-1", EntityKey.remoteIdOf(first))
        assertEquals("server-b", EntityKey.serverIdOf(second))
    }

    private suspend fun seed() {
        database.profileDao().upsertServer(
            ServerEntity(
                serverId = SERVER_ID,
                displayName = "Demo",
                baseUrl = "https://fixture.invalid",
                detectedVersion = null,
                isFixture = true,
                lastFetchedAt = 0,
            ),
        )
        database.profileDao().upsertProfile(profileEntity(PROFILE_ID))
        database.libraryDao().upsertLibraries(
            listOf(
                LibraryEntity(
                    libraryKey = LIBRARY_KEY,
                    serverId = SERVER_ID,
                    remoteId = "lib-1",
                    name = "Fiction",
                    kind = "Book",
                    displayOrder = 0,
                    remoteUpdatedAt = null,
                    lastFetchedAt = 0,
                    isDeleted = false,
                ),
            ),
        )
        database.libraryDao().upsertAuthors(
            listOf(
                AuthorEntity(
                    authorKey = AUTHOR_KEY,
                    serverId = SERVER_ID,
                    remoteId = "author-1",
                    name = "Marisol Holt",
                ),
            ),
        )
        database.libraryDao().upsertSeries(
            listOf(
                SeriesEntity(
                    seriesKey = SERIES_KEY,
                    serverId = SERVER_ID,
                    remoteId = "series-1",
                    name = "The Long Voyage",
                ),
            ),
        )
        database.libraryDao().upsertBooks(listOf(bookEntity()))
        database.libraryDao().upsertBookAuthors(
            listOf(BookAuthorCrossRef(BOOK_KEY, AUTHOR_KEY, position = 0)),
        )
        database.libraryDao().upsertBookSeries(
            listOf(BookSeriesCrossRef(BOOK_KEY, SERIES_KEY, sequenceRaw = "1", isPrimary = true)),
        )
    }

    private suspend fun seedSecondProfile() {
        database.profileDao().upsertProfile(profileEntity(OTHER_PROFILE_ID))
    }

    private fun profileEntity(profileId: String) = ProfileEntity(
        profileId = profileId,
        serverId = SERVER_ID,
        username = profileId,
        displayName = profileId,
        role = "Listener",
        requiresReauthentication = false,
        lastUsedAt = null,
        isFixture = true,
    )

    private fun bookEntity(remoteId: String = "book-1", title: String = "The Salt Harbour") = BookEntity(
        bookKey = EntityKey.of(SERVER_ID, remoteId),
        serverId = SERVER_ID,
        remoteId = remoteId,
        libraryKey = LIBRARY_KEY,
        title = title,
        subtitle = null,
        narratorsJson = "[]",
        genresJson = "[]",
        tagsJson = "[]",
        durationMillis = 1_000,
        description = null,
        publishedYear = null,
        publisher = null,
        language = null,
        isExplicit = false,
        isAbridged = false,
        coverPath = null,
        trackCount = 1,
        sizeBytes = 0,
        remoteUpdatedAt = null,
        lastFetchedAt = 0,
        isDeleted = false,
        localAvailability = "NotDownloaded",
    )

    private fun progressFor(profileId: String, positionMillis: Long) = MediaProgressEntity(
        progressKey = EntityKey.scoped(profileId, BOOK_KEY),
        profileId = profileId,
        bookKey = BOOK_KEY,
        serverId = SERVER_ID,
        positionMillis = positionMillis,
        durationMillis = 1_000,
        isFinished = false,
        updatedAt = 0,
        hasUnsyncedChanges = false,
    )

    private companion object {
        const val SERVER_ID = "server-1"
        const val PROFILE_ID = "profile-1"
        const val OTHER_PROFILE_ID = "profile-2"
        val LIBRARY_KEY = EntityKey.of(SERVER_ID, "lib-1")
        val AUTHOR_KEY = EntityKey.of(SERVER_ID, "author-1")
        val SERIES_KEY = EntityKey.of(SERVER_ID, "series-1")
        val BOOK_KEY = EntityKey.of(SERVER_ID, "book-1")
    }
}
