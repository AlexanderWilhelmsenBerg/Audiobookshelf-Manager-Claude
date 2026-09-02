package com.example.shelfplayer.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
import com.example.shelfplayer.core.database.ShelfPlayerDatabase
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.ListeningSession
import com.example.shelfplayer.core.model.playback.OfflineSession
import com.example.shelfplayer.core.model.playback.OfflineSessionResult
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.core.model.playback.ServerProgress
import com.example.shelfplayer.core.model.playback.SessionProgress
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.core.network.gateway.AuthApi
import com.example.shelfplayer.core.network.gateway.BookmarkApi
import com.example.shelfplayer.core.network.gateway.CapabilityResolver
import com.example.shelfplayer.core.network.gateway.DownloadApi
import com.example.shelfplayer.core.network.gateway.LibraryApi
import com.example.shelfplayer.core.network.gateway.ManagementApi
import com.example.shelfplayer.core.network.gateway.PlaybackApi
import com.example.shelfplayer.core.network.gateway.PlaybackDevice
import com.example.shelfplayer.core.network.gateway.PlaybackDeviceIdentity
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
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * PRODUCT_SPEC PLAY-003 — the history pane, populated from Audiobookshelf's own listening sessions.
 *
 * ### What the owner asked for, and the hole it fills
 *
 * *"Currently it only shows local events, but I want to have it populated from events from audiobookshelf
 * itself."* The remote rows that existed were **derived** — `LibrarySnapshotWriter.recordRemoteChange` diffs
 * stored progress against a sync — and that has two holes it cannot close: it needs a previous local row, so
 * a book listened to elsewhere and never played here produces nothing; and it sees only the endpoints, so
 * two sessions between syncs collapse into one.
 *
 * These cover the import that replaces the reconstruction, against a **real database** rather than a mocked
 * DAO, because three of the four properties are about what the table ends up holding.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerSessionHistoryTest {

    private lateinit var database: ShelfPlayerDatabase
    private lateinit var repository: DefaultPlaybackHistoryRepository
    private val gateway = RecordingSessionGateway()
    private val sink = RecordingLogSink()
    private val profileId = ProfileId("fixture-profile")

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShelfPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DefaultPlaybackHistoryRepository(
            profileRepository = StubProfiles(profileId),
            profileDao = database.profileDao(),
            history = database.playbackHistoryDao(),
            clock = TestAppClock(),
            gateway = gateway,
            device = PlaybackDeviceIdentity { thisDevice() },
            logger = RedactingLogger(sink, DefaultRedactor(RedactionPolicy.Default)),
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        seedProfile()
    }

    @After
    fun tearDown() = database.close()

    // ------------------------------------------------------------------ the hole this closes

    /**
     * **A book this device has never played still gets a history row.**
     *
     * This is the case the derived remote history could not reach at all: with no local row to diff against,
     * `recordRemoteChange` returned early and the pane stayed empty while the position had plainly moved.
     */
    @Test
    fun `a session from another device becomes a history row`() = runTest {
        gateway.sessions = listOf(session(id = "s1", deviceId = OTHER_DEVICE))

        repository.refreshServerSessions(BOOK)

        val row = repository.observe(BOOK).first().single()
        assertEquals(PlaybackEvent.ServerSession, row.event)
        assertEquals(2.hours, row.from, "where the session opened, which is where tapping the row returns")
        assertEquals(3.hours, row.to)
        assertEquals(20.minutes, row.detail, "how much was actually listened, not the span")
        assertEquals(STARTED_AT, row.at, "the server's own start time, not when the fetch noticed")
    }

    /**
     * **Importing the same session twice is one row, not two.**
     *
     * The pane refreshes every time it is opened, so this is the ordinary path rather than an edge case. The
     * row's key is derived from the session's own id, which is what makes the write idempotent — and is why
     * persisting these needed no Room migration.
     */
    @Test
    fun `the same session imported twice stays one row`() = runTest {
        gateway.sessions = listOf(session(id = "s1", deviceId = OTHER_DEVICE))

        repository.refreshServerSessions(BOOK)
        repository.refreshServerSessions(BOOK)

        assertEquals(1, repository.observe(BOOK).first().size)
    }

    // ------------------------------------------------------------------ what is deliberately not imported

    /**
     * **This device's own sessions are not imported**, because the player already writes `Play` and `Pause`
     * rows for them and a second account of the same listening is noise.
     *
     * Told apart by the per-install id the app sends when it opens a session, which is the only thing that
     * distinguishes them — the server has no notion of "this client".
     */
    @Test
    fun `a session from this device is not imported`() = runTest {
        gateway.sessions = listOf(session(id = "s1", deviceId = THIS_DEVICE))

        repository.refreshServerSessions(BOOK)

        assertTrue(repository.observe(BOOK).first().isEmpty())
    }

    /**
     * A session that listened to nothing is not a row.
     *
     * Opening a book and closing it leaves a zero-second session on the server, and "somebody listened for
     * no time" is a line that costs a reader attention and tells them nothing.
     */
    @Test
    fun `a session with nothing listened is not imported`() = runTest {
        gateway.sessions = listOf(session(id = "s1", deviceId = OTHER_DEVICE, listened = Duration.ZERO))

        repository.refreshServerSessions(BOOK)

        assertTrue(repository.observe(BOOK).first().isEmpty())
    }

    /** The endpoint is account-wide, so another book's sessions must not land on this one. */
    @Test
    fun `another book's session is not imported`() = runTest {
        gateway.sessions = listOf(
            session(id = "s1", deviceId = OTHER_DEVICE, bookId = LibraryItemId("some-other-book")),
        )

        repository.refreshServerSessions(BOOK)

        assertTrue(repository.observe(BOOK).first().isEmpty())
    }

    /**
     * A session with **no** device id counts as another device's.
     *
     * The alternative drops a real session from a client that did not identify itself, and a duplicate row
     * is a smaller loss than a missing one.
     */
    @Test
    fun `a session with no device id is treated as another device's`() = runTest {
        gateway.sessions = listOf(session(id = "s1", deviceId = null))

        repository.refreshServerSessions(BOOK)

        assertEquals(1, repository.observe(BOOK).first().size)
    }

    // ------------------------------------------------------------------ failure

    /**
     * **A failed refresh leaves the stored history alone**, rather than clearing it or surfacing an error.
     *
     * This is the offline case, and it is the argument for persisting rather than merging at read time: the
     * rows imported by an earlier refresh are exactly what somebody wants to see when the network is gone.
     */
    @Test
    fun `a failed fetch keeps what was already imported`() = runTest {
        gateway.sessions = listOf(session(id = "s1", deviceId = OTHER_DEVICE))
        repository.refreshServerSessions(BOOK)

        gateway.fails = true
        repository.refreshServerSessions(BOOK)

        assertEquals(1, repository.observe(BOOK).first().size, "the imported row survives an outage")
    }

    /** PRODUCT_SPEC 14.5 — nothing private reaches the log: no title, no device name, no book id. */
    @Test
    fun `the import logs counts and no private data`() = runTest {
        gateway.sessions = listOf(session(id = "s1", deviceId = OTHER_DEVICE))

        repository.refreshServerSessions(BOOK)

        // `text` is the rendered line *after* redaction, which is what would actually be pasted into a
        // support report — a stronger check than inspecting the fields before they are formatted.
        val rendered = sink.text
        for (secret in listOf(TITLE, OTHER_DEVICE_NAME, BOOK.value)) {
            assertTrue(secret !in rendered, "$secret reached the log")
        }
    }

    // ------------------------------------------------------------------ fixtures

    private fun session(id: String, deviceId: String?, bookId: LibraryItemId = BOOK, listened: Duration = 20.minutes) =
        ListeningSession(
            id = id,
            bookId = bookId,
            deviceId = deviceId,
            deviceName = OTHER_DEVICE_NAME,
            clientName = "Audiobookshelf Web",
            listened = listened,
            startedFrom = 2.hours,
            reachedAt = 3.hours,
            startedAt = STARTED_AT,
        )

    private fun thisDevice() = PlaybackDevice(
        clientName = "BookWave",
        clientVersion = "0.9.14",
        deviceId = THIS_DEVICE,
        manufacturer = "fixture",
        model = "fixture",
        sdkVersion = 34,
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

    /** A gateway whose only working half is the session read this test is about. */
    private class RecordingSessionGateway :
        AudiobookshelfGateway,
        PlaybackApi {
        var sessions: List<ListeningSession> = emptyList()
        var fails: Boolean = false

        override val playback: PlaybackApi get() = this

        override suspend fun listeningSessions(
            profileId: ProfileId,
            page: Int,
            itemsPerPage: Int,
        ): AppResult<List<ListeningSession>> = if (fails) {
            AppResult.Failure(AppError.Network(summary = "No connection."))
        } else {
            AppResult.Success(sessions)
        }

        override suspend fun openSession(profileId: ProfileId, bookId: LibraryItemId): AppResult<PlaybackSession> =
            unused()
        override suspend fun serverProgress(profileId: ProfileId, bookId: LibraryItemId): AppResult<ServerProgress> =
            unused()

        override suspend fun syncSession(
            profileId: ProfileId,
            sessionId: String,
            progress: SessionProgress,
        ): AppResult<Unit> = unused()

        override suspend fun closeSession(
            profileId: ProfileId,
            sessionId: String,
            progress: SessionProgress,
        ): AppResult<Unit> = unused()

        override suspend fun syncOfflineSessions(
            profileId: ProfileId,
            sessions: List<OfflineSession>,
        ): AppResult<List<OfflineSessionResult>> = unused()

        override suspend fun setFinished(
            profileId: ProfileId,
            bookId: LibraryItemId,
            isFinished: Boolean,
            position: Duration,
        ): AppResult<Unit> = unused()

        override val auth: AuthApi get() = unused()
        override val capabilities: CapabilityResolver get() = unused()
        override val library: LibraryApi get() = unused()
        override val bookmarks: BookmarkApi get() = unused()
        override val downloads: DownloadApi get() = unused()
        override val management: ManagementApi get() = unused()

        private fun <T> unused(): T = error("not part of this test")
    }

    /** The active profile, without a database of profiles behind it. */
    private class StubProfiles(private var active: ProfileId?) : ProfileRepository {
        override fun observeProfiles(): Flow<List<Profile>> = flowOf(emptyList())

        override fun observeServers(): Flow<List<Server>> = flowOf(emptyList())

        override fun observeActiveProfile(): Flow<Profile?> = MutableStateFlow(
            active?.let { id ->
                Profile(
                    id = id,
                    serverId = ServerId(SERVER),
                    username = "demo",
                    displayName = "Demo listener",
                    role = ProfileRole.Listener,
                    requiresReauthentication = false,
                    lastUsedAt = null,
                    isFixture = true,
                )
            },
        )

        override suspend fun activeProfileId(): ProfileId? = active

        override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> {
            active = profileId
            return AppResult.Success(Unit)
        }
    }

    private companion object {
        const val SERVER = "fixture-server"
        const val THIS_DEVICE = "install-abc"
        const val OTHER_DEVICE = "install-xyz"
        const val OTHER_DEVICE_NAME = "Marisol's iPad"
        const val TITLE = "The Salt Harbour"
        val BOOK = LibraryItemId("book-salt-harbour")

        /** A moment well before the test clock, so "the server's own time" is distinguishable. */
        val STARTED_AT: Instant = Instant.ofEpochSecond(1_700_000_000)
    }
}
