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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResumeSessionCacheTest {

    private lateinit var database: ShelfPlayerDatabase
    private lateinit var repository: DefaultPlaybackHistoryRepository
    private val gateway = SessionGateway()
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
            device = PlaybackDeviceIdentity { device() },
            logger = RedactingLogger(
                RecordingLogSink(),
                DefaultRedactor(RedactionPolicy.Default),
            ),
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        seedProfile()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `a successful server resume answer survives a later outage`() = runTest {
        val live = session()
        gateway.sessions = listOf(live)

        assertEquals(live, repository.latestServerSession())
        assertTrue(repository.observe(BOOK).first().isEmpty(), "the resume cache must not appear in History")

        gateway.fails = true
        val cached = repository.latestServerSession()

        assertEquals(BOOK, cached?.bookId)
        assertEquals(3.hours, cached?.reachedAt)
        assertEquals(UPDATED_AT, cached?.updatedAt)
        assertTrue(cached?.listened?.let { it > Duration.ZERO } == true)
    }

    @Test
    fun `a successful empty server answer clears a previously cached resume`() = runTest {
        gateway.sessions = listOf(session())
        repository.latestServerSession()

        gateway.sessions = emptyList()
        assertNull(repository.latestServerSession())

        gateway.fails = true
        assertNull(repository.latestServerSession(), "an outage must not resurrect a cache the server cleared")
    }

    private fun session() = ListeningSession(
        id = "session-1",
        bookId = BOOK,
        deviceId = "other-device",
        deviceName = "Other device",
        clientName = "Audiobookshelf Web",
        listened = 20.minutes,
        startedFrom = 2.hours,
        reachedAt = 3.hours,
        startedAt = STARTED_AT,
        updatedAt = UPDATED_AT,
    )

    private fun device() = PlaybackDevice(
        clientName = "BookWave",
        clientVersion = "test",
        deviceId = "this-device",
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

    private class SessionGateway : AudiobookshelfGateway, PlaybackApi {
        var sessions: List<ListeningSession> = emptyList()
        var fails = false

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

        override suspend fun openSession(profileId: ProfileId, bookId: LibraryItemId): AppResult<PlaybackSession> = unused()

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

    private class StubProfiles(private val active: ProfileId) : ProfileRepository {
        override fun observeProfiles(): Flow<List<Profile>> = flowOf(emptyList())

        override fun observeServers(): Flow<List<Server>> = flowOf(emptyList())

        override fun observeActiveProfile(): Flow<Profile?> = MutableStateFlow(
            Profile(
                id = active,
                serverId = ServerId(SERVER),
                username = "demo",
                displayName = "Demo listener",
                role = ProfileRole.Listener,
                requiresReauthentication = false,
                lastUsedAt = null,
                isFixture = true,
            ),
        )

        override suspend fun activeProfileId(): ProfileId = active

        override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> = AppResult.Success(Unit)
    }

    private companion object {
        const val SERVER = "fixture-server"
        val BOOK = LibraryItemId("book-salt-harbour")
        val STARTED_AT: Instant = Instant.parse("2026-08-31T07:00:00Z")
        val UPDATED_AT: Instant = Instant.parse("2026-08-31T07:20:00Z")
    }
}
