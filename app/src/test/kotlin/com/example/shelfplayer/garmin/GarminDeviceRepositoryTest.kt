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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class GarminDeviceRepositoryTest {
    @Test fun disconnectedRequestsAreDurableAndDuplicateTapsShareOneRequest() = runTest {
        val f = Fixture()
        f.sdk.providerState.value = GarminSdkState.DeviceDisconnected(GarminDeviceRef(1, "Watch"))
        val repository = f.repository(backgroundScope, StandardTestDispatcher(testScheduler))
        assertTrue(repository.forceSync() is AppResult.Success)
        assertTrue(repository.forceSync() is AppResult.Success)
        runCurrent()
        assertEquals(1, f.records.rows.value.count { it.kind == "command" })
        assertEquals("pending", GarminDeviceDocuments.read(f.records.rows.value.single().payload).optString("state"))
        assertTrue(f.sdk.sent.isEmpty())
    }

    @Test fun importsOriginalEventTimeBeforeAcknowledgingAndDuplicateImportIsIdempotent() = runTest {
        val f = Fixture()
        val repository = f.repository(backgroundScope, StandardTestDispatcher(testScheduler))
        f.answer(backgroundScope)
        f.sdk.respond = f.sdk.respond!!.let { answer ->
            { command ->
                if (command["t"] == "ack_events") assertEquals(1, f.records.rows.value.count { it.kind == "event" })
                answer(command)
            }
        }
        repository.start()
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(1_800_000_000_000L, f.records.rows.value.single { it.kind == "event" }.recordedAt)
        assertTrue(f.sdk.sent.any { it["t"] == "ack_events" })
        assertTrue(repository.refresh() is AppResult.Success)
        assertEquals(1, f.records.rows.value.count { it.kind == "event" })
    }

    @Test fun failedDurableEventImportNeverAcknowledgesTheWatch() = runTest {
        val f = Fixture()
        f.records.failEvents = true
        f.answer(backgroundScope)
        val repository = f.repository(backgroundScope, StandardTestDispatcher(testScheduler))
        repository.start()
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(f.sdk.sent.any { it["t"] == "events" })
        assertTrue(f.sdk.sent.none { it["t"] == "ack_events" })
        assertTrue(f.records.rows.value.none { it.kind == "event" })
    }

    @Test fun aClosedProviderIsNotRepeatedlyPolledAndRevokedTitlesAreNotDisplayed() = runTest {
        val f = Fixture()
        val repository = f.repository(backgroundScope, StandardTestDispatcher(testScheduler))
        repository.start()
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        advanceTimeBy(20_001)
        runCurrent()
        val count = f.sdk.sent.size
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(count, f.sdk.sent.size)
        f.records.put(
            listOf(
                com.example.shelfplayer.core.model.garmin.GarminRecord(
                    "profile",
                    "1",
                    "inventory",
                    "book",
                    """{"b":"book","title":"Revoked private title","state":"downloaded","done":1,"total":1}""",
                    1,
                ),
            ),
        )
        assertNull(repository.observe().first().downloads.single().title)
        f.locked = true
        assertTrue(repository.observe().first().downloads.isEmpty())
    }

    @Test fun anAcceptedDownloadMissingFromAFreshFullReportFailsSoTheUserCanRetry() = runTest {
        val f = Fixture()
        f.records.put(
            listOf(
                com.example.shelfplayer.core.model.garmin.GarminRecord(
                    "profile",
                    "1",
                    "command",
                    "old-download",
                    """{"type":"download","b":"book","state":"accepted"}""",
                    1,
                ),
            ),
        )
        f.answer(backgroundScope, event = false)
        val repository = f.repository(backgroundScope, StandardTestDispatcher(testScheduler))
        repository.start()
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        val command = f.records.rows.value.single { it.kind == "command" }
        assertEquals("failed", GarminDeviceDocuments.read(command.payload).optString("state"))
    }

    @Test fun sharedGoldenProviderMessagesAndRowsAreAccepted() {
        val stream = checkNotNull(javaClass.getResourceAsStream("/garmin/provider-v1.json"))
        val fixtures = org.json.JSONArray(stream.bufferedReader().use { it.readText() })
        repeat(fixtures.length()) { index ->
            val fixture = fixtures.getJSONObject(index)
            val value = fixture.getJSONObject("value")
            when (fixture.getString("name")) {
                "hello", "inventory", "events", "setup-rejected-content-type", "setup-rejected-schema" -> assertNotNull(
                    GarminProviderCodec.decode(raw(value)!!, "profile", "nonce", "request"),
                )
            }
            if (value.has("rows")) {
                val rows = value.getJSONArray("rows")
                repeat(rows.length()) { row ->
                    val parsed = raw(rows.get(row))
                    if (fixture.getString("name") == "events") {
                        assertNotNull(GarminProviderCodec.event(parsed))
                    } else {
                        assertNotNull(GarminProviderCodec.inventory(parsed))
                    }
                }
            }
        }
    }

    @Test fun phoneSetupReusesTheCurrentAccountWithoutCredentialEntry() = runTest {
        val f = Fixture()
        f.answer(backgroundScope, event = false)
        val original = f.sdk.respond!!
        f.sdk.respond = { command ->
            if (command["t"] == "hello") {
                backgroundScope.launch {
                    f.sdk.providerMessages.emit(
                        command +
                            mapOf(
                                "n" to "watch-nonce",
                                "paired" to true,
                                "configured" to true,
                                "caps" to listOf("reuse_login"),
                            ),
                    )
                }
            } else {
                original(command)
            }
        }
        val repository = f.repository(backgroundScope, StandardTestDispatcher(testScheduler))
        repository.start()
        runCurrent()
        assertTrue(
            repository.configure(
                "profile",
                "1",
                "example.invalid/sidecar/",
            ) is AppResult.Success,
        )
        val setup = f.sdk.sent.single { it["t"] == "reuse_login" }
        assertEquals("https://example.invalid/sidecar", setup["url"])
        assertEquals("fixture", setup["user"])
        assertTrue("password" !in setup && "session" !in setup && "token" !in setup)
        assertTrue(
            f.records.rows.value.none {
                it.payload.contains("one-time-secret")
            },
        )
        assertEquals("https://example.invalid/sidecar", repository.observe().first().sidecarUrl)
        f.locked = true
        assertEquals("", repository.observe().first().sidecarUrl)
        f.locked = false
        f.sdk.respond = original
        assertTrue(
            repository.configure("profile", "1", "https://example.invalid") is AppResult.Failure,
        )
        assertEquals(1, f.sdk.sent.count { it["t"] == "reuse_login" })
    }

    @Test fun reissuingAndCancellingPairingClearStalePhoneCodes() = runTest {
        val f = Fixture()
        f.answer(backgroundScope, event = false)
        val original = f.sdk.respond!!
        f.sdk.respond = { command ->
            when (command["t"]) {
                "hello" -> backgroundScope.launch {
                    f.sdk.providerMessages.emit(
                        command +
                            mapOf(
                                "n" to "watch-nonce",
                                "paired" to false,
                                "configured" to false,
                                "pairing" to false,
                                "caps" to listOf("setup", "cancel_pair"),
                            ),
                    )
                }

                "pair" -> backgroundScope.launch {
                    f.sdk.providerMessages.emit(
                        command + mapOf("n" to "watch-nonce", "ok" to false, "error" to "CONFIRM_ON_WATCH"),
                    )
                }

                else -> original(command)
            }
        }
        val repository = f.repository(backgroundScope, StandardTestDispatcher(testScheduler))
        repository.start()
        runCurrent()
        assertTrue(repository.pair() is AppResult.Success)
        assertTrue(repository.pair() is AppResult.Success)
        assertEquals(2, f.sdk.sent.count { it["t"] == "pair" })
        assertTrue(f.records.rows.value.any { GarminDeviceDocuments.read(it.payload).has("pairingCode") })
        assertTrue(repository.cancelPairing() is AppResult.Success)
        assertTrue(f.records.rows.value.none { GarminDeviceDocuments.read(it.payload).has("pairingCode") })
        assertTrue(f.sdk.sent.any { it["t"] == "cancel_pair" })
    }

    @Test fun setupCannotRedirectFromAStaleRenderedAccountOrWatch() = runTest {
        val f = Fixture()
        val repository = f.repository(backgroundScope, StandardTestDispatcher(testScheduler))
        assertTrue(
            repository.configure(
                "other-profile",
                "1",
                "https://example.invalid",
            ) is AppResult.Failure,
        )
        assertTrue(
            repository.configure("profile", "2", "https://example.invalid") is AppResult.Failure,
        )
        assertTrue(f.sdk.sent.isEmpty())
    }

    @Test fun phoneSetupPreservesSpecificProviderFailureReasons() = runTest {
        val f = Fixture()
        f.answer(backgroundScope, event = false)
        val original = f.sdk.respond!!
        var failure = "CONTENT_TYPE"
        f.sdk.respond = { command ->
            when (command["t"]) {
                "hello" -> backgroundScope.launch {
                    f.sdk.providerMessages.emit(
                        command +
                            mapOf(
                                "n" to "watch-nonce",
                                "paired" to true,
                                "configured" to true,
                                "caps" to listOf("reuse_login"),
                            ),
                    )
                }

                "reuse_login" -> backgroundScope.launch {
                    f.sdk.providerMessages.emit(
                        command + mapOf("n" to "watch-nonce", "ok" to false, "error" to failure),
                    )
                }

                else -> original(command)
            }
        }
        val repository = f.repository(backgroundScope, StandardTestDispatcher(testScheduler))
        repository.start()
        runCurrent()
        for ((reason, expected) in listOf(
            "CONTENT_TYPE" to "sidecar_content_type",
            "INCOMPATIBLE_SIDECAR" to "sidecar_schema",
            "ACCOUNT_MISMATCH" to "sidecar_account",
        )) {
            failure = reason
            val result = repository.configure("profile", "1", "https://example.invalid")
            assertTrue(result is AppResult.Failure)
            when (val error = result.error) {
                is com.example.shelfplayer.core.model.AppError.ApiCompatibility -> assertEquals(
                    expected,
                    error.missingCapability,
                )

                is com.example.shelfplayer.core.model.AppError.Authorization -> assertEquals(
                    expected,
                    error.missingPermission,
                )

                else -> error("Provider failure reason was lost")
            }
        }
        assertTrue(f.records.rows.value.none { it.payload.contains("secret") })
    }

    @Test fun freshWatchRequiresItsOwnLoginBeforeReuse() = runTest {
        val f = Fixture()
        f.answer(backgroundScope, event = false)
        val original = f.sdk.respond!!
        f.sdk.respond = { command ->
            if (command["t"] == "hello") {
                backgroundScope.launch {
                    f.sdk.providerMessages.emit(
                        command + mapOf(
                            "n" to "watch-nonce",
                            "paired" to true,
                            "configured" to false,
                            "caps" to listOf("reuse_login"),
                        ),
                    )
                }
            } else {
                original(command)
            }
        }
        val repository = f.repository(backgroundScope, StandardTestDispatcher(testScheduler))
        repository.start()
        runCurrent()
        val result = repository.configure("profile", "1", "example.invalid")
        assertTrue(result is AppResult.Failure)
        assertEquals(
            "provider_login",
            (result as AppResult.Failure).error.let {
                (it as com.example.shelfplayer.core.model.AppError.Authorization).missingPermission
            },
        )
        assertTrue(f.sdk.sent.none { it["t"] == "reuse_login" })
    }

    @Test fun explicitResumeRequeuesAnAcceptedRequestAndRefusesStaleOrCompletedRows() = runTest {
        val f = Fixture()
        f.books.value = listOf(resumeBook())
        val command = com.example.shelfplayer.core.model.garmin.GarminRecord(
            "profile",
            "1",
            "command",
            "accepted",
            """{"type":"download","b":"book","state":"accepted"}""",
            1,
        )
        val inventory = command.copy(
            kind = "inventory",
            recordId = "book",
            payload = """{"b":"book","state":"queued","done":1,"total":4}""",
        )
        f.records.put(listOf(command, inventory))
        val repository = f.repository(backgroundScope, StandardTestDispatcher(testScheduler))
        assertTrue(repository.resumeDownload("other", "1", "book") is AppResult.Failure)
        assertTrue(repository.resumeDownload("profile", "2", "book") is AppResult.Failure)
        assertEquals("accepted", GarminDeviceDocuments.read(f.records.rows.value.first().payload).optString("state"))
        assertTrue(repository.resumeDownload("profile", "1", "book") is AppResult.Success)
        assertTrue(repository.resumeDownload("profile", "1", "book") is AppResult.Success)
        val pending = f.records.rows.value.single { it.kind == "command" }
        assertEquals("accepted", pending.recordId)
        assertEquals("pending", GarminDeviceDocuments.read(pending.payload).optString("state"))
        f.records.put(listOf(inventory.copy(payload = """{"b":"book","state":"downloaded","done":4,"total":4}""")))
        assertTrue(repository.resumeDownload("profile", "1", "book") is AppResult.Failure)
        f.locked = true
        assertTrue(repository.resumeDownload("profile", "1", "book") is AppResult.Failure)
    }

    private fun resumeBook() = com.example.shelfplayer.core.model.library.Book(
        serverId = ServerId("server"), id = com.example.shelfplayer.core.model.LibraryItemId("book"),
        libraryId = com.example.shelfplayer.core.model.LibraryId("library"), title = "Fixture",
        subtitle = null, authors = emptyList(), narrators = emptyList(), seriesMemberships = emptyList(),
        duration = kotlin.time.Duration.ZERO, description = null, genres = emptyList(), tags = emptyList(),
        publishedYear = null, publisher = null, language = null, isbn = null, asin = null, isExplicit = false,
        isAbridged = false, coverPath = null, trackCount = 1, sizeBytes = 1, remoteUpdatedAt = null,
        addedAt = null, lastFetchedAt = java.time.Instant.EPOCH, progress = null,
        localAvailability = com.example.shelfplayer.core.model.library.LocalAvailability.Complete,
    )

    private fun raw(value: Any?): Any? = when (value) {
        is org.json.JSONObject -> value.keys().asSequence().associateWith { raw(value.get(it)) }
        is org.json.JSONArray -> (0 until value.length()).map { raw(value.get(it)) }
        org.json.JSONObject.NULL -> null
        else -> value
    }

    private class MemoryRecords : com.example.shelfplayer.domain.repository.GarminRecordRepository {
        val rows = MutableStateFlow(emptyList<com.example.shelfplayer.core.model.garmin.GarminRecord>())
        var failEvents = false
        override fun observe(profileId: String) = rows.map { values ->
            values.filter { it.profileId == profileId }.sortedByDescending { it.recordedAt }
        }
        override suspend fun records(profileId: String, deviceId: String, kind: String) =
            rows.value.filter { it.profileId == profileId && it.deviceId == deviceId && it.kind == kind }
        override suspend fun put(rows: List<com.example.shelfplayer.core.model.garmin.GarminRecord>) {
            check(!failEvents || rows.none { it.kind == "event" })
            val keys = rows.map { listOf(it.profileId, it.deviceId, it.kind, it.recordId) }.toSet()
            this.rows.value =
                this.rows.value.filter { listOf(it.profileId, it.deviceId, it.kind, it.recordId) !in keys } + rows
        }
        override suspend fun replace(
            profileId: String,
            deviceId: String,
            kind: String,
            rows: List<com.example.shelfplayer.core.model.garmin.GarminRecord>,
        ) {
            this.rows.value =
                this.rows.value.filter { it.profileId != profileId || it.deviceId != deviceId || it.kind != kind } +
                rows
        }
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
        val books = MutableStateFlow(emptyList<com.example.shelfplayer.core.model.library.Book>())
        private val library = proxy<LibraryRepository> { books }
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
                        AppResult.Failure(com.example.shelfplayer.core.model.AppError.Network("Offline"))
                    },
                    library,
                    proxy<BookmarkRepository> { error("No bookmark writes") },
                ),
            )
        val sdk = FakeSdk()
        val port = GarminProviderPort(sdk, access)
        val records = MemoryRecords()
        fun repository(scope: kotlinx.coroutines.CoroutineScope, dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
            GarminDeviceRepository(
                sdk,
                access,
                port,
                records,
                com.example.shelfplayer.core.testing.TestAppClock(),
                scope,
                dispatcher,
            )
        fun answer(scope: kotlinx.coroutines.CoroutineScope, event: Boolean = true) {
            sdk.respond = { command ->
                val type = command["t"] as String
                val body: Map<String, Any> = when (type) {
                    "hello" -> mapOf("configured" to true, "paired" to true)

                    "inventory" -> mapOf(
                        "rows" to emptyList<Any>(),
                        "more" to false,
                        "offset" to 0,
                        "at" to 1_800_000_001L,
                    )

                    "events" -> mapOf(
                        "rows" to if (event) {
                            listOf(
                                mapOf(
                                    "id" to "1",
                                    "p" to "profile",
                                    "b" to "book",
                                    "pos" to 12,
                                    "dur" to 120,
                                    "at" to 1_800_000_000L,
                                    "k" to "pause",
                                    "finished" to false,
                                ),
                            )
                        } else {
                            emptyList<Any>()
                        },
                        "more" to false,
                        "gap" to false,
                    )

                    else -> mapOf("ok" to true)
                }
                scope.launch { sdk.providerMessages.emit(command + body + ("n" to "watch-nonce")) }
            }
        }
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
        var respond: ((Map<String, Any>) -> Unit)? = null
        override fun start() = Unit
        override fun shutdown() = Unit
        override fun send(payload: Map<String, Any>): Boolean = error("Companion channel must remain separate")
        override fun sendProvider(payload: Map<String, Any>): Boolean {
            sent += payload
            respond?.invoke(payload)
            return true
        }
    }
}
