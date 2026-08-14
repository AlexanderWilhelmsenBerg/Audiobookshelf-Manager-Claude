package com.example.shelfplayer.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
import com.example.shelfplayer.core.database.ShelfPlayerDatabase
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.auth.AccountBookmark
import com.example.shelfplayer.core.model.library.Bookmark
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.core.network.gateway.AuthApi
import com.example.shelfplayer.core.network.gateway.BookmarkApi
import com.example.shelfplayer.core.network.gateway.CapabilityResolver
import com.example.shelfplayer.core.network.gateway.DownloadApi
import com.example.shelfplayer.core.network.gateway.FileTransfer
import com.example.shelfplayer.core.network.gateway.LibraryApi
import com.example.shelfplayer.core.network.gateway.PlaybackApi
import com.example.shelfplayer.core.testing.RecordingLogSink
import com.example.shelfplayer.core.testing.TestAppClock
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.OutputStream
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC 11.1 — bookmarks against a real database and a recording gateway.
 *
 * Three properties are worth this much machinery, and none is visible by reading the class:
 *
 *  - **an offline bookmark survives** — the row is written before the server is called, the failure comes
 *    back, and the row is still there with its flag set;
 *  - **an offline delete does not come back** — the row is *marked*, not removed, so the next refresh cannot
 *    resurrect it;
 *  - **a refresh replaces the rest** — a bookmark deleted on another device disappears here, which a merge
 *    would never manage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultBookmarkRepositoryTest {

    private lateinit var database: ShelfPlayerDatabase
    private lateinit var repository: DefaultBookmarkRepository
    private val gateway = RecordingBookmarkGateway()
    private val sink = RecordingLogSink()
    private val profileId = ProfileId("fixture-profile")

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShelfPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DefaultBookmarkRepository(
            profileRepository = StubProfiles(profileId),
            profileDao = database.profileDao(),
            bookmarkDao = database.bookmarkDao(),
            gateway = gateway,
            clock = TestAppClock(),
            logger = RedactingLogger(sink, DefaultRedactor(RedactionPolicy.Default)),
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        seedProfile()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `a bookmark is stored and sent to the server`() = runTest {
        val added = repository.add(BOOK, at = 31.seconds, title = "A line worth keeping")

        assertIs<AppResult.Success<Unit>>(added)
        val stored = repository.observe(BOOK).first().single()
        assertEquals(31.seconds, stored.at)
        assertEquals("A line worth keeping", stored.title)
        assertFalse(storedRow(31)!!.hasUnsyncedChanges, "the server accepted it")
        assertEquals(listOf(31L), gateway.created)
    }

    /**
     * Product priority 2 applied to a listener's own words.
     *
     * The server is unavailable, the bookmark is on screen anyway, and the flag records that the server has
     * not seen it. Rolling the row back because the network was down would be the app discarding something
     * the listener deliberately kept.
     */
    @Test
    fun `a bookmark made offline is kept, flagged, and the failure is reported`() = runTest {
        gateway.fails = true

        val added = repository.add(BOOK, at = 31.seconds, title = "On a train")

        assertIs<AppResult.Failure>(added)
        assertEquals(31.seconds, repository.observe(BOOK).first().single().at, "and it is still on screen")
        assertTrue(storedRow(31)!!.hasUnsyncedChanges)
    }

    /**
     * The server keys bookmarks by the second, so two in the same second are one.
     *
     * The local table has to agree, or the app would show a row that vanishes at the next refresh. The
     * second title wins, which is what a server overwrite does.
     */
    @Test
    fun `two bookmarks in the same second are one bookmark`() = runTest {
        repository.add(BOOK, at = 31.seconds, title = "First")
        repository.add(BOOK, at = 31.seconds, title = "Second")

        val stored = repository.observe(BOOK).first()
        assertEquals(1, stored.size)
        assertEquals("Second", stored.single().title)
    }

    @Test
    fun `removing a bookmark deletes the row once the server agrees`() = runTest {
        repository.add(BOOK, at = 31.seconds, title = "Temporary")

        val removed = repository.remove(BOOK, at = 31.seconds)

        assertIs<AppResult.Success<Unit>>(removed)
        assertEquals(emptyList(), repository.observe(BOOK).first())
        assertEquals(listOf(31L), gateway.removed)
    }

    /**
     * The reason a delete is a flag and not a row removal.
     *
     * A bookmark deleted with no network is gone from the listener's point of view immediately, and must not
     * reappear when the next refresh brings the server's array — which still contains it. The row therefore
     * stays, marked, and the refresh is told to leave it alone.
     */
    @Test
    fun `a bookmark deleted offline stays deleted through a refresh`() = runTest {
        repository.add(BOOK, at = 31.seconds, title = "Doomed")
        gateway.fails = true

        val removed = repository.remove(BOOK, at = 31.seconds)

        assertIs<AppResult.Failure>(removed)
        assertEquals(emptyList(), repository.observe(BOOK).first(), "gone from the list")

        // The server still has it, and says so on the next refresh.
        repository.writeAccountBookmarks(profileId, listOf(accountBookmark(31, "Doomed")))

        assertEquals(emptyList(), repository.observe(BOOK).first(), "and it does not come back")
    }

    /**
     * A refresh is a **replace**, so a bookmark deleted on another device disappears.
     *
     * This is the half a merge could not do. The server's array is the whole truth about what exists, and a
     * local table that only ever added rows would keep somebody's deleted notes forever.
     */
    @Test
    fun `a refresh removes a bookmark that is gone on the server`() = runTest {
        repository.writeAccountBookmarks(
            profileId,
            listOf(accountBookmark(31, "Kept"), accountBookmark(90, "Deleted elsewhere")),
        )
        assertEquals(listOf(31L, 90L), repository.observe(BOOK).first().map { it.at.inWholeSeconds })

        repository.writeAccountBookmarks(profileId, listOf(accountBookmark(31, "Kept")))

        assertEquals(listOf(31L), repository.observe(BOOK).first().map { it.at.inWholeSeconds })
    }

    /** And a refresh does not discard what the server has not been told about yet. */
    @Test
    fun `a refresh keeps an unsynced bookmark the server has never seen`() = runTest {
        gateway.fails = true
        repository.add(BOOK, at = 31.seconds, title = "Made on a train")
        gateway.fails = false

        repository.writeAccountBookmarks(profileId, listOf(accountBookmark(90, "From the web player")))

        assertEquals(
            listOf(31L, 90L),
            repository.observe(BOOK).first().map { it.at.inWholeSeconds },
            "both the local one and the server's",
        )
    }

    /** Earliest first — the order they occur in the book, which is the order a listener looks for. */
    @Test
    fun `bookmarks are listed by position`() = runTest {
        repository.add(BOOK, at = 40.minutes, title = "Later")
        repository.add(BOOK, at = 31.seconds, title = "Earlier")

        assertEquals(
            listOf("Earlier", "Later"),
            repository.observe(BOOK).first().map { it.title },
        )
    }

    /** Renaming a bookmark nobody has is a failure the caller can show, not a silent new bookmark. */
    @Test
    fun `renaming a bookmark that is not there fails`() = runTest {
        val renamed = repository.rename(BOOK, at = 31.seconds, title = "Nothing here")

        assertIs<AppResult.Failure>(renamed)
        assertEquals(emptyList(), repository.observe(BOOK).first())
    }

    /** PRODUCT_SPEC 14.5 — the note is the listener's own words about a book, and never reaches a log. */
    @Test
    fun `no log line carries a bookmark's note`() = runTest {
        repository.add(BOOK, at = 31.seconds, title = "The bit about the harbour")

        assertFalse(sink.text.contains("harbour", ignoreCase = true), sink.text)
        assertTrue(sink.text.contains("bookmark", ignoreCase = true), "but the event is recorded: ${sink.text}")
    }

    private suspend fun storedRow(atSeconds: Long) =
        database.bookmarkDao().find(profileId.value, EntityKey.of(SERVER, BOOK.value), atSeconds)

    private fun accountBookmark(atSeconds: Long, title: String) = AccountBookmark(
        bookId = BOOK,
        atSeconds = atSeconds,
        title = title,
        createdAt = Instant.ofEpochMilli(1_000),
    )

    private suspend fun seedProfile() {
        database.profileDao().upsertServer(
            ServerEntity(
                serverId = SERVER,
                displayName = "Demo",
                baseUrl = "https://fixture.invalid",
                detectedVersion = "fixture-0",
                isFixture = true,
                lastFetchedAt = 0,
                authMethodsJson = "[]",
                capabilitiesJson = "[]",
                capabilitiesDetectedAt = null,
            ),
        )
        database.profileDao().upsertProfile(
            ProfileEntity(
                profileId = profileId.value,
                serverId = SERVER,
                remoteUserId = null,
                username = "demo",
                displayName = "Demo listener",
                role = "Listener",
                requiresReauthentication = false,
                lastUsedAt = null,
                isFixture = true,
                accessibleLibrariesJson = "[]",
                hasAllLibraryAccess = true,
                hasAllTagAccess = true,
                canDownload = false,
            ),
        )
    }

    /**
     * A gateway that records the bookmark writes and can be made to fail.
     *
     * Hand-written rather than mocked (PRODUCT_SPEC 17.1). Only [BookmarkApi] is implemented; the other four
     * sub-APIs throw, because a test that reached one would be testing something this class does not do.
     */
    private class RecordingBookmarkGateway :
        AudiobookshelfGateway,
        BookmarkApi {
        var fails: Boolean = false
        val created = mutableListOf<Long>()
        val removed = mutableListOf<Long>()

        /**
         * PRODUCT_SPEC DL-001 — not exercised by this test, and refusing rather than pretending.
         *
         * A fake that produced bytes would let a test believe a download had happened, which is precisely the
         * belief the download layer exists to make impossible.
         */
        override val downloads: DownloadApi = object : DownloadApi {
            override suspend fun fetchFile(
                profileId: ProfileId,
                bookId: LibraryItemId,
                fileId: String,
                sink: (Boolean) -> OutputStream,
                resumeFrom: Long,
                validator: String?,
                onProgress: (Long) -> Unit,
            ): AppResult<FileTransfer> = AppResult.Failure(
                AppError.ApiCompatibility(summary = "This fake serves no audio files."),
            )
        }

        override val bookmarks: BookmarkApi get() = this

        override suspend fun create(
            profileId: ProfileId,
            bookId: LibraryItemId,
            at: Duration,
            title: String,
        ): AppResult<Bookmark> {
            if (fails) return offline()
            created += at.inWholeSeconds
            return AppResult.Success(
                Bookmark(bookId, at, title, createdAt = Instant.ofEpochMilli(1_000)),
            )
        }

        override suspend fun rename(
            profileId: ProfileId,
            bookId: LibraryItemId,
            at: Duration,
            title: String,
        ): AppResult<Bookmark> {
            if (fails) return offline()
            return AppResult.Success(Bookmark(bookId, at, title, createdAt = Instant.ofEpochMilli(1_000)))
        }

        override suspend fun remove(profileId: ProfileId, bookId: LibraryItemId, at: Duration): AppResult<Unit> {
            if (fails) return offline()
            removed += at.inWholeSeconds
            return AppResult.Success(Unit)
        }

        private fun <T> offline(): AppResult<T> = AppResult.Failure(AppError.Network(summary = "No connection."))

        override val auth: AuthApi get() = unused()
        override val capabilities: CapabilityResolver get() = unused()
        override val library: LibraryApi get() = unused()
        override val playback: PlaybackApi get() = unused()

        private fun <T> unused(): T = error("not part of this test")
    }

    /** The active profile, without a database of profiles behind it. */
    private class StubProfiles(private val activeProfileId: ProfileId?) : ProfileRepository {
        override fun observeProfiles(): Flow<List<Profile>> = flowOf(emptyList())

        override fun observeServers(): Flow<List<Server>> = flowOf(emptyList())

        /**
         * A real profile, unlike the stub in `DefaultPlaybackRepositoryTest`.
         *
         * `observe` re-subscribes on this flow, so a `null` here would make every read return an empty list
         * and every assertion in this file pass for the wrong reason.
         */
        override fun observeActiveProfile(): Flow<Profile?> = MutableStateFlow(
            activeProfileId?.let { id ->
                Profile(
                    id = id,
                    serverId = ServerId(SERVER),
                    username = "demo",
                    displayName = "Demo listener",
                    role = com.example.shelfplayer.core.model.ProfileRole.Listener,
                    requiresReauthentication = false,
                    lastUsedAt = null,
                    isFixture = true,
                )
            },
        )

        override suspend fun activeProfileId(): ProfileId? = activeProfileId

        override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> = AppResult.Success(Unit)
    }

    private companion object {
        const val SERVER = "fixture-server"
        val BOOK = LibraryItemId("book-salt-harbour")
    }
}
