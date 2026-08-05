package com.example.shelfplayer.data.auth

import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
import com.example.shelfplayer.core.database.RoomDatabaseTransactionRunner
import com.example.shelfplayer.core.database.ShelfPlayerDatabase
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.datastore.AppSettingsSerializer
import com.example.shelfplayer.core.datastore.security.SessionTokenStore
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.ServerCandidate
import com.example.shelfplayer.core.model.ServerProbe
import com.example.shelfplayer.core.model.asFailure
import com.example.shelfplayer.core.model.auth.SessionIdentity
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.core.network.http.ServerUrlNormalizer
import com.example.shelfplayer.core.testing.RecordingLogSink
import com.example.shelfplayer.core.testing.TestAppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC AUTH-001 / AUTH-002 / AUTH-003 — sign-in, profile creation and session custody, against
 * a real database, a real settings store and a real (fake-ciphered) token store.
 *
 * Only the gateway is faked. Everything below it is the production code, because the requirements under
 * test are about what ends up written where — and a test with a fake database would not have caught the
 * one thing most worth catching: a profile row and a stored credential disagreeing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultAuthRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dispatcher = UnconfinedTestDispatcher()
    private val sink = RecordingLogSink()
    private val gateway = FakeAuthGateway()
    private val cipher = ReversibleTestCipher()

    @get:Rule
    val testName = TestName()

    private lateinit var database: ShelfPlayerDatabase
    private lateinit var settingsFile: File
    private lateinit var settings: AppSettingsDataSource
    private lateinit var tokens: SessionTokenProvider
    private lateinit var repository: DefaultAuthRepository

    @Before
    fun setUp() {
        val logger = RedactingLogger(sink, DefaultRedactor(RedactionPolicy.Default))
        database = Room.inMemoryDatabaseBuilder(context, ShelfPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Named after the test rather than after a timestamp: `@Before` runs once per test, so the name
        // is already unique, and the injected clock is deliberately frozen.
        settingsFile = File(context.cacheDir, "auth-test-${testName.methodName.hashCode()}.pb")
        settings = AppSettingsDataSource(
            dataStore = DataStoreFactory.create(
                serializer = AppSettingsSerializer(),
                scope = CoroutineScope(dispatcher),
            ) { settingsFile },
            logger = logger,
        )
        tokens = SessionTokenProvider(SessionTokenStore(context, cipher, dispatcher))
        repository = DefaultAuthRepository(
            gateway = gateway,
            urlNormalizer = ServerUrlNormalizer(),
            profileDao = database.profileDao(),
            transaction = RoomDatabaseTransactionRunner(database),
            sessionTokens = tokens,
            settings = settings,
            clock = TestAppClock(),
            logger = logger,
            ioDispatcher = dispatcher,
        )
    }

    @After
    fun tearDown() {
        database.close()
        settingsFile.delete()
        File(context.filesDir, "sessions").deleteRecursively()
    }

    // --- AUTH-001: probing before a password -------------------------------------------------------

    /** PRODUCT_SPEC AUTH-001 — a URL without a scheme is proposed as HTTPS, and the caller is told. */
    @Test
    fun `probing normalizes the address before it reaches the network`() = runTest {
        val result = repository.probeServer("books.example/")

        val candidate = assertIs<AppResult.Success<*>>(result).value as ServerCandidate
        assertEquals("https://books.example", candidate.serverUrl)
        assertTrue(candidate.wasSchemeAssumed)
        assertFalse(candidate.isCleartext)
        assertEquals(listOf("https://books.example"), gateway.probedUrls)
    }

    /**
     * A host that answers but is not Audiobookshelf must not be reported as a bad password later
     * (PRODUCT_SPEC 14.4: the user needs the right action).
     */
    @Test
    fun `a host that is not Audiobookshelf is a compatibility failure, not a login failure`() = runTest {
        gateway.probeResult = AppResult.Success(
            ServerProbe(
                isAudiobookshelf = false,
                serverVersion = null,
                isInitialized = true,
                authMethods = emptyList(),
            ),
        )

        val error = assertIs<AppResult.Failure>(repository.probeServer("https://not-abs.example")).error

        assertIs<AppError.ApiCompatibility>(error)
    }

    @Test
    fun `a server that has not finished setup cannot be signed in to`() = runTest {
        gateway.probeResult = AppResult.Success(
            ServerProbe(
                isAudiobookshelf = true,
                serverVersion = "2.36.0",
                isInitialized = false,
                authMethods = listOf("local"),
            ),
        )

        val error = assertIs<AppResult.Failure>(repository.probeServer("https://fresh.example")).error

        assertIs<AppError.Validation>(error)
    }

    @Test
    fun `an unusable address never reaches the network`() = runTest {
        val error = assertIs<AppResult.Failure>(repository.probeServer("https://host.example/?token=abc")).error

        assertIs<AppError.Validation>(error)
        assertTrue(gateway.probedUrls.isEmpty())
    }

    // --- AUTH-001/AUTH-002: sign-in and profile creation -------------------------------------------

    @Test
    fun `a successful sign-in stores the server, the profile, the token and the selection`() = runTest {
        val profile = assertIs<AppResult.Success<*>>(repository.signIn("books.example", "ada", "pw")).value
            as Profile

        val serverId = SessionIdentity.serverIdFor("https://books.example")
        assertEquals(serverId, profile.serverId)
        assertEquals("ada", profile.username)
        assertFalse(profile.isFixture)
        assertFalse(profile.requiresReauthentication)

        val storedServer = database.profileDao().findServer(serverId.value)
        assertNotNull(storedServer)
        assertEquals("https://books.example", storedServer.baseUrl)
        assertEquals("books.example", storedServer.displayName)

        val storedProfile = database.profileDao().findProfile(profile.id.value)
        assertNotNull(storedProfile)
        assertEquals("remote-user-1", storedProfile.remoteUserId)

        assertEquals("access-1", tokens.currentToken())
        assertEquals(profile.id, settings.activeProfileId.first())
    }

    /** PRODUCT_SPEC AUTH-001 — the password is used and dropped; only the token is stored. */
    @Test
    fun `no password reaches storage`() = runTest {
        repository.signIn("books.example", "ada", "correct-horse-battery-staple")

        val storedBytes = File(context.filesDir, "sessions").walkTopDown()
            .filter(File::isFile)
            .joinToString(separator = "\n") { it.readBytes().decodeToString() }
        assertFalse(storedBytes.contains("correct-horse-battery-staple"))
        assertFalse(settingsFile.readBytes().decodeToString().contains("correct-horse-battery-staple"))
    }

    /** PRODUCT_SPEC AUTH-001 — "given an invalid login, then no credential material is persisted". */
    @Test
    fun `a rejected sign-in writes nothing at all`() = runTest {
        gateway.signInResult = AppError.Authentication().asFailure()

        val result = repository.signIn("books.example", "ada", "wrong")

        assertIs<AppResult.Failure>(result)
        assertTrue(database.profileDao().observeProfiles().first().isEmpty())
        assertNull(database.profileDao().findServer(SessionIdentity.serverIdFor("https://books.example").value))
        assertNull(tokens.currentToken())
        assertNull(settings.activeProfileId.first())
    }

    /**
     * PRODUCT_SPEC AUTH-004 — reauthenticating must return to the same profile, or its downloads and
     * progress are orphaned.
     */
    @Test
    fun `signing the same account in again reuses its profile`() = runTest {
        val first = successfulSignIn()
        gateway.signInResult = AppResult.Success(FakeAuthGateway.session(accessToken = "access-2"))

        val second = successfulSignIn()

        assertEquals(first.id, second.id)
        assertEquals(1, database.profileDao().observeProfiles().first().size)
        assertEquals("access-2", tokens.currentToken())
    }

    /** PRODUCT_SPEC AUTH-002 — two accounts on one server are two profiles sharing one server row. */
    @Test
    fun `two accounts on one server become two profiles and one server`() = runTest {
        val ada = successfulSignIn()
        gateway.signInResult = AppResult.Success(
            FakeAuthGateway.session(accessToken = "access-grace", userId = "remote-user-2", username = "grace"),
        )

        val grace = successfulSignIn(username = "grace")

        assertEquals(ada.serverId, grace.serverId)
        assertEquals(2, database.profileDao().observeProfiles().first().size)
        assertEquals(setOf("ada", "grace"), database.profileDao().observeProfiles().first().map { it.username }.toSet())
    }

    /**
     * PRODUCT_SPEC SYNC-001 — a second sign-in must not blank the capability handshake the first one
     * established. Signing in is not a handshake, so it has nothing to put in those columns.
     */
    @Test
    fun `a second sign-in preserves the server's stored capability handshake`() = runTest {
        val ada = successfulSignIn()
        database.profileDao().updateServerCapabilities(
            serverId = ada.serverId.value,
            capabilitiesJson = """["Websocket"]""",
            authMethodsJson = """["local"]""",
            serverVersion = "2.36.0",
            detectedAt = 1_234,
        )

        gateway.signInResult = AppResult.Success(
            FakeAuthGateway.session(userId = "remote-user-2", username = "grace"),
        )
        successfulSignIn(username = "grace")

        val server = assertNotNull(database.profileDao().findServer(ada.serverId.value))
        assertEquals("""["Websocket"]""", server.capabilitiesJson)
        assertEquals(1_234L, server.capabilitiesDetectedAt)
        assertEquals("2.36.0", server.detectedVersion)
    }

    /** PRODUCT_SPEC AUTH-004 — a non-renewable session stores no refresh token to try later. */
    @Test
    fun `a session the server would not let us renew stores no refresh token`() = runTest {
        gateway.signInResult = AppResult.Success(FakeAuthGateway.session(refreshToken = null))

        val profile = successfulSignIn()

        assertNull(tokens.refreshTokenFor(profile.id))
        assertEquals("access-1", tokens.currentToken())
    }

    /** A renewable sign-in must not leave the previous session's refresh token behind. */
    @Test
    fun `signing in again without a refresh token discards the previous one`() = runTest {
        val profile = successfulSignIn()
        assertNotNull(tokens.refreshTokenFor(profile.id))

        gateway.signInResult = AppResult.Success(FakeAuthGateway.session(refreshToken = null))
        successfulSignIn()

        assertNull(tokens.refreshTokenFor(profile.id))
    }

    @Test
    fun `the role the server reported is what the profile records`() = runTest {
        gateway.signInResult = AppResult.Success(FakeAuthGateway.session(role = ProfileRole.Admin))

        assertEquals(ProfileRole.Admin, successfulSignIn().role)
    }

    // --- AUTH-004: restoring and ending a session -------------------------------------------------

    @Test
    fun `restoring a profile with a stored token reports an active session`() = runTest {
        val profile = successfulSignIn()

        assertEquals(AppResult.Success(SessionStatus.Active), repository.restoreSession(profile.id))
        assertEquals("access-1", tokens.currentToken())
    }

    /**
     * PRODUCT_SPEC AUTH-003 — a credential that can no longer be decrypted requires reauthentication
     * and must not crash. This is the invalidation path a real Keystore key takes after a lock-screen
     * change; only the *handling* is reachable here (see [TokenCipher]).
     */
    @Test
    fun `a credential that can no longer be decrypted marks the profile, keeping it saved`() = runTest {
        val profile = successfulSignIn()
        cipher.loseKey()

        val status = repository.restoreSession(profile.id)

        assertEquals(AppResult.Success(SessionStatus.ReauthenticationRequired), status)
        assertNull(tokens.currentToken())
        val stored = assertNotNull(database.profileDao().findProfile(profile.id.value))
        assertTrue(stored.requiresReauthentication, "the profile must be marked, not removed")
    }

    @Test
    fun `restoring a profile that is not saved is a validation failure`() = runTest {
        val error = assertIs<AppResult.Failure>(repository.restoreSession(ProfileId("prf_missing"))).error

        assertIs<AppError.Validation>(error)
    }

    @Test
    fun `restoring a usable session clears an earlier reauthentication mark`() = runTest {
        val profile = successfulSignIn()
        database.profileDao().setRequiresReauthentication(profile.id.value, required = true)

        repository.restoreSession(profile.id)

        assertFalse(assertNotNull(database.profileDao().findProfile(profile.id.value)).requiresReauthentication)
    }

    // --- AUTH-004: renewing a session -------------------------------------------------------------

    @Test
    fun `a renewable session is renewed with the stored refresh token and replaces both tokens`() = runTest {
        val profile = successfulSignIn()
        gateway.refreshResult = AppResult.Success(
            FakeAuthGateway.session(accessToken = "access-2", refreshToken = "refresh-2"),
        )

        val status = repository.renewSession(profile.id)

        assertEquals(AppResult.Success(SessionStatus.Active), status)
        assertEquals(
            listOf(FakeAuthGateway.Refresh("https://books.example", "refresh-1")),
            gateway.refreshCalls,
        )
        assertEquals("access-2", tokens.currentToken())
        // The server issues a new refresh token each time; keeping the old one would work once and then
        // fail at the following renewal.
        assertEquals("refresh-2", tokens.refreshTokenFor(profile.id)?.value)
    }

    @Test
    fun `renewing clears an earlier reauthentication mark`() = runTest {
        val profile = successfulSignIn()
        database.profileDao().setRequiresReauthentication(profile.id.value, required = true)

        repository.renewSession(profile.id)

        assertFalse(assertNotNull(database.profileDao().findProfile(profile.id.value)).requiresReauthentication)
    }

    /**
     * PRODUCT_SPEC AUTH-004 — the case the whole `isRenewable` flag exists for. A session the server
     * never issued a refresh token for is marked for reauthentication and never silently signed out.
     */
    @Test
    fun `a session with no refresh token is marked rather than renewed`() = runTest {
        gateway.signInResult = AppResult.Success(FakeAuthGateway.session(refreshToken = null))
        val profile = successfulSignIn()

        val status = repository.renewSession(profile.id)

        assertEquals(AppResult.Success(SessionStatus.ReauthenticationRequired), status)
        assertTrue(gateway.refreshCalls.isEmpty(), "there is no token to attempt a renewal with")
        assertTrue(assertNotNull(database.profileDao().findProfile(profile.id.value)).requiresReauthentication)
    }

    @Test
    fun `a refused renewal marks the profile and keeps everything else`() = runTest {
        val profile = successfulSignIn()
        gateway.refreshResult = AppError.Authentication().asFailure()

        val status = repository.renewSession(profile.id)

        assertEquals(AppResult.Success(SessionStatus.ReauthenticationRequired), status)
        val stored = assertNotNull(database.profileDao().findProfile(profile.id.value))
        assertTrue(stored.requiresReauthentication)
        assertEquals("ada", stored.username)
    }

    /** PRODUCT_SPEC AUTH-004 — "the app never loops login requests": one attempt per call, no retry. */
    @Test
    fun `a refused renewal is attempted exactly once`() = runTest {
        val profile = successfulSignIn()
        gateway.refreshResult = AppError.Authentication().asFailure()

        repository.renewSession(profile.id)

        assertEquals(1, gateway.refreshCalls.size)
    }

    @Test
    fun `renewing a profile that is not saved is a validation failure`() = runTest {
        val error = assertIs<AppResult.Failure>(repository.renewSession(ProfileId("prf_missing"))).error

        assertIs<AppError.Validation>(error)
        assertTrue(gateway.refreshCalls.isEmpty())
    }

    /** The trap AUTH-003 exists to avoid: clearing storage but leaving the process authenticated. */
    @Test
    fun `signing out clears the token in memory as well as on disk`() = runTest {
        val profile = successfulSignIn()

        repository.signOut(profile.id)

        assertNull(tokens.currentToken())
        assertNull(tokens.accessTokenFor(profile.id))
        assertNull(tokens.refreshTokenFor(profile.id))
    }

    /** PRODUCT_SPEC AUTH-004 — signing out keeps the profile so reauthenticating restores it. */
    @Test
    fun `signing out keeps the profile and marks it for reauthentication`() = runTest {
        val profile = successfulSignIn()

        repository.signOut(profile.id)

        val stored = assertNotNull(database.profileDao().findProfile(profile.id.value))
        assertTrue(stored.requiresReauthentication)
    }

    /** PRODUCT_SPEC 5.2 — the sign-out carries the credential of the profile being signed out. */
    @Test
    fun `signing out sends that profile's own token to its own server`() = runTest {
        val profile = successfulSignIn()

        repository.signOut(profile.id)

        assertEquals(
            listOf(FakeAuthGateway.SignOut("https://books.example", "access-1")),
            gateway.signOutCalls,
        )
    }

    /** A user who asked to sign out is signed out, even if their server is unreachable. */
    @Test
    fun `a server that refuses the sign-out does not keep the local session alive`() = runTest {
        val profile = successfulSignIn()
        gateway.signOutResult = AppError.Network().asFailure()

        assertIs<AppResult.Success<*>>(repository.signOut(profile.id))
        assertNull(tokens.currentToken())
    }

    // --- AUTH-002: removing a profile -------------------------------------------------------------

    @Test
    fun `removing a profile deletes its row, its credential and its selection`() = runTest {
        val profile = successfulSignIn()

        repository.removeProfile(profile.id)

        assertNull(database.profileDao().findProfile(profile.id.value))
        assertNull(tokens.accessTokenFor(profile.id))
        assertNull(settings.activeProfileId.first())
    }

    /** PRODUCT_SPEC AUTH-002 — "removing one profile does not remove another profile's data". */
    @Test
    fun `removing one profile leaves the other's credential and selection intact`() = runTest {
        val ada = successfulSignIn()
        gateway.signInResult = AppResult.Success(
            FakeAuthGateway.session(accessToken = "access-grace", userId = "remote-user-2", username = "grace"),
        )
        val grace = successfulSignIn(username = "grace")

        repository.removeProfile(ada.id)

        assertNull(database.profileDao().findProfile(ada.id.value))
        assertNotNull(database.profileDao().findProfile(grace.id.value))
        assertEquals("access-grace", tokens.accessTokenFor(grace.id)?.value)
        // grace was the active profile, and removing ada must not have disturbed that.
        assertEquals(grace.id, settings.activeProfileId.first())
    }

    // --- PRODUCT_SPEC 14.5: what the logs may contain ---------------------------------------------

    @Test
    fun `no log line carries a token, a password, a host or a username`() = runTest {
        val profile = successfulSignIn()
        repository.signOut(profile.id)
        repository.removeProfile(profile.id)

        // `sink` records lines *after* redaction, which is what actually reaches the device log.
        listOf("access-1", "refresh-1", "pw", "books.example", "ada").forEach { secret ->
            assertFalse(sink.text.contains(secret), "a log line leaked \"$secret\"")
        }
    }

    private suspend fun successfulSignIn(username: String = "ada"): Profile {
        val result = repository.signIn("books.example", username, "pw")
        return assertIs<AppResult.Success<Profile>>(result).value
    }
}
