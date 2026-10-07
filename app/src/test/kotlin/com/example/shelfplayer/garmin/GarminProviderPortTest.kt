package com.example.shelfplayer.garmin

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.lock.ProfileLockState
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.repository.BookmarkRepository
import com.example.shelfplayer.domain.repository.DownloadRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileLockRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.SyncAccountUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GarminProviderPortTest {
    @Test fun staleNonceAndRequestCannotCompleteAnotherRequest() = runTest {
        val f = Fixture()
        val hello = async { f.port.exchange(f.profile, "hello") }
        runCurrent()
        f.port.receive(f.reply())
        assertNotNull(hello.await())
        val inventory = async { f.port.exchange(f.profile, "inventory") }
        runCurrent()
        f.port.receive(f.reply() + ("n" to "old-nonce"))
        assertTrue(!inventory.isCompleted)
        f.port.receive(f.reply() + ("r" to "old-request"))
        assertTrue(!inventory.isCompleted)
        f.port.receive(f.reply())
        assertNotNull(inventory.await())
    }

    @Test fun conflatedProfileRoundTripRejectsDelayedResponse() = runTest {
        val f = Fixture()
        val hello = async { f.port.exchange(f.profile, "hello") }
        runCurrent()
        f.generation += 2
        f.port.receive(f.reply())
        advanceTimeBy(20_001)
        runCurrent()
        assertNull(hello.await())
        assertNull(f.port.nonce)
    }

    @Test fun differentWatchAndLockedAccountRejectResponsesAndNewSends() = runTest {
        val f = Fixture()
        val hello = async { f.port.exchange(f.profile, "hello") }
        runCurrent()
        f.sdk.providerState.value = GarminSdkState.AppAvailable(GarminDeviceRef(2, "Second watch"))
        f.port.receive(f.reply())
        assertTrue(!hello.isCompleted)
        f.port.reset()
        assertNull(hello.await())
        f.locked = true
        val count = f.sdk.sent.size
        assertNull(f.port.exchange(f.profile, "hello"))
        assertEquals(count, f.sdk.sent.size)
    }

    @Test fun completedDeviceWideDownloadDoesNotGrantARevokedProfileAccess() = runTest {
        val f = Fixture()
        assertTrue(f.access.eligible(f.profile).isEmpty()) // No authorized catalogue row.
        f.locked = true
        assertNull(f.access.current())
    }
    private class Fixture {
        val profile =
            Profile(
                ProfileId(
                    "profile",
                ),
                ServerId("server"), "fixture", "Fixture", ProfileRole.Listener, false, null, false, canDownload = true,
            )
        var generation = 1L
        var locked = false
        private val profiles = object : ProfileRepository {
            override fun observeProfiles(): Flow<List<Profile>> = MutableStateFlow(listOf(profile))
            override fun observeServers(): Flow<List<Server>> = MutableStateFlow(emptyList())
            override fun observeActiveProfile(): Flow<Profile?> = MutableStateFlow(profile)
            override suspend fun activeProfileId(): ProfileId = profile.id
            override fun activeProfileGeneration(): Long = generation
            override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> = error("Read only")
        }
        private val locks = proxy<ProfileLockRepository> { method ->
            when {
                method.startsWith("isLocked") -> locked
                method.startsWith("observeLockState") -> MutableStateFlow<ProfileLockState>(ProfileLockState.Unlocked)
                else -> error("Unexpected lock call")
            }
        }
        private val library =
            proxy<LibraryRepository> { MutableStateFlow(emptyList<com.example.shelfplayer.core.model.library.Book>()) }
        private val downloads =
            proxy<DownloadRepository> {
                MutableStateFlow(setOf(com.example.shelfplayer.core.model.LibraryItemId("book")))
            }
        val access =
            GarminDeviceAccess(
                profiles,
                locks,
                library,
                downloads,
                SyncAccountUseCase(
                    profiles,
                    proxy<AuthRepository> {
                        error("No server calls")
                    },
                    library,
                    proxy<BookmarkRepository> { error("No bookmark writes") },
                ),
            )
        val sdk = FakeSdk()
        val port = GarminProviderPort(sdk, access)
        fun reply(): Map<String, Any> = sdk.sent.last().toMutableMap().apply { put("n", "watch-nonce") }
        private inline fun <reified T> proxy(crossinline call: (String) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
                call(method.name)
            } as T
    }
    private class FakeSdk : GarminMobileSdk {
        override val state = MutableStateFlow<GarminSdkState>(GarminSdkState.NoDevice)
        override val providerState =
            MutableStateFlow<GarminSdkState>(GarminSdkState.AppAvailable(GarminDeviceRef(1, "Watch")))
        override val incomingMessages = MutableSharedFlow<Any>()
        override val providerMessages = MutableSharedFlow<Any>()
        override val lastSendStatus = MutableStateFlow<String?>(null)
        val sent = mutableListOf<Map<String, Any>>()
        override fun start() = Unit
        override fun shutdown() = Unit
        override fun send(payload: Map<String, Any>): Boolean = error("Companion channel must remain separate")
        override fun sendProvider(payload: Map<String, Any>): Boolean {
            sent += payload
            return true
        }
    }
}
