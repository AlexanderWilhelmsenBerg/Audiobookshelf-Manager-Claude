package com.example.shelfplayer.data.auth

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
import com.example.shelfplayer.core.database.ShelfPlayerDatabase
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import com.example.shelfplayer.core.datastore.security.SessionTokenStore
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerCapabilities
import com.example.shelfplayer.core.model.ServerCapability
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.asFailure
import com.example.shelfplayer.core.model.auth.AuthToken
import com.example.shelfplayer.core.testing.RecordingLogSink
import com.example.shelfplayer.core.testing.TestAppClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC SYNC-001 — the handshake is persisted, and an unconfirmed capability reads as
 * unsupported.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultCapabilityRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = FakeAuthGateway()

    private lateinit var database: ShelfPlayerDatabase
    private lateinit var repository: DefaultCapabilityRepository
    private lateinit var tokens: SessionTokenProvider

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, ShelfPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tokens = SessionTokenProvider(SessionTokenStore(context, ReversibleTestCipher(), dispatcher))
        repository = DefaultCapabilityRepository(
            gateway = gateway,
            profileDao = database.profileDao(),
            tokens = tokens,
            clock = TestAppClock(),
            logger = RedactingLogger(RecordingLogSink(), DefaultRedactor(RedactionPolicy.Default)),
            ioDispatcher = dispatcher,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * The distinction the UI depends on: "no handshake has run" is not "nothing is supported"
     * (PRODUCT_SPEC SYNC-001 requires an explanation, and those are different explanations).
     */
    @Test
    fun `a server with no handshake reports no capabilities and no detection time`() = runTest {
        seedServerAndProfile()

        val stored = assertNotNull(repository.capabilities(SERVER))
        assertEquals(emptySet(), stored.supported)
        assertNull(assertNotNull(database.profileDao().findServer(SERVER.value)).capabilitiesDetectedAt)
    }

    @Test
    fun `a handshake stores the version, the authentication modes and the detection time`() = runTest {
        seedServerAndProfile()

        val result = repository.handshake(PROFILE)

        assertIs<AppResult.Success<*>>(result)
        val stored = assertNotNull(repository.capabilities(SERVER))
        assertEquals("2.36.0", stored.serverVersion)
        assertEquals(listOf("local"), stored.authMethods)
        assertNotNull(assertNotNull(database.profileDao().findServer(SERVER.value)).capabilitiesDetectedAt)
    }

    /** PRODUCT_SPEC 5.2 — the handshake is addressed at the profile's own server, never an ambient one. */
    @Test
    fun `the handshake targets the server the profile belongs to`() = runTest {
        seedServerAndProfile()

        repository.handshake(PROFILE)

        assertEquals(
            listOf(FakeAuthGateway.Handshake(SERVER, "https://books.example")),
            gateway.handshakes,
        )
    }

    /**
     * PRODUCT_SPEC MGR-003 / 5.2 — the authenticated probes travel with *this* profile's credential.
     *
     * The provider probe is the first thing in the handshake that needs a token, and a handshake that
     * reached for an ambient one would attribute another account's session to this server.
     */
    @Test
    fun `the handshake carries the profile's own access token`() = runTest {
        seedServerAndProfile()
        tokens.adopt(PROFILE, FakeAuthGateway.session(accessToken = "profile-a-token"))

        repository.handshake(PROFILE)

        assertEquals(AuthToken("profile-a-token"), gateway.handshakes.single().accessToken)
    }

    /**
     * A cold start can hand the handshake a profile with no stored token, and that is not a failure: the
     * version and the authentication modes come from `/status`, which needs no credential. Only the
     * authenticated probes go unanswered, which SYNC-001 already defines as unsupported.
     */
    @Test
    fun `a profile with no stored token still completes the handshake`() = runTest {
        seedServerAndProfile()

        val result = repository.handshake(PROFILE)

        assertIs<AppResult.Success<*>>(result)
        assertNull(gateway.handshakes.single().accessToken)
    }

    @Test
    fun `a confirmed capability round-trips through storage`() = runTest {
        seedServerAndProfile()
        gateway.capabilitiesResult = AppResult.Success(
            ServerCapabilities(
                serverId = SERVER,
                serverVersion = "2.36.0",
                supported = setOf(ServerCapability.Websocket, ServerCapability.RangeDownload),
                authMethods = listOf("local"),
            ),
        )

        repository.handshake(PROFILE)

        val stored = assertNotNull(repository.capabilities(SERVER))
        assertTrue(stored.supports(ServerCapability.Websocket))
        assertTrue(stored.supports(ServerCapability.RangeDownload))
        assertFalse(stored.supports(ServerCapability.SourceFileDelete))
    }

    /**
     * PRODUCT_SPEC SYNC-001 — a stored name this build does not recognise is dropped, not honoured.
     *
     * The scenario is a downgrade: a newer build wrote a capability name that this one has never heard
     * of. Treating it as supported would enable a feature whose code is not present.
     */
    @Test
    fun `a stored capability name this build does not know is ignored`() = runTest {
        seedServerAndProfile()
        database.profileDao().updateServerCapabilities(
            serverId = SERVER.value,
            capabilitiesJson = """["Websocket","TeleportBooks"]""",
            authMethodsJson = """["local"]""",
            serverVersion = "9.9.9",
            detectedAt = 1,
        )

        val stored = assertNotNull(repository.capabilities(SERVER))

        assertEquals(setOf(ServerCapability.Websocket), stored.supported)
    }

    @Test
    fun `a failed handshake leaves the previous result in place`() = runTest {
        seedServerAndProfile()
        repository.handshake(PROFILE)
        gateway.capabilitiesResult = AppError.Network().asFailure()

        val result = repository.handshake(PROFILE)

        assertIs<AppResult.Failure>(result)
        assertEquals("2.36.0", assertNotNull(repository.capabilities(SERVER)).serverVersion)
    }

    @Test
    fun `the stored handshake is observable`() = runTest {
        seedServerAndProfile()
        repository.handshake(PROFILE)

        val observed = repository.observeCapabilities(SERVER).first()

        assertEquals(listOf("local"), assertNotNull(observed).authMethods)
    }

    @Test
    fun `a handshake for an unsaved profile is a validation failure`() = runTest {
        val error = assertIs<AppResult.Failure>(repository.handshake(ProfileId("prf_missing"))).error

        assertIs<AppError.Validation>(error)
        assertTrue(gateway.handshakes.isEmpty())
    }

    private suspend fun seedServerAndProfile() {
        database.profileDao().upsertServer(
            ServerEntity(
                serverId = SERVER.value,
                displayName = "books.example",
                baseUrl = "https://books.example",
                detectedVersion = null,
                isFixture = false,
                lastFetchedAt = 0,
                authMethodsJson = "[]",
                capabilitiesJson = "[]",
                capabilitiesDetectedAt = null,
            ),
        )
        database.profileDao().upsertProfile(
            ProfileEntity(
                profileId = PROFILE.value,
                serverId = SERVER.value,
                remoteUserId = "remote-user-1",
                username = "ada",
                displayName = "ada",
                role = "Listener",
                requiresReauthentication = false,
                lastUsedAt = null,
                isFixture = false,
                accessibleLibrariesJson = "[]",
                hasAllLibraryAccess = true,
                hasAllTagAccess = true,
                canDownload = false,
            ),
        )
    }

    private companion object {
        val SERVER = ServerId("srv_books")
        val PROFILE = ProfileId("prf_ada")
    }
}
