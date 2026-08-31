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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrossDeviceResumePolicyTest {

    private lateinit var database: ShelfPlayerDatabase
    private lateinit var profiles: StubProfiles
    private lateinit var policy: DefaultResumePolicyRepository
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShelfPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        profiles = StubProfiles(PROFILE)
        seedProfile()
        policy = DefaultResumePolicyRepository(
            profiles = profiles,
            profileDao = database.profileDao(),
            history = database.playbackHistoryDao(),
            ioDispatcher = dispatcher,
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `cross-device resume defaults on and round trips off and on`() = runTest {
        assertTrue(policy.observeCrossDeviceResumeEnabled().first())
        assertTrue(policy.isCrossDeviceResumeEnabled())

        policy.setCrossDeviceResumeEnabled(false)
        assertFalse(policy.observeCrossDeviceResumeEnabled().first())
        assertFalse(policy.isCrossDeviceResumeEnabled())

        policy.setCrossDeviceResumeEnabled(true)
        assertTrue(policy.observeCrossDeviceResumeEnabled().first())
        assertTrue(policy.isCrossDeviceResumeEnabled())
    }

    @Test
    fun `disabled cross-device resume does not contact the listening-session endpoint`() = runTest {
        val gateway = CountingGateway()
        policy.setCrossDeviceResumeEnabled(false)
        val repository = DefaultPlaybackHistoryRepository(
            profileRepository = profiles,
            profileDao = database.profileDao(),
            history = database.playbackHistoryDao(),
            clock = TestAppClock(),
            gateway = gateway,
            device = PlaybackDeviceIdentity { device() },
            logger = RedactingLogger(
                RecordingLogSink(),
                DefaultRedactor(RedactionPolicy.Default),
            ),
            resumePolicy = policy,
            ioDispatcher = dispatcher,
        )

        assertNull(repository.latestServerSession())
        assertEquals(0, gateway.sessionReads)
    }

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
                profileId = PROFILE.value,
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

    private class CountingGateway : AudiobookshelfGateway, PlaybackApi {
        var sessionReads = 0

        override val playback: PlaybackApi get() = this

        override suspend fun listeningSessions(
            profileId: ProfileId,
            page: Int,
            itemsPerPage: Int,
        ): AppResult<List<ListeningSession>> {
            sessionReads += 1
            return AppResult.Success(emptyList())
        }

        override suspend fun openSession(profileId: ProfileId, bookId: LibraryItemId): AppResult<PlaybackSession> =
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

    private class StubProfiles(active: ProfileId) : ProfileRepository {
        private val state = MutableStateFlow<Profile?>(profile(active))

        override fun observeProfiles(): Flow<List<Profile>> = state.map { current -> listOfNotNull(current) }

        override fun observeServers(): Flow<List<Server>> = flowOf(emptyList())

        override fun observeActiveProfile(): Flow<Profile?> = state

        override suspend fun activeProfileId(): ProfileId? = state.value?.id

        override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> {
            state.value = profile(profileId)
            return AppResult.Success(Unit)
        }

        private fun profile(id: ProfileId) = Profile(
            id = id,
            serverId = ServerId(SERVER),
            username = "demo",
            displayName = "Demo listener",
            role = ProfileRole.Listener,
            requiresReauthentication = false,
            lastUsedAt = null,
            isFixture = true,
        )
    }

    private companion object {
        const val SERVER = "fixture-server"
        val PROFILE = ProfileId("fixture-profile")
    }
}
