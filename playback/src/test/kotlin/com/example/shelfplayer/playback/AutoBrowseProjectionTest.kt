package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.SeriesSequence
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.core.model.library.MediaProgress
import com.example.shelfplayer.core.model.library.Series
import com.example.shelfplayer.core.model.library.SeriesMembership
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class AutoBrowseProjectionTest {

    @Test
    fun `played Series lead by newest activity then ties alphabetically`() {
        val books = listOf(
            book("a", "A book", series = "Alpha", activity = "2026-09-10T10:00:00Z"),
            book("z", "Z book", series = "Zulu", activity = "2026-09-12T10:00:00Z"),
            book("b", "B book", series = "Beta", activity = "2026-09-12T10:00:00Z"),
            book("g", "G book", series = "Gamma"),
        )

        assertEquals(
            listOf("Beta", "Zulu", "Alpha", "Gamma"),
            autoSeriesNodes(books).map { node -> node.membership.series.name },
        )
    }

    @Test
    fun `unplayed Series are alphabetical and deterministic`() {
        val books = listOf(
            book("2", "Second", series = "zeta"),
            book("1", "First", series = "Alpha"),
            book("3", "Third", series = "alpha"),
        )

        assertEquals(
            listOf("Alpha", "alpha", "zeta"),
            autoSeriesNodes(books).map { node -> node.membership.series.name },
        )
    }

    @Test
    fun `multiple books combine to the most recent Series activity including unsynced progress`() {
        val older = book("1", "One", series = "Saga", sequence = "1", activity = "2026-09-01T10:00:00Z")
        val newer = book(
            "2",
            "Two",
            series = "Saga",
            sequence = "2",
            activity = "2026-09-19T10:00:00Z",
            unsynced = true,
        )

        assertEquals(
            Instant.parse("2026-09-19T10:00:00Z"),
            autoSeriesNodes(listOf(older, newer)).single().lastActivity,
        )
    }

    @Test
    fun `books inside Series retain LIB-003 numeric sequence order`() {
        val books = listOf(
            book("ten", "Ten", series = "Saga", sequence = "10"),
            book("two", "Two", series = "Saga", sequence = "2"),
            book("pre", "Prequel", series = "Saga", sequence = "Prequel"),
        )

        assertEquals(
            listOf("two", "ten", "pre"),
            autoSeriesNodes(books).single().books.map { it.id.value },
        )
    }

    @Test
    fun `representative Series cover follows canonical order and missing art stays absent`() {
        val noCover = book("one", "One", series = "Saga", sequence = "1")
        val withCover = book("two", "Two", series = "Saga", sequence = "2", cover = true)
        val node = autoSeriesNodes(listOf(withCover, noCover)).single()

        assertEquals("two", node.representativeCover?.id?.value)
        assertNull(autoSeriesNodes(listOf(noCover)).single().representativeCover)
    }

    @Test
    fun `Authors are deterministic and representative cover is title ordered`() {
        val author = Author(SERVER, AuthorId("author"), "Ada")
        val zulu = book("z", "Zulu", author = author, cover = true)
        val alpha = book("a", "Alpha", author = author, cover = true)
        val node = autoAuthorNodes(listOf(zulu, alpha)).single()

        assertEquals(listOf("a", "z"), node.books.map { it.id.value })
        assertEquals("a", node.representativeCover?.id?.value)
    }

    private fun book(
        id: String,
        title: String,
        series: String? = null,
        sequence: String? = null,
        activity: String? = null,
        unsynced: Boolean = false,
        cover: Boolean = false,
        author: Author? = null,
    ): Book {
        val itemId = LibraryItemId(id)
        return Book(
            serverId = SERVER,
            id = itemId,
            libraryId = LIBRARY,
            title = title,
            subtitle = null,
            authors = listOfNotNull(author),
            narrators = emptyList(),
            seriesMemberships = series?.let { name ->
                listOf(
                    SeriesMembership(
                        series = Series(SERVER, SeriesId(name.lowercase()), name),
                        sequence = SeriesSequence.parse(sequence),
                        isPrimary = true,
                    ),
                )
            }.orEmpty(),
            duration = 10.hours,
            description = null,
            genres = emptyList(),
            tags = emptyList(),
            publishedYear = null,
            publisher = null,
            language = null,
            isbn = null,
            asin = null,
            isExplicit = false,
            isAbridged = false,
            coverPath = if (cover) "/private/cover.jpg" else null,
            trackCount = 1,
            sizeBytes = 1L,
            remoteUpdatedAt = null,
            addedAt = null,
            lastFetchedAt = Instant.parse("2026-09-20T00:00:00Z"),
            progress = activity?.let { at ->
                MediaProgress(
                    serverId = SERVER,
                    profileId = PROFILE,
                    bookId = itemId,
                    position = 30.minutes,
                    duration = 10.hours,
                    isFinished = false,
                    updatedAt = Instant.parse(at),
                    hasUnsyncedChanges = unsynced,
                )
            },
            localAvailability = LocalAvailability.NotDownloaded,
        )
    }

    private companion object {
        val SERVER = ServerId("server")
        val LIBRARY = LibraryId("library")
        val PROFILE = ProfileId("profile")
    }
}
