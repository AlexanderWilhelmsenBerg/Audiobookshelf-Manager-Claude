package com.example.shelfplayer.domain.library

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
import java.time.Instant
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * ADR-0025 — a seeded library of any size, for the scale question 17.3 asks badly.
 *
 * ### Why a generator rather than a committed fixture
 *
 * ADR-0025 makes a 2,000-item library a prerequisite for measuring what happens to this app at scale, and
 * the committed demo fixture holds seven books. The alternative to generating one is a two-thousand-entry
 * JSON file whose diff nobody will ever read, which is a fixture in the sense that it sits in the
 * repository and in no other sense.
 *
 * The seed is fixed by default, so a failure is reproducible and the same run produces the same library on
 * every machine. Nothing here is random in the sense that matters.
 *
 * ### The distribution is chosen to make grouping do work
 *
 * A thousand books by one author would exercise nothing: `groupBooks` would build one group and return.
 * The shape below is deliberately awkward for the grouping code — many authors with few books each, a long
 * tail of one-book series alongside a few long ones, genres shared widely so their groups are large, and
 * a third of the library carrying progress so the shelves have something to select from.
 *
 * ### Scope
 *
 * This produces `List<Book>`, which is what every function in `:domain`'s library package consumes. It is
 * **not** what a macrobenchmark would need — that wants a seeded Room database or a fake server behind the
 * real UI, which is a different artefact and is still to build. Written here because here is where it has
 * a caller today.
 */
internal object LargeLibrary {

    const val DEFAULT_SEED = 20260821

    /** How many distinct authors, series and genres [books] spreads a library across. */
    private const val AUTHORS = 240
    private const val SERIES = 180
    private const val GENRES = 22

    fun books(count: Int, seed: Int = DEFAULT_SEED): List<Book> {
        val random = Random(seed)
        return (0 until count).map { index -> book(index, random) }
    }

    private fun book(index: Int, random: Random): Book {
        val authorIndex = random.nextInt(AUTHORS)
        val hasSeries = random.nextInt(100) < SERIES_SHARE
        val seriesIndex = random.nextInt(SERIES)
        val progressState = random.nextInt(100)

        return Book(
            serverId = SERVER,
            id = LibraryItemId("book-$index"),
            libraryId = LIBRARY,
            // Padded so lexical and numeric order agree; a sort test comparing "book-10" with "book-9"
            // would otherwise be asserting the wrong thing about `sortBooks`.
            title = "Title ${index.toString().padStart(4, '0')}",
            subtitle = null,
            authors = listOf(Author(SERVER, AuthorId("author-$authorIndex"), "Author $authorIndex")),
            narrators = listOf("Narrator ${random.nextInt(NARRATORS)}"),
            seriesMemberships = if (hasSeries) {
                listOf(
                    SeriesMembership(
                        series = Series(SERVER, SeriesId("series-$seriesIndex"), "Series $seriesIndex"),
                        sequence = SeriesSequence.parse((random.nextInt(MAX_SEQUENCE) + 1).toString()),
                        isPrimary = true,
                    ),
                )
            } else {
                emptyList()
            },
            duration = (random.nextInt(MAX_HOURS) + 1).hours,
            description = null,
            genres = listOf("Genre ${random.nextInt(GENRES)}"),
            tags = emptyList(),
            publishedYear = FIRST_YEAR + random.nextInt(YEAR_SPAN),
            publisher = null,
            language = null,
            isbn = null,
            asin = null,
            isExplicit = false,
            isAbridged = false,
            coverPath = null,
            trackCount = random.nextInt(MAX_TRACKS) + 1,
            sizeBytes = random.nextLong(MAX_SIZE_BYTES),
            remoteUpdatedAt = EPOCH.plusSeconds(random.nextLong(YEAR_SECONDS)),
            addedAt = EPOCH.plusSeconds(random.nextLong(YEAR_SECONDS)),
            lastFetchedAt = EPOCH,
            // A third in progress, a fifth finished, the rest untouched — so "continue listening",
            // "listen again" and "discover" each have candidates and none of them has all of them.
            progress = when {
                progressState < IN_PROGRESS_SHARE -> progress(index, finished = false, random = random)
                progressState < IN_PROGRESS_SHARE + FINISHED_SHARE ->
                    progress(index, finished = true, random = random)

                else -> null
            },
            localAvailability = LocalAvailability.NotDownloaded,
        )
    }

    private fun progress(index: Int, finished: Boolean, random: Random) = MediaProgress(
        serverId = SERVER,
        profileId = PROFILE,
        bookId = LibraryItemId("book-$index"),
        position = if (finished) TOTAL else (random.nextInt(MAX_POSITION_MINUTES) + 1).minutes,
        duration = TOTAL,
        isFinished = finished,
        updatedAt = EPOCH.plusSeconds(random.nextLong(YEAR_SECONDS)),
        hasUnsyncedChanges = false,
    )

    private val SERVER = ServerId("srv-scale")
    private val LIBRARY = LibraryId("lib-scale")
    private val PROFILE = ProfileId("prf-scale")
    private val EPOCH: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val TOTAL = 600.minutes

    private const val SERIES_SHARE = 60
    private const val IN_PROGRESS_SHARE = 33
    private const val FINISHED_SHARE = 20
    private const val NARRATORS = 90
    private const val MAX_SEQUENCE = 12
    private const val MAX_HOURS = 30
    private const val MAX_TRACKS = 40
    private const val MAX_SIZE_BYTES = 900_000_000L
    private const val MAX_POSITION_MINUTES = 590
    private const val FIRST_YEAR = 1950
    private const val YEAR_SPAN = 76
    private const val YEAR_SECONDS = 31_536_000L
}
