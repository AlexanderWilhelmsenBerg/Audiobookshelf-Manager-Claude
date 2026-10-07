package com.example.shelfplayer.garmin

import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.lock.ProfileLockState
import com.example.shelfplayer.domain.repository.ProfileLockRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.playback.PlaybackController
import com.example.shelfplayer.playback.PlaybackUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GarminBridge @Inject internal constructor(
    private val sdk: GarminMobileSdk,
    private val codec: GarminMessageCodec,
    private val projector: GarminSnapshotProjector,
    private val sendPolicy: GarminSnapshotSendPolicy,
    private val privacyPolicy: GarminPrivacyPolicy,
    private val playback: PlaybackController,
    private val profiles: ProfileRepository,
    private val locks: ProfileLockRepository,
    private val clock: AppClock,
    private val logger: Logger,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<GarminBridgeState>(GarminBridgeState.SdkUnavailable("NOT_STARTED"))
    val state: StateFlow<GarminBridgeState> = _state.asStateFlow()

    private var started = false
    private var sdkState: GarminSdkState = GarminSdkState.Starting
    private var jobs = mutableListOf<Job>()
    private var protocolCompatible: Boolean? = null
    private var latestSnapshot: GarminPlaybackSnapshot? = null
    private var privacyBlocked = true
    private var privacyClearSent = false
    private var lastProfileGeneration: Long? = null
    private val pending = linkedMapOf<String, GarminMessageType>()
    private val processedAcks = LinkedHashSet<String>()

    fun start() {
        if (started) return
        started = true

        jobs += applicationScope.launch {
            sdk.state.collect { next ->
                mutex.withLock { onSdkState(next) }
            }
        }
        jobs += applicationScope.launch {
            sdk.incomingMessages.collect { raw ->
                mutex.withLock { onIncoming(raw) }
            }
        }
        jobs += applicationScope.launch {
            combine(
                playback.state,
                profiles.observeActiveProfile(),
                locks.observeLockState(),
            ) { playbackState, profile, lockState ->
                ProjectionInput(playbackState, profile, lockState)
            }.collect { input ->
                mutex.withLock { onBookWaveState(input) }
            }
        }

        sdk.start()
    }

    fun stop() {
        if (!started) return
        jobs.forEach { it.cancel() }
        jobs.clear()
        sdk.shutdown()
        started = false
        protocolCompatible = null
        _state.value = GarminBridgeState.SdkUnavailable("SDK_STOPPED")
    }

    private fun onSdkState(next: GarminSdkState) {
        val wasAvailable = sdkState is GarminSdkState.AppAvailable
        sdkState = next
        when (next) {
            GarminSdkState.Starting,
            is GarminSdkState.Unavailable,
            GarminSdkState.NoDevice,
            is GarminSdkState.DeviceDisconnected,
            is GarminSdkState.DeviceConnected,
            is GarminSdkState.AppNotInstalled,
            is GarminSdkState.AppUnavailable,
            -> {
                protocolCompatible = null
                _state.value = next.toBridgeState()
            }
            is GarminSdkState.AppAvailable -> {
                if (!wasAvailable) {
                    protocolCompatible = null
                    _state.value = next.toBridgeState()
                    // A reconnect must refresh the watch even if its UI is closed. The watch still
                    // validates protocol-major and snapshot content before persistence.
                    if (privacyBlocked) {
                        sendClear()
                    } else {
                        sendCurrent(force = true)
                    }
                }
            }
        }
    }

    private fun onBookWaveState(input: ProjectionInput) {
        val generation = profiles.activeProfileGeneration()
        val generationChanged = lastProfileGeneration != null && lastProfileGeneration != generation
        if (generationChanged) {
            privacyClearSent = false
            sendPolicy.reset()
            latestSnapshot = null
            sendClear()
        }
        lastProfileGeneration = generation

        val profile = input.profile
        if (!privacyPolicy.mayExpose(profile, input.lockState)) {
            latestSnapshot = null
            sendPolicy.reset()
            if (!privacyBlocked || !privacyClearSent) {
                privacyBlocked = true
                sendClear()
            }
            return
        }

        privacyBlocked = false
        privacyClearSent = false

        val snapshot = projector.project(
            playback = input.playback,
            profile = profile,
            updatedAtEpochMs = clock.now().toEpochMilli(),
        )
        latestSnapshot = snapshot
        if (snapshot != null) {
            sendSnapshotIfNeeded(snapshot)
        }
    }

    private fun onIncoming(raw: Any) {
        when (val decoded = codec.decode(raw)) {
            is GarminDecodeResult.Failure -> {
                sdk.send(codec.error(decoded.messageId, decoded.reason))
                if (decoded.reason == "unsupported_protocol_major") {
                    currentDeviceName()?.let { _state.value = GarminBridgeState.ProtocolIncompatible(it) }
                }
            }
            is GarminDecodeResult.Success -> handle(decoded.envelope)
        }
    }

    private fun handle(envelope: GarminEnvelope) {
        when (envelope.type) {
            GarminMessageType.Hello -> handleHello(envelope)
            GarminMessageType.StateRequest -> {
                if (privacyBlocked) sendClear() else sendCurrent(force = true)
            }
            GarminMessageType.SnapshotAck -> handleSnapshotAck(envelope)
            GarminMessageType.ClearAck -> handleClearAck(envelope)
            GarminMessageType.Error -> handleError(envelope)
            GarminMessageType.HelloAck,
            GarminMessageType.Snapshot,
            GarminMessageType.ClearState,
            -> sdk.send(codec.error(envelope.id, "unexpected_message_type"))
        }
    }

    private fun handleHello(envelope: GarminEnvelope) {
        val majors = envelope.payload["majors"] as? List<*>
        val compatible = majors?.any { (it as? Number)?.toInt() == GarminBridgeConfig.PROTOCOL_MAJOR } == true

        sdk.send(codec.helloAck(envelope.id, compatible))
        val device = currentDeviceName()
        if (!compatible) {
            protocolCompatible = false
            if (device != null) _state.value = GarminBridgeState.ProtocolIncompatible(device)
            return
        }

        protocolCompatible = true
        if (device != null) _state.value = GarminBridgeState.Ready(device)
        if (privacyBlocked) sendClear() else sendCurrent(force = true)
    }

    private fun handleSnapshotAck(envelope: GarminEnvelope) {
        val correlation = envelope.replyTo ?: return
        if (!rememberAck(correlation)) return
        pending.remove(correlation)

        val accepted = envelope.payload["accepted"] as? Boolean ?: return
        if (!accepted) {
            val reason = envelope.payload["reason"] as? String ?: "rejected"
            logger.warn(
                LogCategory.App,
                "Garmin snapshot rejected",
                LogField.Public("reason", reason.take(64)),
            )
        }
    }

    private fun handleClearAck(envelope: GarminEnvelope) {
        val correlation = envelope.replyTo ?: return
        if (!rememberAck(correlation)) return
        pending.remove(correlation)
    }

    private fun handleError(envelope: GarminEnvelope) {
        val reason = envelope.payload["reason"] as? String ?: return
        if (reason == "unsupported_protocol_major" || reason == "protocol_incompatible") {
            protocolCompatible = false
            currentDeviceName()?.let { _state.value = GarminBridgeState.ProtocolIncompatible(it) }
        }
    }

    private fun sendSnapshotIfNeeded(snapshot: GarminPlaybackSnapshot) {
        val elapsed = clock.elapsed()
        if (!sendPolicy.shouldSend(snapshot, elapsed)) return
        val outgoing = codec.snapshot(snapshot)
        if (sdk.send(outgoing.payload)) {
            pending[outgoing.id] = GarminMessageType.Snapshot
            trimPending()
            sendPolicy.markSent(snapshot, elapsed)
        }
    }

    private fun sendCurrent(force: Boolean) {
        val snapshot = latestSnapshot ?: return
        val elapsed = clock.elapsed()
        if (!sendPolicy.shouldSend(snapshot, elapsed, force)) return
        val outgoing = codec.snapshot(snapshot)
        if (sdk.send(outgoing.payload)) {
            pending[outgoing.id] = GarminMessageType.Snapshot
            trimPending()
            sendPolicy.markSent(snapshot, elapsed)
        }
    }

    private fun sendClear() {
        if (sdkState !is GarminSdkState.AppAvailable) return
        val outgoing = codec.clearState()
        if (sdk.send(outgoing.payload)) {
            pending[outgoing.id] = GarminMessageType.ClearState
            trimPending()
            privacyClearSent = true
            logger.info(LogCategory.App, "Garmin privacy clear sent")
        }
    }

    private fun currentDeviceName(): String? = when (val current = sdkState) {
        is GarminSdkState.AppAvailable -> current.device.name
        is GarminSdkState.AppUnavailable -> current.device.name
        is GarminSdkState.AppNotInstalled -> current.device.name
        is GarminSdkState.DeviceConnected -> current.device.name
        is GarminSdkState.DeviceDisconnected -> current.device.name
        else -> null
    }

    private fun rememberAck(id: String): Boolean {
        if (!processedAcks.add(id)) return false
        while (processedAcks.size > MAX_ACK_HISTORY) {
            val first = processedAcks.firstOrNull() ?: break
            processedAcks.remove(first)
        }
        return true
    }

    private fun trimPending() {
        while (pending.size > MAX_PENDING_MESSAGES) {
            pending.remove(pending.keys.first())
        }
    }

    private data class ProjectionInput(
        val playback: PlaybackUiState,
        val profile: Profile?,
        val lockState: ProfileLockState,
    )

    private companion object {
        const val MAX_ACK_HISTORY = 64
        const val MAX_PENDING_MESSAGES = 32
    }
}
