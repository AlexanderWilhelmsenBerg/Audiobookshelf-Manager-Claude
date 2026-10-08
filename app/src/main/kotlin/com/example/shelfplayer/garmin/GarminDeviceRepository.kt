package com.example.shelfplayer.garmin

import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.garmin.GarminRecord
import com.example.shelfplayer.domain.repository.GarminRecordRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** SET-002, DL-001/003, AUTH-002/005, SYNC-001/002. SDK transport writes Room; UI reads only Room. */
@Singleton
internal class GarminDeviceRepository @Inject constructor(
    private val sdk: GarminMobileSdk,
    private val access: GarminDeviceAccess,
    private val port: GarminProviderPort,
    private val store: GarminRecordRepository,
    private val clock: AppClock,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:Dispatcher(ShelfDispatcher.MainImmediate) private val dispatcher: CoroutineDispatcher,
) {
    private val operations = Mutex()
    private val inventoryReader = GarminInventoryReader(port)
    private var jobs: List<Job> = emptyList()
    private var previous: Pair<Profile, GarminDeviceRef>? = null
    private var watchAwake = false

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs = listOf(
            scope.launch(dispatcher) { sdk.providerMessages.collect(::watchMessage) },
            scope.launch(dispatcher) {
                combine(sdk.providerState, access.profiles.observeActiveProfile(), access.locks.observeLockState()) {
                        state,
                        profile,
                        _,
                    ->
                    state to
                        profile
                }
                    .collect { (state, profile) -> connection(state, profile) }
            },
            scope.launch(dispatcher) {
                while (isActive) {
                    guarded { poll() }
                    delay(POLL_MS)
                }
            },
        )
    }

    private suspend fun watchMessage(raw: Any) {
        val signal = raw as? Map<*, *> ?: return port.receive(raw)
        val nonce = GarminProviderCodec.text(signal["n"], GarminProviderCodec.MAX_CORRELATION)
        if (GarminProviderCodec.number(signal["v"]) != 1L || nonce == null) {
            port.receive(raw)
            return
        }
        when (signal["t"]) {
            "ready" -> {
                val owner = GarminProviderCodec.text(signal["p"], GarminProviderCodec.MAX_CORRELATION)
                val active = access.current()
                if (owner != null && owner != active?.id?.value) {
                    sdk.sendProvider(
                        mapOf(
                            "v" to 1,
                            "t" to "redact",
                            "r" to UUID.randomUUID().toString(),
                            "p" to owner,
                            "n" to nonce,
                        ),
                    )
                }
                watchAwake = true
                port.reset()
                scope.launch(dispatcher) { guarded { poll() } }
            }

            "sleeping" -> if (nonce == port.nonce) {
                watchAwake = false
                port.reset()
            }

            else -> port.receive(raw)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<GarminDeviceUi> = access.profiles.observeActiveProfile().flatMapLatest { profile ->
        if (profile == null) {
            flowOf(GarminDeviceUi())
        } else {
            combine(
                store.observe(profile.id.value),
                access.library.observeAccessibleBooks(profile.id),
                access.downloads.observeCompletedFor(profile.id),
                access.locks.observeLockState(),
            ) { rows, books, completed, _ ->
                if (!access.allowed(profile, access.profiles.activeProfileGeneration())) {
                    GarminDeviceUi()
                } else {
                    GarminDeviceDocuments.ui(
                        rows,
                        books.filter {
                            it.serverId == profile.serverId
                        },
                        completed,
                        profile.canDownload,
                    ).copy(username = profile.username, profileId = profile.id.value)
                }
            }
        }
    }

    suspend fun pair(): AppResult<Unit> = guarded {
        watchAwake = true
        operations.withLock {
            val profile = access.current() ?: return@withLock denied()
            val device = available() ?: return@withLock unavailable()
            val hello = port.exchange(profile, "hello") ?: return@withLock unavailable()
            if (hello["configured"] != true && !hasCapability(hello, "setup")) {
                return@withLock AppResult.Failure(
                    AppError.ApiCompatibility("Configure Sidecar on the watch first."),
                )
            }
            val code = UUID.randomUUID().toString().filter(
                Char::isDigit,
            ).take(PAIR_CODE_LENGTH).padEnd(PAIR_CODE_LENGTH, '0')
            metadata(profile, device) {
                put("pairingCode", code)
                put("pairingAt", clock.now().toEpochMilli())
            }
            val result = port.exchange(profile, "pair", mapOf("code" to code))
            when {
                result?.get("ok") == true -> {
                    metadata(profile, device) {
                        remove("pairingCode")
                        put("paired", true)
                    }
                    AppResult.Success(Unit)
                }

                result?.get("error") == "CONFIRM_ON_WATCH" -> AppResult.Success(Unit)

                else -> {
                    metadata(profile, device) { remove("pairingCode") }
                    if (result == null) unavailable() else rejected()
                }
            }
        }
    }

    suspend fun cancelPairing(): AppResult<Unit> = guarded {
        operations.withLock {
            val profile = access.current() ?: return@withLock denied()
            val device = selected() ?: return@withLock unavailable()
            metadata(profile, device) {
                remove("pairingCode")
                remove("pairingAt")
            }
            if (available() != null) {
                val hello = port.exchange(profile, "hello")
                if (hello != null && hasCapability(hello, "cancel_pair")) port.exchange(profile, "cancel_pair")
            }
            AppResult.Success(Unit)
        }
    }

    /** One-time credentials never enter the durable command queue or Room metadata. */
    suspend fun configure(
        owner: String?,
        target: String?,
        url: String,
        user: String,
        password: String,
    ): AppResult<Unit> = guarded {
        val destination =
            GarminSetupPolicy.url(url)
                ?: return@guarded AppResult.Failure(AppError.Validation("Enter the HTTPS Sidecar base URL."))
        if (!GarminSetupPolicy.credentials(user, password)) return@guarded rejected()
        operations.withLock {
            val generation = access.profiles.activeProfileGeneration()
            val profile = access.current() ?: return@withLock denied()
            val device = available() ?: return@withLock unavailable()
            if (!setupDestinationAllowed(owner, target, profile, generation, device)) {
                return@withLock denied()
            }
            val hello = port.exchange(profile, "hello") ?: return@withLock unavailable()
            if (hello["paired"] != true) {
                return@withLock AppResult.Failure(
                    AppError.Authorization(
                        "Pair the provider with this phone first.",
                        missingPermission = "provider_pairing",
                    ),
                )
            }
            if (!hasCapability(hello, "setup")) {
                return@withLock AppResult.Failure(
                    AppError.ApiCompatibility(
                        "Update the watch provider to support phone setup.",
                        missingCapability = "provider_setup",
                    ),
                )
            }
            if (port.exchange(profile, "authorize")?.get("ok") != true) return@withLock denied()
            if (!setupAllowed(profile, generation, device)) return@withLock denied()
            val reply =
                port.exchange(
                    profile,
                    "setup",
                    mapOf("url" to destination, "user" to user, "password" to password),
                    timeoutMs = SETUP_TIMEOUT_MS,
                )
                    ?: return@withLock unavailable()
            if (reply["ok"] != true) return@withLock setupError(reply["error"])
            if (!setupAllowed(profile, generation, device)) return@withLock denied()
            metadata(profile, device) { put("configured", true) }
            AppResult.Success(Unit)
        }
    }

    private suspend fun setupDestinationAllowed(
        owner: String?,
        target: String?,
        profile: Profile,
        generation: Long,
        device: GarminDeviceRef,
    ): Boolean = profile.id.value == owner && device.identifier.toString() == target &&
        setupAllowed(profile, generation, device)

    private suspend fun setupAllowed(profile: Profile, generation: Long, device: GarminDeviceRef): Boolean =
        access.allowed(profile, generation) && available()?.identifier == device.identifier

    private fun hasCapability(hello: Map<*, *>, capability: String) =
        (hello["caps"] as? List<*>)?.contains(capability) == true
    private fun setupError(error: Any?): AppResult<Unit> = AppResult.Failure(
        when (error) {
            "CONTENT_TYPE" -> AppError.ApiCompatibility(
                "Sidecar returned an unexpected content type. Check its URL and reverse proxy.",
                missingCapability = "sidecar_content_type",
            )

            "INCOMPATIBLE_SIDECAR" -> AppError.ApiCompatibility(
                "Sidecar returned incompatible health or login data.",
                missingCapability = "sidecar_schema",
            )

            "LOGIN_REJECTED" -> AppError.Authentication(
                "Sidecar rejected the username or password.",
                requiresReauthentication = false,
            )

            "ACCOUNT_MISMATCH" -> AppError.Authorization(
                "Retained watch books belong to another Sidecar account. Sync them before resetting the watch app.",
                missingPermission = "sidecar_account",
            )

            else -> AppError.Network("Could not configure Sidecar. Check the connection and try again.")
        },
    )

    suspend fun queueDownload(book: String): AppResult<Unit> = enqueue("download", book)
    suspend fun forceSync(): AppResult<Unit> = enqueue("sync", null)
    suspend fun refresh(): AppResult<Unit> = guarded {
        watchAwake = true
        poll()
    }

    private suspend fun enqueue(type: String, book: String?): AppResult<Unit> = guarded {
        operations.withLock {
            val profile = access.current() ?: return@withLock denied()
            val generation = access.profiles.activeProfileGeneration()
            if (book != null && access.eligible(profile).none { it.id.value == book }) return@withLock denied()
            val device = selected() ?: return@withLock unavailable()
            if (!access.allowed(profile, generation)) return@withLock denied()
            val existing = store.records(profile.id.value, device.identifier.toString(), COMMAND)
            if (existing.any { row ->
                    GarminDeviceDocuments.read(row.payload).let {
                        it.optString("type") == type &&
                            it.optString("b") == book.orEmpty() &&
                            it.optString("state") in setOf("pending", "accepted")
                    }
                }
            ) {
                return@withLock AppResult.Success(Unit)
            }
            val document = JSONObject().put("type", type).put("b", book.orEmpty()).put("state", "pending")
            store.put(listOf(record(profile, device, COMMAND, UUID.randomUUID().toString(), document.toString())))
            watchAwake = true
            scope.launch(dispatcher) { guarded { poll() } }
            AppResult.Success(Unit)
        }
    }

    private suspend fun connection(state: GarminSdkState, profile: Profile?) {
        val old = previous
        val next = selected()
        if (old != null &&
            scopeLost(profile, old.first)
        ) {
            val nonce = port.nonce
            if (nonce != null &&
                available()?.identifier == old.second.identifier
            ) {
                sdk.sendProvider(
                    mapOf(
                        "v" to 1,
                        "t" to "redact",
                        "r" to UUID.randomUUID().toString(),
                        "p" to old.first.id.value,
                        "n" to nonce,
                    ),
                )
            }
        }
        port.reset()
        watchAwake = state is GarminSdkState.AppAvailable
        previous = if (profile != null && next != null) profile to next else null
        if (profile == null) return
        guarded {
            operations.withLock {
                // Persisted green dots are cleared on every SDK/profile transition, including cold start.
                store.observe(profile.id.value).let { flow ->
                    val rows = flow.first().filter { it.kind == DEVICE }
                    store.put(
                        rows.map {
                            it.copy(
                                payload = GarminDeviceDocuments.read(
                                    it.payload,
                                ).put("connected", false).put("ready", false).put("selected", false).toString(),
                            )
                        },
                    )
                }
                if (next != null) {
                    metadata(profile, next) {
                        val connected = state.connected()
                        put("selected", true)
                        put("name", next.name)
                        put("connected", connected)
                        put("ready", state is GarminSdkState.AppAvailable)
                        if (connected) put("lastConnected", clock.now().toEpochMilli())
                    }
                }
                AppResult.Success(Unit)
            }
        }
    }

    private suspend fun poll(): AppResult<Unit> = operations.withLock {
        val profile = access.current() ?: return@withLock denied()
        val device = available() ?: return@withLock unavailable()
        if (!watchAwake) return@withLock unavailable()
        val hello = port.exchange(profile, "hello")
        if (hello == null) {
            watchAwake = false
            return@withLock unavailable()
        }
        val paired = hello["paired"] == true
        metadata(profile, device) {
            put("paired", paired)
            put("configured", hello["configured"] == true)
            if (paired || hello["pairing"] == false ||
                clock.now().toEpochMilli() - optLong("pairingAt") >= PAIR_TIMEOUT_MS
            ) {
                remove("pairingCode")
            }
        }
        if (!paired || hello["configured"] != true) return@withLock rejected()
        if (port.exchange(profile, "authorize")?.get("ok") != true) return@withLock rejected()
        store.records(profile.id.value, device.identifier.toString(), COMMAND).forEach { command ->
            sendCommand(profile, command)
        }
        reconcile()
    }

    private suspend fun sendCommand(profile: Profile, command: GarminRecord) {
        val document = GarminDeviceDocuments.read(command.payload)
        if (document.optString("state") != "pending") return
        val type = document.optString("type")
        val book = document.optString("b")
        if (type == "download" && access.eligible(profile).none { it.id.value == book }) {
            store.put(listOf(command.copy(payload = document.put("state", "failed").toString())))
            return
        }
        val fields = if (type == "download") mapOf("b" to book) else emptyMap()
        val reply = port.exchange(profile, type, fields, command.recordId) ?: return
        document.put("state", if (reply["ok"] == true) "accepted" else "failed")
        store.put(listOf(command.copy(payload = document.toString())))
    }

    // Guard returns prevent partial inventory or a stale profile from becoming canonical cache.
    @Suppress("ReturnCount")
    private suspend fun reconcile(): AppResult<Unit> {
        val profile = access.current() ?: return denied()
        val device = available() ?: return unavailable()
        if (port.nonce == null) return unavailable()
        val generation = access.profiles.activeProfileGeneration()
        val previousMetadata = store.records(
            profile.id.value,
            device.identifier.toString(),
            DEVICE,
        ).firstOrNull()?.let {
            GarminDeviceDocuments.read(it.payload)
        }
        val previousSync = previousMetadata?.optLong("lastSynced")
        val refreshPending = previousMetadata?.optBoolean("accountRefreshPending") == true
        val report = when (val result = inventoryReader.read(profile)) {
            is AppResult.Failure -> return result
            is AppResult.Success -> result.value
        }
        val rows = report.rows.map { record(profile, device, INVENTORY, it.id, GarminDeviceDocuments.encode(it)) }
        val syncTime = report.synced
        if (!access.allowed(profile, generation)) return denied()
        store.replace(profile.id.value, device.identifier.toString(), INVENTORY, rows)
        metadata(profile, device) {
            put("inventoryAt", report.at)
            if (syncTime !=
                null
            ) {
                put("lastSynced", syncTime)
                if (syncTime != previousSync) put("accountRefreshPending", true)
            }
        }
        reconcileCommands(profile, device, rows, report.completedRequest, report.failedRequest)
        val events = importEvents(profile, device, generation)
        if (events is AppResult.Failure) return events
        refreshAccount(profile, device, generation, refreshPending || (syncTime != null && syncTime != previousSync))
        return AppResult.Success(Unit)
    }

    private suspend fun scopeLost(current: Profile?, owner: Profile): Boolean =
        current?.id != owner.id || current.requiresReauthentication || access.locks.isLocked(owner.id)

    private suspend fun refreshAccount(profile: Profile, device: GarminDeviceRef, generation: Long, needed: Boolean) {
        // Sidecar owns watch -> ABS writes. Refresh through the existing account/progress owner.
        if (!needed || !access.allowed(profile, generation)) return
        val refreshed = access.syncAccount(profile.id)
        if (refreshed is AppResult.Success && access.allowed(profile, generation)) {
            metadata(profile, device) { put("accountRefreshPending", false) }
        }
    }

    private suspend fun reconcileCommands(
        profile: Profile,
        device: GarminDeviceRef,
        rows: List<GarminRecord>,
        syncRequest: String?,
        failedSync: String?,
    ) {
        val states = rows.associate { it.recordId to GarminDeviceDocuments.read(it.payload).optString("state") }
        for (command in store.records(profile.id.value, device.identifier.toString(), COMMAND)) {
            val document = GarminDeviceDocuments.read(command.payload)
            if (document.optString("state") != "accepted") continue
            val sync = document.optString("type") == "sync"
            val complete = if (sync) {
                command.recordId == syncRequest
            } else {
                states[
                    document.optString(
                        "b",
                    ),
                ] == "downloaded"
            }
            val failed = if (sync) {
                command.recordId == failedSync
            } else {
                val reported = states[document.optString("b")]
                reported == null || reported == "failed"
            }
            val status = when {
                failed -> "failed"
                complete -> "completed"
                else -> "accepted"
            }
            store.put(listOf(command.copy(payload = document.put("state", status).toString())))
        }
    }

    // Stop before acknowledging at every failed page/import/ack seam.
    @Suppress("ReturnCount")
    private suspend fun importEvents(profile: Profile, device: GarminDeviceRef, generation: Long): AppResult<Unit> {
        repeat(EVENT_PAGE_LIMIT) { _ ->
            val reply = port.exchange(profile, "events") ?: return unavailable()
            if (reply["t"] != "events") return rejected()
            val raw = reply["rows"] as? List<*> ?: return rejected()
            if (raw.size > EVENT_PAGE_SIZE) return rejected()
            val events = raw.map { GarminProviderCodec.event(it) ?: return rejected() }
            if (events.any { it.profileId != profile.id.value }) return rejected()
            if (reply["more"] !is Boolean || reply["gap"] !is Boolean) return rejected()
            if (!access.allowed(profile, generation)) return denied()
            store.put(
                events.map {
                    record(profile, device, EVENTS, it.id, GarminDeviceDocuments.encode(it)).copy(recordedAt = it.at)
                },
            )
            metadata(profile, device) { put("historyGap", reply["gap"] == true) }
            if (events.isNotEmpty() &&
                port.exchange(profile, "ack_events", mapOf("ids" to events.map { it.id }))?.get("ok") != true
            ) {
                return unavailable()
            }
            if (reply["more"] != true || events.isEmpty()) return AppResult.Success(Unit)
        }
        return AppResult.Success(Unit) // Bounded work; next tick drains the remainder.
    }

    private suspend fun metadata(profile: Profile, device: GarminDeviceRef, change: JSONObject.() -> Unit) {
        val previous = store.records(profile.id.value, device.identifier.toString(), DEVICE).firstOrNull()
        val document = previous?.let { GarminDeviceDocuments.read(it.payload) } ?: JSONObject()
        document.change()
        store.put(listOf(record(profile, device, DEVICE, "state", document.toString())))
    }
    private fun record(profile: Profile, device: GarminDeviceRef, kind: String, id: String, payload: String) =
        GarminRecord(profile.id.value, device.identifier.toString(), kind, id, payload, clock.now().toEpochMilli())
    private fun available() = (sdk.providerState.value as? GarminSdkState.AppAvailable)?.device
    private fun GarminSdkState.connected() = when (this) {
        is GarminSdkState.AppAvailable,
        is GarminSdkState.AppNotInstalled,
        is GarminSdkState.AppUnavailable,
        is GarminSdkState.DeviceConnected,
        -> true

        GarminSdkState.Starting,
        GarminSdkState.NoDevice,
        is GarminSdkState.Unavailable,
        is GarminSdkState.DeviceDisconnected,
        -> false
    }
    private fun selected(): GarminDeviceRef? = when (val state = sdk.providerState.value) {
        is GarminSdkState.AppAvailable -> state.device
        is GarminSdkState.DeviceConnected -> state.device
        is GarminSdkState.DeviceDisconnected -> state.device
        is GarminSdkState.AppNotInstalled -> state.device
        is GarminSdkState.AppUnavailable -> state.device
        else -> null
    }
    private suspend fun guarded(action: suspend () -> AppResult<Unit>): AppResult<Unit> = try {
        action()
    } catch (
        cancelled: CancellationException,
    ) {
        throw cancelled
    } catch (
        _: RuntimeException,
    ) {
        AppResult.Failure(AppError.Storage("Watch state could not be saved."))
    }
    private fun denied() = AppResult.Failure(AppError.Authorization("Unlock the current account to manage its watch."))
    private fun unavailable() =
        AppResult.Failure(AppError.Network("Open BookWave Audio on the connected watch and try again."))
    private fun rejected() = AppResult.Failure(
        AppError.ApiCompatibility("The watch could not accept this request. Check its setup and account."),
    )
    private companion object {
        const val PAIR_TIMEOUT_MS = 120_000L
        const val SETUP_TIMEOUT_MS = 60_000L
        const val PAIR_CODE_LENGTH = 6
        const val DEVICE = "device"
        const val COMMAND = "command"
        const val INVENTORY = "inventory"
        const val EVENTS = "event"
        const val POLL_MS = 30_000L
        const val EVENT_PAGE_LIMIT = 20
        const val EVENT_PAGE_SIZE = 5
    }
}
