package com.example.shelfplayer.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputActivitySource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * PRODUCT_SPEC PLAY-002 — sending the current book to a chosen output, and saying which one that is.
 *
 * `ExoPlayer.setPreferredAudioDevice` is a preference, not a promise. Android may decline it. API 33+
 * supplies the framework-reported media route through `getAudioDevicesForAttributes`; below that the
 * explicit request is the best available fallback.
 *
 * The complete output flow deliberately still contains the phone speaker so routing safety can observe it.
 * The retired Android Auto browse output list excluded speakers: BookWave never offers the built-in speaker as a manual
 * destination even though Android's own system output switcher may still do so.
 */
@OptIn(UnstableApi::class)
@Singleton
class AudioOutputRouter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val logger: Logger,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
    @param:Dispatcher(ShelfDispatcher.MainImmediate) private val mainDispatcher: CoroutineDispatcher,
    private val endpointRegistry: LiveOutputEndpointRegistry = LiveOutputEndpointRegistry(),
) {

    private val _outputs = MutableStateFlow<List<AudioOutput>>(emptyList())

    /** Every output connected right now, including speakers needed for route observation. */
    val outputs: StateFlow<List<AudioOutput>> = _outputs.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)

    /** The chosen output id, or `null` for Automatic — let Android route. */
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    /**
     * Listener choices from every BookWave output surface.
     *
     * This is deliberately separate from [selectedId]: a repeated Car/Automatic press is still newer intent
     * even when the value stays `null`, while a policy reassertion must not masquerade as listener intent.
     */
    internal enum class ExplicitDestination {
        Automatic,
        Car,
        Output,
    }

    internal data class ExplicitSelection(
        val sequence: Long,
        val outputId: String?,
        val destination: ExplicitDestination,
    )

    private var nextExplicitSelectionSequence = 0L
    private val explicitSelectionState = MutableStateFlow<ExplicitSelection?>(null)
    internal val explicitSelection: StateFlow<ExplicitSelection?> = explicitSelectionState.asStateFlow()

    private var player: ExoPlayer? = null

    /** Queued preferences share one generation and the existing Media3 application dispatcher. */
    private val preferenceEffects = RoutePreferenceEffects(mainDispatcher)
    private var callback: AudioDeviceCallback? = null

    // Transient continuity ownership is player-attached and separate from endpoint policy.
    private val headsetGuard = HeadsetRouteGuard(
        scope = applicationScope,
        playerDispatcher = mainDispatcher,
        isCurrentProbe = ::isCurrentGuard,
        onProbeInvalidated = ::handleInvalidatedProbe,
        onProbeFinished = { verified ->
            logger.info(
                LogCategory.Playback,
                "Car continuity silent headset verification completed",
                LogField.Public("verified", verified),
            )
        },
        onPlaying = ::pauseUnsafeGuardedPlayback,
    )
    private var currentTrackRoutes: () -> Set<String>? = { null }
    private var collidingOutputIds: Set<String> = emptySet()

    /** Process-scoped live endpoints; never key physical routing by persistent name-based policy ID. */
    private var live: Map<String, AudioDeviceInfo> = emptyMap()

    fun attach(target: ExoPlayer, trackRoutes: () -> Set<String>? = { null }) {
        // A probe owns the volume it muted. Restore it while its player is still attached,
        // before a replacement can become the router's current player.
        headsetGuard.cancelProbe()
        headsetGuard.detachPlayer()
        val epoch = preferenceEffects.supersede()
        currentTrackRoutes = trackRoutes
        player = target
        headsetGuard.attachPlayer(target)
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        if (callback == null) {
            val registered = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
            }
            manager.registerAudioDeviceCallback(registered, null)
            callback = registered
        }
        refresh()
        applicationScope.launch { apply(_selectedId.value, epoch) }
    }

    /** Releases the player. The device callback stays registered because the connected list outlives it. */
    fun detach() {
        preferenceEffects.supersede()
        headsetGuard.release()
        currentTrackRoutes = { null }
        collidingOutputIds = emptySet()
        headsetGuard.detachPlayer()
        player = null
    }

    /**
     * Chooses an output, or `null` for Automatic.
     *
     * A stale id is ignored rather than converted to Automatic.
     *
     * The phone speaker is **not** refused here. It is not a car destination, and the car cannot reach it:
     * `AutoLibrary.outputRows` never lists it and `chooseOutput` refuses it by id, so a stale cached browse
     * row is already answered where that row is handled. Refusing it in the shared router instead reached
     * the phone's own chooser, which lists every connected output — including the speaker — and left that
     * row silently doing nothing.
     */
    fun select(id: String?) {
        choose(
            id = id,
            destination = if (id == null) ExplicitDestination.Automatic else ExplicitDestination.Output,
        )
    }

    /**
     * The player-screen Car action is semantically stronger than generic Automatic.
     *
     * Both clear ExoPlayer's preferred device, but only this call says the listener explicitly chose the car.
     * Projected Android Auto may not expose a phone-side route that can prove that fact, so #34 keeps this
     * intent separate from [selectedId] instead of collapsing both choices to null.
     */
    fun selectCar() {
        choose(id = null, destination = ExplicitDestination.Car)
    }

    /**
     * Reasserts already-owned routing when a car arrives.
     *
     * This is BookWave policy applying existing [RouteHeardOwnership] evidence, not a new listener choice, so
     * it intentionally does not emit through [explicitSelection].
     */
    internal suspend fun reassert(id: String): Boolean {
        if (id !in live) {
            logger.info(
                LogCategory.Playback,
                "An owned audio output was no longer connected",
                LogField.Public("kind", kindOf(id)),
            )
            return false
        }
        val epoch = preferenceEffects.supersede()
        headsetGuard.protect(id)
        _selectedId.value = id
        publish()
        apply(id, epoch)
        return true
    }

    /**
     * Protect an already-running exact headset without touching ExoPlayer's output selection.
     * Car binding must be observable but must not reassert if the route is still healthy.
     */
    internal fun protectPlayingHeadset(id: String) {
        if (id !in live) return
        // A captured headset supersedes queued Automatic/Car requests, even before a direct route call.
        preferenceEffects.supersede()
        // Capture an implicit system headset route as the exact temporary preference, without
        // touching the AudioTrack. This lets refresh detect disappearance even when the user
        // never opened BookWave's output chooser and selectedId was Automatic.
        if (_selectedId.value == null) _selectedId.value = id
        headsetGuard.protect(id)
        publish()
    }

    private fun choose(id: String?, destination: ExplicitDestination) {
        if (id != null && id !in live) {
            logger.info(
                LogCategory.Playback,
                "An output was chosen that is no longer connected",
                LogField.Public("kind", kindOf(id)),
            )
            return
        }
        // Even a repeated null Car/Automatic choice is newer intent; output id equality is insufficient.
        val epoch = preferenceEffects.supersede()
        headsetGuard.clearOwnership()
        applicationScope.launch(mainDispatcher) { headsetGuard.cancelProbe() }
        explicitSelectionState.value = ExplicitSelection(
            sequence = ++nextExplicitSelectionSequence,
            outputId = id,
            destination = destination,
        )
        _selectedId.value = id
        publish()
        applicationScope.launch { apply(id, epoch) }
    }

    private suspend fun apply(id: String?, epoch: Long) = preferenceEffects.run(epoch) {
        // The generation also distinguishes consecutive Car and Automatic selections (both null),
        // player reattachments, and the recovery router silently asserting the same output ID.
        if (_selectedId.value != id) return@run
        val target = player ?: return@run
        val device = id?.let(live::get)
        target.setPreferredAudioDevice(device)
        publish()
        val routed = routedIds()
        logger.info(
            LogCategory.Playback,
            "The audio output was set",
            LogField.Public("asked", if (id == null) "automatic" else kindOf(id)),
            LogField.Public("connected", (device != null).toString()),
            LogField.Public("routedTo", routed?.joinToString("+") { kindOf(it) } ?: "unknown"),
            LogField.Public(
                "honoured",
                when {
                    routed == null -> "unknown"
                    id == null -> "automatic"
                    else -> routed.contains(id).toString()
                },
            ),
        )
        // A request may become stale during the delay. It must not publish a superseded outcome.
        preferenceEffects.afterSettle(epoch, ROUTE_SETTLE_DELAY) { publish() }
    }

    /** Observe a car-driven OS route change independently of output-preference requests. */
    private suspend fun resettleAfterDelay() {
        delay(ROUTE_SETTLE_DELAY)
        publish()
    }

    /**
     * PRODUCT_SPEC PLAY-002 / ROUTE-002 — re-read the route when something moved it that this class cannot see.
     *
     * ### Why a car needs this
     *
     * `routedIds()` is only ever consulted from [publish], and [publish] is only reached from [attach], from
     * [refresh] — which the framework drives through `AudioDeviceCallback.onAudioDevicesAdded/Removed` — and
     * from an explicit [select]. **A car binding is none of those.** Android Auto activating an A2DP link
     * that was already connected moves the route without adding or removing a device, so no callback fires,
     * nothing re-reads the route, and the output state stays whatever it was before the drive started.
     *
     * That is why a device run reported the Car action never lighting: the fact `AudioOutputRoles.buttons`
     * depends on was never refreshed at the one moment it changed. ADR-0029 §8 claimed "a republish of the
     * button preferences, which the service already does when the route moves" — true only for moves that
     * come with a device event, which this one does not. The republish happened; the *route* behind it was
     * stale. Fifth correction of that shape on this branch.
     *
     * Twice, because the route is not settled the instant the controller binds — the same reason [apply]
     * ends in [settle], and the same delay.
     */
    fun resettle() {
        publish()
        applicationScope.launch { resettleAfterDelay() }
    }

    private fun refresh() {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        val observations = devices.map { info ->
            LiveOutputEndpointRegistry.Observation(info.id, info.type, info.productName?.toString())
        }
        val endpointEntries = endpointRegistry.reconcile(observations)
        val byHandle = devices.associateBy(AudioDeviceInfo::getId)
        live = endpointEntries.mapNotNull { endpoint ->
            byHandle[endpoint.platformId]?.let { endpoint.id to it }
        }.toMap()
        // Distinct live endpoints that share a legacy saved name are not one physical route.
        val duplicates = endpointEntries.groupBy { it.knownDeviceId }
            .values.filter { it.size > 1 }
        collidingOutputIds = duplicates.flatMap { group -> group.map { it.id } }.toSet()
        val found = live
        val chosen = _selectedId.value
        when {
            chosen != null && chosen !in found -> onDisconnectedSelection(chosen, found.keys)

            chosen != null && chosen == headsetGuard.endpointId && headsetGuard.endpointMissing ->
                onGuardedHeadsetReappeared(chosen)
        }
        publish()
    }

    private fun onDisconnectedSelection(chosen: String, connectedIds: Set<String>) {
        if (!shouldRetainContinuityHeadset(headsetGuard.endpointId, chosen, connectedIds)) {
            val epoch = preferenceEffects.supersede()
            _selectedId.value = null
            logger.info(
                LogCategory.Playback,
                "The chosen audio output disconnected, so routing is automatic again",
                LogField.Public("kind", kindOf(chosen)),
            )
            applicationScope.launch { apply(null, epoch) }
            return
        }

        // Pause on the player looper before OS default routing can become audible.
        applicationScope.launch(mainDispatcher) {
            if (_selectedId.value == chosen && headsetGuard.endpointId == chosen && chosen !in live) {
                player?.takeIf { it.isPlaying }?.pause()
            }
        }
        val alreadyMissing = headsetGuard.markMissing()
        if (alreadyMissing || headsetGuard.disappearanceGrace != null) return
        logger.info(
            LogCategory.Playback,
            "Car continuity headset disappeared; preserving selection while paused",
            LogField.Public("kind", kindOf(chosen)),
        )
        headsetGuard.startGrace {
            if (_selectedId.value == chosen && headsetGuard.endpointId == chosen && chosen !in live) {
                // The waiting window ended, not the owner's explicit headset destination.
                // Clearing the guard here allowed a subsequent virtual/unattributed headset
                // Play to resume through the car on Android Auto. Stay fail-closed.
                logger.info(
                    LogCategory.Playback,
                    "Car continuity headset still absent after grace; keeping route guarded",
                    LogField.Public("kind", kindOf(chosen)),
                )
            }
        }
    }

    private fun onGuardedHeadsetReappeared(chosen: String) {
        // The old exact headset returned during the grace. Reapply its preference
        // without issuing Play; a newer listener selection can still supersede it.
        headsetGuard.endpointReturned()
        val epoch = preferenceEffects.supersede()
        applicationScope.launch {
            if (_selectedId.value == chosen && headsetGuard.endpointId == chosen && chosen in live) {
                apply(chosen, epoch)
            }
        }
    }

    /** An unknown projection prediction permits only a SILENT verification of the exact original headset. */
    internal fun canProbeHeldHeadset(id: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return canAttemptMutedHeadsetProbe(
            id,
            headsetGuard.endpointId,
            _selectedId.value,
            id in live && id !in collidingOutputIds,
            routedIds(),
        )
    }

    /** Called on Media3's application thread before the automatic continuity Play. */
    internal fun beginMutedHeadsetProbe(id: String): Boolean = canProbeHeldHeadset(id) && startMutedProbe(id)

    /** An unattributed manual Play can only become audible on this already-guarded headset. */
    internal fun protectManualPlayOnGuardedHeadset(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val id = headsetGuard.endpointId ?: return false
        if (_selectedId.value != id || id in collidingOutputIds) return false
        return startMutedProbe(id)
    }

    private fun startMutedProbe(id: String): Boolean {
        val current = player ?: return false
        val epoch = preferenceEffects.snapshot()
        val started = headsetGuard.beginProbe(id, current, epoch)
        if (!started) return false
        logger.info(LogCategory.Playback, "Car continuity began silent headset verification")
        return true
    }

    /** AudioTrack can change sinks without an AudioManager device-list callback. */
    internal fun onActualTrackRouteChanged() {
        pauseUnsafeGuardedPlayback()
        publish()
    }

    private fun publish() {
        // One publication must not mix a measured sink with a later policy/preference observation.
        // This snapshot describes presentation; the guard below still reads fresh actual-track proof
        // on Media3's dispatcher before deciding whether playback may become audible.
        val actual = currentTrackRoutes()
        val predicted = if (actual == null) routedIds() else null
        val routed = actual ?: predicted
        val chosen = _selectedId.value
        val source = when {
            actual != null -> AudioOutputActivitySource.PlayingAudioTrack
            predicted != null -> AudioOutputActivitySource.FrameworkMediaPolicy
            chosen != null -> AudioOutputActivitySource.SelectedPreference
            else -> AudioOutputActivitySource.Unmeasured
        }
        pauseUnsafeGuardedPlayback()
        _outputs.value = live.mapNotNull { (id, info) ->
            endpointRegistry.find(info.id, info.type, info.productName?.toString())?.output?.copy(
                id = id,
                isActive = routed?.contains(id) ?: (id == chosen),
                activitySource = source,
            )
        }
    }

    /**
     * Check again when Media3 transitions to audible playback. A Gearhead Play can arrive
     * without any AudioDeviceCallback, so checking only publish/resettle misses that route race.
     */
    private fun pauseUnsafeGuardedPlayback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        // The route-settle coroutine and AudioDeviceCallback may call publish() off the
        // ExoPlayer application looper. Even reading isPlaying there crashes Media3.
        // Check only our own routing state before dispatching; every Player access,
        // including the initial isPlaying read, belongs on MainImmediate.
        val epoch = preferenceEffects.snapshot()
        val guarded = headsetGuard.endpointId ?: return
        if (_selectedId.value != guarded) return
        val target = player ?: return
        applicationScope.launch(mainDispatcher) {
            if (!isCurrentGuard(epoch, target, guarded)) return@launch
            val current = target
            // A framework media-policy prediction is never proof of this AudioTrack's
            // physical sink. In particular, a paused-to-Play car reconnect can report
            // a predicted headset while the actual track is RemoteSubmix or unobservable.
            // A guarded headset must fail closed until this playing track is measured.
            val actualRoute = currentTrackRoutes()
            if (!isCurrentGuard(epoch, target, guarded)) return@launch
            val isPlaying = current.isPlaying
            if (headsetGuard.verifyProbeIfActive(actualRoute, collidingOutputIds, isPlaying)) return@launch
            if (guarded !in collidingOutputIds && !shouldSuspendGuardedPlayback(
                    headsetGuard.endpointId,
                    _selectedId.value,
                    actualRoute,
                    isPlaying,
                )
            ) {
                return@launch
            }
            current.pause()
            logUnverifiedRoute(actualRoute, guarded)
        }
    }

    /** A queued safety observation belongs to one preference epoch, endpoint and attached player. */
    private fun isCurrentGuard(epoch: Long, target: ExoPlayer, guarded: String): Boolean =
        preferenceEffects.isCurrent(epoch) && player === target && headsetGuard.endpointId == guarded &&
            _selectedId.value == guarded

    /** Preserve fail-closed playback if policy supersedes a probe but still owns a guarded route. */
    private fun handleInvalidatedProbe(probePlayer: ExoPlayer): Boolean {
        val currentGuard = headsetGuard.endpointId
        if (player !== probePlayer || currentGuard == null || _selectedId.value != currentGuard) return true
        val epoch = preferenceEffects.snapshot()
        if (!isCurrentGuard(epoch, probePlayer, currentGuard)) return false
        val actualRoute = currentTrackRoutes()
        if (!isCurrentGuard(epoch, probePlayer, currentGuard)) return false
        val currentGuardIsMeasured = actualRoute == setOf(currentGuard) && currentGuard !in collidingOutputIds
        if (!currentGuardIsMeasured && probePlayer.isPlaying) {
            probePlayer.pause()
            logUnverifiedRoute(actualRoute, currentGuard)
        }
        return true
    }

    /** Reports route categories from the decision snapshot without exposing endpoint identity. */
    private fun logUnverifiedRoute(actualRoute: Set<String>?, guarded: String) {
        logger.info(
            LogCategory.Playback,
            "Car continuity paused because the headset route is not verified",
            LogField.Public(
                "route",
                when {
                    actualRoute.isNullOrEmpty() -> "unknown"
                    guarded !in actualRoute -> "other"
                    else -> "multiple"
                },
            ),
        )
    }

    /** Framework-reported devices for BookWave's media attributes, or `null` before API 33. */
    private fun routedIds(): Set<String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val manager = context.getSystemService(AudioManager::class.java) ?: return null
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        return manager.getAudioDevicesForAttributes(attributes)
            .mapNotNull { info ->
                endpointRegistry.find(info.id, info.type, info.productName?.toString())?.id
            }
            .toSet()
    }

    /**
     * The selectable list exposed to the car browse tree. Speakers remain in [outputs] above but are absent
     * here by construction.
     */
    fun available(): List<AudioOutput> = _outputs.value.filterNot(AudioOutput::isSpeaker)

    fun selected(): String? = _selectedId.value

    /** Kind half only; product names never reach logs. */
    private fun kindOf(id: String): String = id.substringBefore(':')

    private companion object {
        val ROUTE_SETTLE_DELAY = 400.milliseconds
    }
}

