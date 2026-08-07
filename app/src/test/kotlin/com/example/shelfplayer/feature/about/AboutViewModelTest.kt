package com.example.shelfplayer.feature.about

import app.cash.turbine.test
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerCapabilities
import com.example.shelfplayer.core.model.ServerCapability
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.StorageDiagnostics
import com.example.shelfplayer.core.model.realtime.RealtimeEvent
import com.example.shelfplayer.core.model.realtime.RealtimeStatus
import com.example.shelfplayer.core.testing.MainDispatcherRule
import com.example.shelfplayer.domain.realtime.RealtimeUpdates
import com.example.shelfplayer.domain.repository.CapabilityRepository
import com.example.shelfplayer.domain.repository.DiagnosticsRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.ObserveServerDiagnosticsUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC SYNC-001 / SET-002 — the readings, which moved here out of Settings.
 *
 * The assertions are the ones the settings screen used to carry. They moved with the thing they cover,
 * so the coverage did not quietly stay behind on a screen that no longer renders any of it.
 */
class AboutViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val profiles = FakeProfiles()
    private val diagnostics = FakeDiagnostics()
    private val capabilities = FakeCapabilities()

    private fun viewModel() = AboutViewModel(
        diagnostics = diagnostics,
        observeServerDiagnostics = ObserveServerDiagnosticsUseCase(profiles, capabilities, StubRealtime()),
    )

    /**
     * PRODUCT_SPEC SET-002 (Privacy/diagnostics) — the counts that used to need `adb … sqlite3`.
     *
     * The pair that matters is stored-against-visible. "Unauthorized libraries never appear" is really
     * "unauthorized rows were never written", and a screen that hides a row looks exactly like one that
     * never had it — so the difference between the two numbers is the only thing that can tell them apart.
     */
    @Test
    fun `storage counts distinguish what is stored from what this profile can see`() = runTest {
        diagnostics.emit(
            StorageDiagnostics(
                serversStored = 1,
                profilesStored = 2,
                storedCredentials = 2,
                librariesStored = 2,
                librariesAccessible = 1,
                booksStored = 490,
                booksAccessible = 188,
                booksSoftDeleted = 3,
            ),
        )

        viewModel().uiState.test {
            val storage = awaitItem().storage
            assertEquals(1, storage.serversStored, "two accounts on one server count once")
            assertEquals(2, storage.librariesStored)
            assertEquals(1, storage.librariesAccessible)
            assertEquals(490, storage.booksStored)
            assertEquals(188, storage.booksAccessible)
        }
    }

    /**
     * PRODUCT_SPEC SYNC-001 — "the compatibility result is visible in diagnostics".
     *
     * The distinction the screen exists to draw: a handshake that confirmed nothing and a handshake
     * that never ran produce the same empty set, and only one of them means "this server cannot do it".
     */
    @Test
    fun `diagnostics distinguish an unchecked server from one that confirmed nothing`() = runTest {
        val model = viewModel()

        model.uiState.test {
            assertFalse(assertNotNull(awaitItem().server).hasHandshake, "no probe has run yet")

            capabilities.stored.value = ServerCapabilities(
                serverId = ServerId("srv_books"),
                serverVersion = "2.36.0",
                supported = setOf(ServerCapability.Websocket),
                authMethods = listOf("local"),
            )

            val server = assertNotNull(awaitItem().server)
            assertTrue(server.hasHandshake)
            assertEquals("2.36.0", server.reportedVersion)
            assertEquals(listOf("local"), server.authMethods)
            assertEquals(setOf(ServerCapability.Websocket), server.confirmed)
        }
    }

    /** Every capability is listed, so "not confirmed" is visible rather than merely absent. */
    @Test
    fun `every known capability is reported, confirmed or not`() = runTest {
        capabilities.stored.value = ServerCapabilities(
            serverId = ServerId("srv_books"),
            serverVersion = null,
            supported = setOf(ServerCapability.Websocket),
        )

        viewModel().uiState.test {
            val server = assertNotNull(awaitItem().server)
            assertEquals(ServerCapability.entries.size, server.allCapabilities.size)
            assertEquals(1, server.allCapabilities.count { (_, confirmed) -> confirmed })
        }
    }

    /** Zeroes before the first read would look like facts, so the screen says it is still reading. */
    @Test
    fun `the counts are not shown until they have been read`() = runTest {
        assertFalse(AboutUiState().isLoaded)

        viewModel().uiState.test {
            assertTrue(awaitItem().isLoaded)
        }
    }

    /** PRODUCT_SPEC SYNC-001 — a handshake the test dictates, including "there has not been one". */
    private class FakeCapabilities : CapabilityRepository {
        val stored = MutableStateFlow<ServerCapabilities?>(null)

        override fun observeCapabilities(serverId: ServerId): Flow<ServerCapabilities?> = stored

        override suspend fun capabilities(serverId: ServerId): ServerCapabilities? = stored.value

        override suspend fun handshake(profileId: ProfileId): AppResult<ServerCapabilities> =
            AppResult.Failure(AppError.Network())
    }

    /** The socket is not the subject here; the diagnostics row just has to read whatever it reports. */
    private class StubRealtime : RealtimeUpdates {
        override val status = MutableStateFlow(RealtimeStatus.Idle)

        override fun events(profileId: ProfileId): Flow<RealtimeEvent> = emptyFlow()
    }

    private class FakeDiagnostics : DiagnosticsRepository {
        private val storage = MutableStateFlow(StorageDiagnostics())

        fun emit(value: StorageDiagnostics) {
            storage.value = value
        }

        override fun observeStorage(): Flow<StorageDiagnostics> = storage
    }

    private class FakeProfiles : ProfileRepository {
        private val active = MutableStateFlow<Profile?>(
            Profile(
                id = ProfileId("prf_ada"),
                serverId = ServerId("srv_books"),
                username = "ada",
                displayName = "ada",
                role = ProfileRole.Listener,
                requiresReauthentication = false,
                lastUsedAt = Instant.EPOCH,
                isFixture = false,
            ),
        )

        override fun observeProfiles(): Flow<List<Profile>> = MutableStateFlow(emptyList())

        override fun observeServers(): Flow<List<Server>> = MutableStateFlow(emptyList())

        override fun observeActiveProfile(): Flow<Profile?> = active

        override suspend fun activeProfileId(): ProfileId? = active.value?.id

        override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> = AppResult.Success(Unit)
    }
}
