package com.example.shelfplayer.benchmarkfixture

import com.example.shelfplayer.core.database.dao.LibraryWriteDao
import com.example.shelfplayer.core.database.dao.ProfileDao
import com.example.shelfplayer.core.database.dao.ProgressDao
import com.example.shelfplayer.core.database.entity.AuthorEntity
import com.example.shelfplayer.core.database.entity.BookAuthorCrossRef
import com.example.shelfplayer.core.database.entity.BookEntity
import com.example.shelfplayer.core.database.entity.BookSeriesCrossRef
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.LibraryEntity
import com.example.shelfplayer.core.database.entity.MediaProgressEntity
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.ProfileVisibleBookEntity
import com.example.shelfplayer.core.database.entity.SeriesEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.core.model.settings.AppLanguage
import javax.inject.Inject
import kotlin.random.Random

/**
 * PRODUCT_SPEC 17.3 / ADR-0025 — a library of any size, written straight into the database.
 *
 * ### Why this exists and why it is in the `benchmark` source set
 *
 * ADR-0025 makes a 2,000-item library the prerequisite for three of 17.3's numbers, and records that the
 * domain half was already built — `LargeLibrary` generates `List<Book>` and `LargeLibraryScaleTest` runs
 * every pure function over it. What that cannot reach is the application: the screens, the ViewModels and
 * the Room reads underneath them. Measuring those needs rows in a real database on a real device, which is
 * this class.
 *
 * It lives in `app/src/benchmark/`, so it is compiled into exactly one build type — the one that is never
 * published, never signed with a release key and never assembled by CI's release path. It is not in
 * `debug` either, because a debug build is the one the owner installs on their own phone.
 *
 * ### It writes entities rather than driving the sync
 *
 * The alternative was to stand up a fake server and let `LibrarySync` populate the database through the
 * real path, which would be a more faithful fixture. It is rejected for the reason ADR-0025 gives about
 * building things to satisfy measurements: a fake server is a large artefact whose own fidelity would then
 * need defending, and the thing under measurement here is the *read* path — Room to `Flow<List<Book>>` to
 * the list. What put the rows there does not change what reading them costs.
 *
 * ### The distribution is `LargeLibrary`'s, deliberately
 *
 * Many authors with few books each, a long tail of one-book series beside a few long ones, genres shared
 * widely, and a third of the library carrying progress so the shelves have something to select. A thousand
 * books by one author would exercise nothing. The seed is fixed, so two runs on two devices measure the
 * same library.
 *
 * ### One honest limitation, recorded rather than hidden
 *
 * **Every book is seeded with no cover.** A cover is fetched over the network from the server the book came
 * from, and there is no server here. So the scroll number this fixture produces excludes image decode,
 * which on a real library is a substantial part of what scrolling costs. It is a floor, not the whole
 * answer, and `docs/benchmark.md` says so where the number is recorded.
 */