/**
 * Pure route-safety guard used by the shared router's actual publish callback.
 * The caller prefers a measured AudioTrack sink over AudioManager's predicted output.
 * Unknown, multiple and other sinks fail closed even when a preference was accepted.
 */
internal fun shouldSuspendGuardedPlayback(
    guardedHeadsetId: String?,
    selectedId: String?,
    routedIds: Set<String>?,
    isPlaying: Boolean,
): Boolean {
    if (!isPlaying || guardedHeadsetId == null || guardedHeadsetId != selectedId) return false
    return routedIds == null || routedIds.size != 1 || guardedHeadsetId !in routedIds
}

/**
 * Selecting Play on the Android Auto surface is an explicit car-route request, not a reason to keep
 * an old headset-only protection active. A duplicate Gearhead Play must not steal a healthy running
 * headset; ordinary media buttons do not identify which Bluetooth device sent the button.
 */
internal fun shouldSelectCarForControllerPlay(
    requestedPlay: Boolean,
    controllerIsCar: Boolean,
    playWhenReady: Boolean,
): Boolean = requestedPlay && controllerIsCar && !playWhenReady

/**
 * A silent probe never uses a different connected device or a known competing route.
 * An empty prediction (as for projected REMOTE_SUBMIX) is unknown, not permission to play audibly.
 */
internal fun canAttemptMutedHeadsetProbe(
    targetId: String,
    guardedId: String?,
    selectedId: String?,
    targetConnected: Boolean,
    predictedIds: Set<String>?,
): Boolean = targetConnected && targetId == guardedId && targetId == selectedId &&
    (predictedIds.isNullOrEmpty() || predictedIds == setOf(targetId))

/** Preserve only an app-owned exact headset, never an unrelated or explicitly superseded selection. */
internal fun shouldRetainContinuityHeadset(
    guardedHeadsetId: String?,
    selectedId: String?,
    connectedIds: Set<String>,
): Boolean = guardedHeadsetId != null && selectedId == guardedHeadsetId && guardedHeadsetId !in connectedIds
