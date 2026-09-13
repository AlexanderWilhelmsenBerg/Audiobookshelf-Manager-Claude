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

    @Test
    fun `a session from another device becomes a history row`() = runTest {
        gateway.sessions = listOf(session(id = "s1", deviceId = OTHER_DEVICE))

        repository.refreshServerSessions(BOOK)

        val row = repository.observe(BOOK).first().single()
        assertEquals(PlaybackEvent.ServerSession, row.event)
        assertEquals(2.hours, row.from)
        assertEquals(3.hours, row.to)
        assertEquals(20.minutes, row.detail)
        assertEquals(STARTED_AT, row.at)
    }

    @Test
    fun `the same session imported twice stays one row`() = runTest {
        gateway.sessions = listOf(session(id = "s1", deviceId = OTHER_DEVICE))
        repository.refreshServerSessions(BOOK)
        repository.refreshServerSessions(BOOK)
        assertEquals(1, repository.observe(BOOK).first().size)
    }

    @Test
    fun `a session from this device is not imported`() = runTest {
        gateway.sessions = listOf(session(id = "s1", deviceId = THIS_DEVICE))
        repository.refreshServerSessions(BOOK)
        assertTrue(repository.observe(BOOK).first().isEmpty())
    }

    @Test
    fun `a session with nothing listened is not imported`() = runTest {
        gateway.sessions = listOf(session(id = "s1", deviceId = OTHER_DEVICE, listened = Duration.ZERO))
        repository.refreshServerSessions(BOOK)
        assertTrue(repository.observe(BOOK).first().isEmpty())
    }

    @Test
    fun `another book's session is not imported`() = runTest {
        gateway.sessions = listOf(
            session(id = "s1", deviceId = OTHER_DEVICE, bookId = LibraryItemId("some-other-book")),
        )
        repository.refreshServerSessions(BOOK)
        assertTrue(repository.observe(BOOK).first().isEmpty())
    }

    @Test
    fun `a session with no device id is treated as another device's`() = runTest {
        gateway.sessions = listOf(session(id = "s1", deviceId = null))
        repository.refreshServerSessions(BOOK)
        assertEquals(1, repository.observe(BOOK).first().size)
    }

    @Test
    fun `a failed fetch keeps what was already imported`() = runTest {
        gateway.sessions = listOf(session(id = "s1", deviceId = OTHER_DEVICE))
        repository.refreshServerSessions(BOOK)
        gateway.fails = true
        repository.refreshServerSessions(BOOK)
        assertEquals(1, repository.observe(BOOK).first().size)
    }

    @Test
    fun `the import logs counts and no private data`() = runTest {
        gateway.sessions = listOf(session(id = "s1", deviceId = OTHER_DEVICE))
        repository.refreshServerSessions(BOOK)
        val rendered = sink.text
        for (secret in listOf(TITLE, OTHER_DEVICE_NAME, BOOK.value)) {
            assertTrue(secret !in rendered, "$secret reached the log")
        }
    }

    @Test
    fun `a local pause persists without a server round trip`() = runTest {
        gateway.fails = true
        repository.record(
            bookId = BOOK,
            event = PlaybackEvent.Pause,
            from = null,
            to = 3.hours,
            owner = profileId,
        )
        val row = repository.observe(BOOK).first().single()
        assertEquals(PlaybackEvent.Pause, row.event)
        assertEquals(3.hours, row.to)
    }

    @Test
    fun `a sleep timer stop persists with its explicit cause`() = runTest {
        gateway.fails = true
        repository.record(
            bookId = BOOK,
            event = PlaybackEvent.SleepTimerExpired,
            from = null,
            to = 3.hours,
            owner = profileId,
        )
        val row = repository.observe(BOOK).first().single()
        assertEquals(PlaybackEvent.SleepTimerExpired, row.event)
        assertEquals(3.hours, row.to)
    }

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
    )

    private suspend fun seedProfile() {
        database.serverDao().upsert(
            ServerEntity(
                serverId = SERVER.value,
                displayName = "Fixture Server",
                normalizedBaseUrl = "https://example.invalid",
                lastConnectedAt = 0,
            ),
        )
        database.profileDao().upsert(
            ProfileEntity(
                profileId = profileId.value,
                serverId = SERVER.value,
                userId = "user",
                username = "fixture",
                displayName = "Fixture",
                role = ProfileRole.User.name,
                permissionsJson = "[]",
                permissionsHash = "hash",
                createdAt = 0,
                lastUsedAt = 0,
                isLocked = false,
                lockMode = null,
            ),
        )
    }

    private class StubProfiles(private val profileId: ProfileId) : ProfileRepository {
        private val profile = Profile(
            id = profileId,
            serverId = SERVER,
            userId = "user",
            username = "fixture",
            displayName = "Fixture",
            role = ProfileRole.User,
            permissions = emptySet(),
            permissionsHash = "hash",
            createdAt = Instant.EPOCH,
            lastUsedAt = Instant.EPOCH,
            isLocked = false,
            lockMode = null,
        )
        override fun observeProfiles(): Flow<List<Profile>> = flowOf(listOf(profile))
        override fun observeServers(): Flow<List<Server>> = flowOf(emptyList())
        override fun observeActiveProfile(): Flow<Profile?> = flowOf(profile)
        override suspend fun activeProfileId(): ProfileId = profileId
        override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> = AppResult.Success(Unit)
    }

    private class RecordingSessionGateway : AudiobookshelfGateway {
        var sessions: List<ListeningSession> = emptyList()
        var fails = false
        override val auth: AuthApi = noOp()
        override val library: LibraryApi = noOp()
        override val playback: PlaybackApi = object : PlaybackApi {
            override suspend fun openSession(bookId: LibraryItemId): AppResult<PlaybackSession> = AppResult.Failure(AppError.Network())
            override suspend fun listListeningSessions(profileId: ProfileId): AppResult<List<ListeningSession>> =
                if (fails) AppResult.Failure(AppError.Network()) else AppResult.Success(sessions)
            override suspend fun progress(bookId: LibraryItemId): AppResult<ServerProgress?> = AppResult.Success(null)
            override suspend fun updateProgress(bookId: LibraryItemId, progress: SessionProgress): AppResult<Unit> = AppResult.Success(Unit)
            override suspend fun syncOfflineSession(session: OfflineSession): AppResult<OfflineSessionResult> = AppResult.Failure(AppError.Network())
        }
        override val bookmarks: BookmarkApi = noOp()
        override val downloads: DownloadApi = noOp()
        override val management: ManagementApi = noOp()
        override val capabilityResolver: CapabilityResolver = noOp()

        @Suppress("UNCHECKED_CAST")
        private inline fun <reified T> noOp(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                java.lang.Void.TYPE -> Unit
                else -> null
            }
        } as T
    }

    companion object {
        private val SERVER = ServerId("fixture-server")
        private val BOOK = LibraryItemId("book")
        private const val THIS_DEVICE = "this-device"
        private const val OTHER_DEVICE = "other-device"
        private const val OTHER_DEVICE_NAME = "Other device"
        private const val TITLE = "Private title"
        private val STARTED_AT = Instant.parse("2026-08-20T20:00:00Z")
    }
}