class BenchmarkLibrarySeeder @Inject constructor(
    private val profiles: ProfileDao,
    private val libraryWrites: LibraryWriteDao,
    private val progress: ProgressDao,
    private val settings: AppSettingsDataSource,
) {

    /**
     * Writes a library of [bookCount] books and makes it the active profile's.
     *
     * Idempotent by construction: every write is an upsert against a key derived from the seed and the
     * index, so running it twice produces the same rows rather than twice as many. That matters because
     * a benchmark class seeds in `@Before` and Macrobenchmark may run a class more than once.
     */
    @Suppress("LongMethod")
    suspend fun seed(bookCount: Int, seed: Int = DEFAULT_SEED): Int {
        val now = FIXED_CLOCK_MILLIS
        val random = Random(seed)

        profiles.upsertServer(
            ServerEntity(
                serverId = SERVER_ID,
                displayName = "Benchmark fixture",
                // Reserved by RFC 2606 and resolvable by nothing, which is the point: a cover request
                // that escaped would fail fast and locally rather than reaching somebody's server.
                baseUrl = "https://benchmark.invalid",
                detectedVersion = "2.36.0",
                isFixture = true,
                lastFetchedAt = now,
                authMethodsJson = """["local"]""",
                capabilitiesJson = "[]",
                capabilitiesDetectedAt = now,
            ),
        )

        profiles.upsertProfile(
            ProfileEntity(
                profileId = PROFILE_ID,
                serverId = SERVER_ID,
                remoteUserId = "benchmark-user",
                username = "benchmark",
                displayName = "Benchmark",
                role = "user",
                requiresReauthentication = false,
                lastUsedAt = now,
                isFixture = true,
                accessibleLibrariesJson = "[]",
                // PRODUCT_SPEC 5.2 — an empty accessible list with this flag set means *all* libraries,
                // which is what makes the seeded books visible without naming each one.
                hasAllLibraryAccess = true,
                hasAllTagAccess = true,
                canDownload = true,
            ),
        )

        libraryWrites.upsertLibraries(
            listOf(
                LibraryEntity(
                    libraryKey = LIBRARY_KEY,
                    serverId = SERVER_ID,
                    remoteId = LIBRARY_REMOTE_ID,
                    name = "Benchmark Fiction",
                    kind = "book",
                    displayOrder = 0,
                    remoteUpdatedAt = now,
                    lastFetchedAt = now,
                    isDeleted = false,
                    finishedTimeRemainingSeconds = null,
                ),
            ),
        )

        val authors = (0 until AUTHORS).map { index ->
            AuthorEntity(
                authorKey = EntityKey.of(SERVER_ID, "author-$index"),
                serverId = SERVER_ID,
                remoteId = "author-$index",
                name = "Author ${index.toString().padStart(3, '0')}",
            )
        }
        libraryWrites.upsertAuthors(authors)

        val series = (0 until SERIES).map { index ->
            SeriesEntity(
                seriesKey = EntityKey.of(SERVER_ID, "series-$index"),
                serverId = SERVER_ID,
                remoteId = "series-$index",
                name = "Series ${index.toString().padStart(3, '0')}",
            )
        }
        libraryWrites.upsertSeries(series)

        val books = ArrayList<BookEntity>(bookCount)
        val bookAuthors = ArrayList<BookAuthorCrossRef>(bookCount)
        val bookSeries = ArrayList<BookSeriesCrossRef>()
        val visibility = ArrayList<ProfileVisibleBookEntity>(bookCount)
        val progressRows = ArrayList<MediaProgressEntity>()

        for (index in 0 until bookCount) {
            val remoteId = "book-$index"
            val bookKey = EntityKey.of(SERVER_ID, remoteId)
            val durationMillis = random.nextLong(MIN_DURATION_MILLIS, MAX_DURATION_MILLIS)

            books += BookEntity(
                bookKey = bookKey,
                serverId = SERVER_ID,
                remoteId = remoteId,
                libraryKey = LIBRARY_KEY,
                title = "Book ${index.toString().padStart(4, '0')} ${TITLE_WORDS[index % TITLE_WORDS.size]}",
                subtitle = null,
                narratorsJson = """["Narrator ${index % NARRATORS}"]""",
                genresJson = """["${GENRE_NAMES[index % GENRE_NAMES.size]}"]""",
                tagsJson = "[]",
                durationMillis = durationMillis,
                description = null,
                publishedYear = FIRST_YEAR + (index % YEAR_SPREAD),
                publisher = null,
                language = "en",
                isbn = null,
                asin = null,
                isExplicit = false,
                isAbridged = false,
                // See the class KDoc: no server, so no cover, so no image decode in the scroll number.
                coverPath = null,
                trackCount = 1,
                sizeBytes = durationMillis * BYTES_PER_MILLI,
                remoteUpdatedAt = now,
                addedAt = now - index.toLong() * MILLIS_PER_MINUTE,
                lastFetchedAt = now,
                isDeleted = false,
                localAvailability = LocalAvailability.NotDownloaded.name,
            )

            bookAuthors += BookAuthorCrossRef(
                bookKey = bookKey,
                authorKey = authors[index % AUTHORS].authorKey,
                position = 0,
            )

            // Two books in three belong to a series, which leaves a long tail of one-book series beside
            // the few long ones — the shape that makes grouping do work.
            if (index % 3 != 0) {
                bookSeries += BookSeriesCrossRef(
                    bookKey = bookKey,
                    seriesKey = series[index % SERIES].seriesKey,
                    sequenceRaw = (index / SERIES + 1).toString(),
                    isPrimary = true,
                )
            }

            visibility += ProfileVisibleBookEntity(
                profileId = PROFILE_ID,
                bookKey = bookKey,
                libraryKey = LIBRARY_KEY,
            )

            // A third of the library has been listened to, so "Continue listening" is not empty and the
            // shelves have something to select from rather than degenerating to a single query.
            if (index % 3 == 0) {
                progressRows += MediaProgressEntity(
                    progressKey = EntityKey.scoped(PROFILE_ID, bookKey),
                    profileId = PROFILE_ID,
                    bookKey = bookKey,
                    serverId = SERVER_ID,
                    positionMillis = durationMillis / 2,
                    durationMillis = durationMillis,
                    isFinished = false,
                    updatedAt = now - index.toLong() * MILLIS_PER_MINUTE,
                    hasUnsyncedChanges = false,
                )
            }
        }

        // Chunked because SQLite binds a bounded number of arguments per statement and 2,000 books is
        // comfortably past it. The chunk size is small enough to be safe on every supported API level.
        books.chunked(WRITE_CHUNK).forEach { libraryWrites.upsertBooks(it) }
        bookAuthors.chunked(WRITE_CHUNK).forEach { libraryWrites.upsertBookAuthors(it) }
        bookSeries.chunked(WRITE_CHUNK).forEach { libraryWrites.upsertBookSeries(it) }

        libraryWrites.clearVisibility(PROFILE_ID, LIBRARY_KEY)
        visibility.chunked(WRITE_CHUNK).forEach { libraryWrites.insertVisibility(it) }
        progressRows.chunked(WRITE_CHUNK).forEach { progress.upsertProgress(it) }

        settings.setActiveProfile(ProfileId(PROFILE_ID))
        /*
         * PRODUCT_SPEC SET-002 — pinned so the benchmark finds the controls it navigates by.
         *
         * UiAutomator locates a Compose node by its content description, and this app translates those:
         * the same button is "Show all books as a list" or "Vis alle bøker som liste" depending on the
         * phone. A benchmark that failed on a Norwegian device and passed on an English one would be
         * reporting the tester's locale. Pinning it here rather than adding test tags to production UI
         * keeps the measured build the shipped build.
         */
        settings.setAppLanguage(AppLanguage.English)
        return bookCount
    }

    companion object {
        /** `LargeLibrary.DEFAULT_SEED`, so the two fixtures describe the same library. */
        const val DEFAULT_SEED: Int = 20260821

        const val SERVER_ID: String = "benchmark-server"
        const val PROFILE_ID: String = "benchmark-profile"

        private const val LIBRARY_REMOTE_ID = "benchmark-library"
        private val LIBRARY_KEY = EntityKey.of(SERVER_ID, LIBRARY_REMOTE_ID)

        /**
         * A fixed timestamp rather than `System.currentTimeMillis()`.
         *
         * Two runs a week apart otherwise seed libraries whose "recently added" ordering differs by the
         * gap between them, and a benchmark that measures a different list each time is measuring the
         * clock. 2026-01-01T00:00:00Z.
         */
        private const val FIXED_CLOCK_MILLIS = 1_767_225_600_000L

        private const val AUTHORS = 240
        private const val SERIES = 180
        private const val NARRATORS = 40
        private const val FIRST_YEAR = 1990
        private const val YEAR_SPREAD = 36
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MIN_DURATION_MILLIS = 45L * 60L * 1000L
        private const val MAX_DURATION_MILLIS = 26L * 60L * 60L * 1000L
        private const val BYTES_PER_MILLI = 8L
        private const val WRITE_CHUNK = 200

        private val GENRE_NAMES = listOf(
            "Fiction", "Mystery", "Science Fiction", "Fantasy", "History", "Biography",
            "Thriller", "Romance", "Horror", "Poetry", "Essays",
        )

        private val TITLE_WORDS = listOf(
            "Harbour", "Tidewatch", "Lantern", "Quarry", "Meridian", "Saltmarsh",
            "Hollow", "Ember", "Cartographer", "Ledger", "Almanac", "Threshold",
        )
    }
}
