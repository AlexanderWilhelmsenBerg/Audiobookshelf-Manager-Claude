package com.example.shelfplayer.garmin

import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.lock.ProfileLockState
import com.example.shelfplayer.domain.repository.ProfileLockRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.playback.PlaybackUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class GarminBridgeLifecycleTest {
    @Test
    fun lockCancelsPendingPrivateDeliveryAndOldAckCannotReleaseIt() = runTest {
        val f = Fixture(this)
        f.ready()
        val snapshot = f.sdk.sent.last()
        f.lock.value = ProfileLockState.Locked(f.profile.id)
        runCurrent()
        assertEquals("clear_state", f.sdk.sent.last()["t"])
        f.ack(snapshot)
        assertEquals("clear_state", f.sdk.sent.last()["t"])
        f.ack(f.sdk.sent.last())
        val count = f.sdk.sent.size
        f.hello()
        assertTrue(f.sdk.sent.drop(count).none { it["t"] == "snapshot" })
        f.bridge.stop()
    }

    @Test
    fun synchronousGenerationGuardClearsEvenWhenSelectionFlowConflates() = runTest {
        val f = Fixture(this)
        f.ready()
        f.generation += 2 // A -> B -> A can disappear from observeActiveProfile.
        f.ack(f.sdk.sent.last())
        assertEquals("clear_state", f.sdk.sent.last()["t"])
        assertTrue(f.sdk.sent.last()["p"] is Map<*, *>)
        f.bridge.stop()
    }

    @Test
    fun unknownOwnerNoCurrentBookAndRestartFailClosedWithoutPlayerCommands() = runTest {
        val f = Fixture(this)
        f.playbackState.value = f.playbackState.value.copy(ownerProfileId = null)
        f.ready()
        assertTrue(f.sdk.sent.none { it["t"] == "snapshot" })
        f.playbackState.value = PlaybackUiState.Idle
        runCurrent()
        f.bridge.start()
        assertEquals(1, f.sdk.starts)
        f.bridge.stop()
        f.bridge.start()
        runCurrent()
        assertEquals("hello", f.sdk.sent.last()["t"])
        assertEquals(2, f.sdk.starts)
        f.bridge.stop()
    }

    @Test
    fun explicitPhoneSyncRetriesCompanionAndRechecksPrivacyBeforeNewHandshake() = runTest {
        val f = Fixture(this)
        f.ready()
        f.lock.value = ProfileLockState.Locked(f.profile.id)
        f.bridge.forceSync()
        runCurrent()
        assertEquals("hello", f.sdk.sent.last()["t"])
        f.hello()
        assertEquals("clear_state", f.sdk.sent.last()["t"])
        f.ack(f.sdk.sent.last())
        assertTrue(f.sdk.sent.takeLast(2).none { it["t"] == "snapshot" })
        f.bridge.stop()
    }

    private class Fixture(private val scope: TestScope) {
        val profile = Profile(
            ProfileId("profile-a"),
            ServerId("server-a"),
            "fixture",
            "Fixture",
            ProfileRole.Listener,
            false,
            Instant.EPOCH,
            isFixture = true,
        )
        val active = MutableStateFlow<Profile?>(profile)
        val lock = MutableStateFlow<ProfileLockState>(ProfileLockState.Unlocked)
        var generation = 1L
        val playbackState = MutableStateFlow(
            PlaybackUiState.Idle.copy(
                bookId = LibraryItemId("book-a"),
                title = "Private fixture",
                ownerProfileId = profile.id,
                position = 12.seconds,
                duration = 100.seconds,
            ),
        )
        val playback = object : GarminPlaybackSource {
            override val state = playbackState
        }
        private val profiles = object : ProfileRepository {
            override fun observeProfiles(): Flow<List<Profile>> = MutableStateFlow(listOf(profile))
            override fun observeServers(): Flow<List<Server>> = MutableStateFlow(emptyList())
            override fun observeActiveProfile(): Flow<Profile?> = active
            override suspend fun activeProfileId(): ProfileId? = active.value?.id
            override fun activeProfileGeneration(): Long = generation
            override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> = error("No selection writes")
        }
        private val locks = proxy<ProfileLockRepository> { method ->
            when {
                method.startsWith("observeLockState") -> lock
                method.startsWith("isLocked") -> lock.value != ProfileLockState.Unlocked
                else -> error("Unexpected lock operation: $method")
            }
        }
        val sdk = FakeSdk()
        private val clock = object : AppClock {
            override fun now(): Instant = Instant.ofEpochMilli(1_700_000_000_000L + scope.testScheduler.currentTime)
            override fun elapsed(): Duration = scope.testScheduler.currentTime.milliseconds
        }
        val bridge = GarminBridge(
            sdk, GarminSnapshotProjector(clock), GarminPrivacyPolicy(),
            GarminDeliverySession(GarminMessageCodec(clock), GarminSnapshotSendPolicy(), clock),
            playback, profiles, locks, scope.backgroundScope, StandardTestDispatcher(scope.testScheduler),
        )

        suspend fun ready() {
            bridge.start()
            scope.runCurrent()
            hello()
            ack(sdk.sent.last())
        }
        suspend fun hello() {
            sdk.incomingMessages.emit(
                envelope("hello") + mapOf("p" to mapOf("majors" to listOf(1), "caps" to listOf("ordered_state"))),
            )
            scope.runCurrent()
        }
        suspend fun ack(message: Map<String, Any>) {
            sdk.incomingMessages.emit(
                envelope(if (message["t"] == "clear_state") "clear_ack" else "snapshot_ack") +
                    mapOf(
                        "r" to message["id"],
                        "s" to message["s"],
                        "n" to message["n"],
                        "p" to mapOf("accepted" to true),
                    ),
            )
            scope.runCurrent()
        }
        private fun envelope(type: String): Map<String, Any?> =
            mapOf("v" to 1, "t" to type, "id" to "watch-1", "ts" to 1_700_000_000_000L, "p" to emptyMap<String, Any>())

        private inline fun <reified T> proxy(crossinline call: (String) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
                call(method.name)
            } as T
    }

    private class FakeSdk : GarminMobileSdk {
        override val state = MutableStateFlow<GarminSdkState>(GarminSdkState.Starting)
        override val incomingMessages = MutableSharedFlow<Any>(extraBufferCapacity = 32)
        override val lastSendStatus = MutableStateFlow<String?>(null)
        val sent = mutableListOf<Map<String, Any>>()
        var starts = 0
        override fun start() {
            starts++
            state.value = GarminSdkState.AppAvailable(GarminDeviceRef(1, "Fixture watch"))
        }
        override fun send(payload: Map<String, Any>): Boolean = sent.add(payload)
        override fun shutdown() {
            state.value = GarminSdkState.Unavailable("SDK_STOPPED")
        }
    }
}
