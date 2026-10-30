package com.example.shelfplayer.playback

import android.content.Context
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.Player
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
import com.example.shelfplayer.core.model.playback.AudioOutputLockStatus
import com.example.shelfplayer.core.model.playback.AudioOutputPresentation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * PRODUCT_SPEC PLAY-002 — sending the current book to a chosen output, and saying which one that is.
 *
 * `ExoPlayer.setPreferredAudioDevice` is a preference, not a promise. Android may decline it. API 33+
 * supplies a framework media-policy prediction through `getAudioDevicesForAttributes`; it is not a
 * measurement of the current AudioTrack. The chooser keeps that prediction separate from track observation.
 *
 * The complete output flow deliberately still contains the phone speaker so routing safety can observe it.
 * The retired Android Auto browse output list excluded speakers: BookWave never offers the built-in speaker as a manual
 * destination even though Android's own system output switcher may still do so.
 */
@OptIn(UnstableApi::class)
@Singleton
class AudioOutputRouter @Inject constructor(
    @ApplicationContext context: Context,
    private val logger: Logger,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
    @param:Dispatcher(ShelfDispatcher.MainImmediate) private val mainDispatcher: CoroutineDispatcher,
    endpointRegistry: LiveOutputEndpointRegistry = LiveOutputEndpointRegistry(),
    private val volumeEffects: PlaybackVolumeEffects,
) {

    private val _outputs = MutableStateFlow<List<AudioOutput>>(emptyList())

    /** Every output connected right now, including speakers needed for route observation. */
    val outputs: StateFlow<List<AudioOutput>> = _outputs.asStateFlow()

    /** Current request and current-track observation, projected from the existing owner on MainImmediate. */
    val presentation: StateFlow<AudioOutputPresentation> get() = presentationPublisher.state

    private val _selectedId = MutableStateFlow<String?>(null)

    /** The chosen endpoint id; Car and Automatic both have no endpoint id (see [explicitSelection]). */
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    /**
     * Listener choices from every BookWave output surface.
     *
     * This is deliberately separate from [selectedId]: a repeated Car/Automatic press is still newer intent
     * even when the value stays `null`, while a policy reassertion must not masquerade as listener intent.
     */
    internal val outputIntentOwner = OutputIntentOwner()
    internal val explicitSelection: StateFlow<OutputIntentOwner.Selection?> = outputIntentOwner.selection
    internal val outputLock: StateFlow<OutputIntentOwner.Lock?> = outputIntentOwner.lock
    private val _canKeepAudioHere = MutableStateFlow(false)

    /** Advisory presentation state. Arming always performs its own fresh validation. */
    val canKeepAudioHere: StateFlow<Boolean> = _canKeepAudioHere.asStateFlow()

    @kotlin.OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    val outputLockStatus: StateFlow<AudioOutputLockStatus?> = object : StateFlow<AudioOutputLockStatus?> {
        override val value: AudioOutputLockStatus?
            get() = statusFor(outputIntentOwner.state.value.lock)
        override val replayCache: List<AudioOutputLockStatus?> get() = listOf(value)
        override suspend fun collect(
            collector: kotlinx.coroutines.flow.FlowCollector<AudioOutputLockStatus?>,
        ): Nothing {
            outputIntentOwner.state.map { statusFor(it.lock) }.distinctUntilChanged().collect(collector)
            kotlinx.coroutines.awaitCancellation()
        }
    }

    private var player: ExoPlayer? = null

    /** The service supplies evidence, never another mutable intent or heard-route copy. Clear on teardown. */
    internal var lastHeardRouteId: () -> String? = { null }

    /** Queued preferences share one generation and the existing Media3 application dispatcher. */
    private val preferenceEffects = RoutePreferenceEffects(mainDispatcher)
    internal val localOutputs = AndroidLocalOutputAdapter(context, endpointRegistry, applicationScope, mainDispatcher)
    internal val positions = PositionAccess()
    internal val handoffs by lazy(LazyThreadSafetyMode.NONE) { OutputHandoffEffects(HandoffAccess()) }
    internal var currentProjection: () -> Boolean = { false }

    /** Projection lifecycle edges can change presentation even when the device inventory is unchanged. */
    internal val refreshPresentationForLifecycle: () -> Unit get() = presentationPublisher::refresh

    // Transient continuity ownership is player-attached and separate from endpoint policy.
    private val headsetGuard = HeadsetRouteGuard(
        scope = applicationScope,
        playerDispatcher = mainDispatcher,
        volumeEffects = volumeEffects,
        isCurrentScope = ::isCurrentProbeScope,
        onProbeInvalidated = ::handleInvalidatedProbe,
        onProbeFinished = { verified ->
            if (verified) verifyOutputLock()
        },
        onPlaying = ::pauseUnsafeGuardedPlayback,
    ).also { guard ->
        guard.captureCheckpoint = { probe -> positions.handlers?.capture(probe) }
        guard.completeVerifiedProbe = { probe ->
            positions.handlers?.complete(probe) ?: MutedProbeCompletion.Refused
        }
        guard.commitVerifiedProbe = { probe, proof -> handoffs.commit(probe, proof) }
        guard.onProbeCancelled = { probe -> handoffs.cancelled(probe) }
        guard.onExternalPause = { probe -> positions.handlers?.restorePausedProbe(probe) }
        guard.onProbeSettled = { probe, proof ->
            handoffs.settled(probe, proof)
            logger.info(
                LogCategory.Playback,
                "Muted output route proof completed",
                LogField.Public("confirmation", proof.name),
                LogField.Public("destination", probe.transferRequest?.selection?.destination?.name ?: "HeldOutput"),
                LogField.Public("proofEpoch", probe.preferenceEpoch),
                LogField.Public("requestGeneration", probe.transferRequest?.bookGeneration?.toString() ?: "none"),
                LogField.Public("requestSelection", probe.transferRequest?.selection?.sequence?.toString() ?: "none"),
            )
        }
    }
    private var currentTrackRoutes: () -> Set<String>? = { null }

    /** Bound by the service after attachment; absent evidence fails closed. Retired on detach/replacement. */
    internal var currentTrackObservation: () -> AudioTrackObservation? = { null }
    private val collidingOutputIds: Set<String> get() = localOutputs.collidingOutputIds
    private data class AutomaticProtection(
        val outputId: String,
        val generation: Long,
        val selectionSequence: Long,
        val preferenceEpoch: Long,
        val preferredDeviceRequested: Boolean = false,
    ) {
        fun matchesClaim(id: String, bookGeneration: Long, sequence: Long): Boolean =
            outputId == id && generation == bookGeneration && selectionSequence == sequence
    }
    private var automaticProtection: AutomaticProtection? = null

    /** Process-scoped live endpoints; never key physical routing by persistent name-based policy ID. */
    private val live: Set<String> get() = localOutputs.endpointIds

    private val presentationPublisher =
        OutputPresentationPublisher(outputIntentOwner.state, applicationScope, mainDispatcher) {
            OutputPresentationProjection.present(
                outputIntentOwner.state.value,
                currentTrackObservation(),
                _outputs.value,
                currentProjection(),
                live - collidingOutputIds,
            )
        }

    fun attach(target: ExoPlayer, trackRoutes: () -> Set<String>? = { null }) {
        // Publish the successor attachment before retiring old callbacks. A stale probe then restores only its
        // own player and cannot emit an audible-resume event into the successor's sleep/session accounting.
        volumeEffects.attach(target)
        clearMeasuredPlayingHeadsetProtection()
        // A probe owns the volume it muted. Restore it while its player is still attached,
        // before a replacement can become the router's current player.
        headsetGuard.cancelProbe()
        headsetGuard.detachPlayer()
        val epoch = preferenceEffects.supersede()
        currentTrackRoutes = trackRoutes
        currentTrackObservation = { null }
        player = target
        outputIntentOwner.state.value.lock?.let(outputIntentOwner::invalidateProof)
        headsetGuard.attachPlayer(target)
        localOutputs.start(::refresh)
        refresh()
        applicationScope.launch { apply(_selectedId.value, epoch) }
    }

    /** Releases the player. The device callback stays registered because the connected list outlives it. */
    fun detach() {
        _canKeepAudioHere.value = false
        outputIntentOwner.state.value.lock?.let(outputIntentOwner::invalidateProof)
        preferenceEffects.supersede()
        automaticProtection = null
        volumeEffects.detach(player)
        headsetGuard.release()
        currentTrackRoutes = { null }
        currentTrackObservation = { null }
        headsetGuard.detachPlayer()
        player = null
        refreshPresentationForLifecycle()
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
            destination = if (id ==
                null
            ) {
                OutputIntentOwner.Destination.Automatic
            } else {
                OutputIntentOwner.Destination.Output
            },
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
        choose(id = null, destination = OutputIntentOwner.Destination.Car)
    }

    /** Generic controller Play carries no physical dashboard gesture evidence and cannot supersede a lock. */
    internal fun selectCarFromGenericController(): Boolean {
        if (outputIntentOwner.state.value.lock != null) return false
        selectCar()
        return true
    }

    /** Arm the listener's logical target only after fresh, unique proof from the current playing AudioTrack. */
    internal fun keepAudioHere(): Boolean {
        val proof = currentLockTarget() ?: return false
        val effectEpoch = preferenceEffects.supersede()
        outputIntentOwner.keepHere(proof.outputId, proof.generation, effectEpoch)
        _selectedId.value = proof.outputId
        headsetGuard.protect(proof.outputId)
        automaticProtection = null
        publish()
        return true
    }

    private data class LockTarget(val outputId: String, val generation: Long)

    /** Existing ownership boundaries captured together; this adds no generation or intent counter. */
    private data class LockContext(
        val player: ExoPlayer,
        val generation: Long,
        val selectionSequence: Long?,
        val effectEpoch: Long,
    )

    private fun currentLockContext(): LockContext? {
        val intent = outputIntentOwner.state.value
        val generation = intent.activeBookGeneration ?: return null
        val attached = player ?: return null
        if (!attached.isPlaying || attached.mediaItemCount == 0 || volumeEffects.isRouteMuted(attached)) return null
        return LockContext(attached, generation, intent.selection?.sequence, preferenceEffects.snapshot())
    }

    private fun isCurrentLockContext(context: LockContext): Boolean {
        val intent = outputIntentOwner.state.value
        return player === context.player && preferenceEffects.isCurrent(context.effectEpoch) &&
            intent.activeBookGeneration == context.generation &&
            intent.selection?.sequence == context.selectionSequence &&
            context.player.isPlaying && context.player.mediaItemCount > 0
    }

    private fun isEligibleLockOutput(id: String): Boolean {
        val output = _outputs.value.singleOrNull { it.id == id } ?: return false
        return output.isHeadsetCandidate && id in live && id !in collidingOutputIds
    }

    /** Shared by advisory presentation and the final command; never writes intent or starts playback. */
    private fun currentLockTarget(): LockTarget? {
        val context = currentLockContext() ?: return null
        val id = currentTrackRoutes()?.singleOrNull() ?: return null
        if (!isEligibleLockOutput(id)) return null
        val after = currentTrackRoutes()
        return if (after == setOf(id) && isCurrentLockContext(context) && isEligibleLockOutput(id)) {
            LockTarget(id, context.generation)
        } else {
            null
        }
    }

    /** Fresh playing-track evidence only; policy prediction and preferred output cannot preserve a route. */
    internal fun isMeasuredHeadset(id: String): Boolean {
        val context = currentLockContext() ?: return false
        val first = currentTrackObservation() ?: return false
        val second = currentTrackObservation()
        return first.path == AudioTrackPath.Endpoint(id) && first == second &&
            isEligibleLockOutput(id) && !volumeEffects.isRouteMuted(context.player) && isCurrentLockContext(context)
    }

    /**
     * Reasserts already-owned routing when a car arrives.
     *
     * This is BookWave policy applying existing [RouteHeardOwnership] evidence, not a new listener choice, so
     * it intentionally does not emit through [explicitSelection].
     */
    internal suspend fun reassert(id: String): Boolean {
        val selection = explicitSelection.value
        val incompatible = selection?.destination == OutputIntentOwner.Destination.Car ||
            (selection?.destination == OutputIntentOwner.Destination.Output && selection.outputId != id)
        if (incompatible) return false
        if (id !in live) {
            logger.info(
                LogCategory.Playback,
                "An owned audio output was no longer connected",
                LogField.Public("kind", kindOf(id)),
            )
            return false
        }
        val attached = player
        val priorEpoch = preferenceEffects.snapshot()
        val measured = isMeasuredHeadset(id)
        return when {
            !sameReassertScope(selection, attached, priorEpoch) -> false

            measured -> {
                protectPlayingHeadset(id)
                true
            }

            handoffs.isPendingFor(id) -> true

            else -> {
                val epoch = preferenceEffects.supersede()
                headsetGuard.protect(id)
                automaticProtection = automaticProtection?.takeIf { it.outputId == id }?.copy(
                    preferenceEpoch = epoch,
                    preferredDeviceRequested = true,
                )
                _selectedId.value = id
                publish()
                apply(id, epoch)
                true
            }
        }
    }

    private fun sameReassertScope(selection: OutputIntentOwner.Selection?, attached: ExoPlayer?, epoch: Long): Boolean =
        explicitSelection.value == selection && player === attached && preferenceEffects.isCurrent(epoch)

    /**
     * Protect an already-running exact headset without touching ExoPlayer's output selection.
     * Car binding must be observable but must not reassert if the route is still healthy.
     */
    internal fun protectPlayingHeadset(id: String) {
        if (id !in live) return
        if (_selectedId.value == id && headsetGuard.endpointId == id) return
        // A captured headset supersedes queued Automatic/Car requests, even before a direct route call.
        preferenceEffects.supersede()
        // Capture an implicit system headset route as the exact temporary preference, without
        // touching the AudioTrack. This lets refresh detect disappearance even when the user
        // never opened BookWave's output chooser and selectedId was Automatic.
        if (_selectedId.value == null) _selectedId.value = id
        headsetGuard.protect(id)
        publish()
    }

    /** Protects the exact current playing route accepted by the service's generation-bound route owner. */
    internal fun protectMeasuredPlayingHeadset(
        id: String,
        generation: Long,
        selectionSequence: Long,
        isCurrentClaim: () -> Boolean,
    ): Boolean {
        if (!canProtectMeasuredHeadset(id, selectionSequence)) return false
        if (automaticProtection?.matchesClaim(id, generation, selectionSequence) == true &&
            _selectedId.value == id && headsetGuard.endpointId == id
        ) {
            return true
        }
        val attached = player ?: return false
        val epoch = preferenceEffects.snapshot()
        val measuredRoute = currentTrackRoutes()
        val sameAttachment = preferenceEffects.isCurrent(epoch) && player === attached
        return when {
            measuredRoute != setOf(id) || !sameAttachment -> false

            !canProtectMeasuredHeadset(id, selectionSequence) || !isCurrentClaim() -> false

            else -> {
                val protectionEpoch = preferenceEffects.supersede()
                if (_selectedId.value == null) _selectedId.value = id
                headsetGuard.protect(id)
                automaticProtection = AutomaticProtection(id, generation, selectionSequence, protectionEpoch)
                publish()
                true
            }
        }
    }

    /** Route queries may synchronously retire an endpoint or deliver newer selection; check both sides. */
    private fun canProtectMeasuredHeadset(id: String, selectionSequence: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || id !in live ||
            id in collidingOutputIds
        ) {
            return false
        }
        val selection = explicitSelection.value
        if (selection?.destination == OutputIntentOwner.Destination.Car ||
            (selection?.sequence ?: 0L) != selectionSequence
        ) {
            return false
        }
        return (_selectedId.value == null || _selectedId.value == id) &&
            (headsetGuard.endpointId == null || headsetGuard.endpointId == id)
    }

    /** Drops only the transient Automatic-route claim when its owning book/queue generation ends. */
    internal fun clearMeasuredPlayingHeadsetProtection() {
        val active = automaticProtection ?: return
        val epoch = preferenceEffects.supersede()
        automaticProtection = null
        if (headsetGuard.endpointId == active.outputId) headsetGuard.clearOwnership()
        if (_selectedId.value == active.outputId && explicitSelection.value?.outputId == null) {
            _selectedId.value = null
        }
        applicationScope.launch(mainDispatcher) {
            headsetGuard.cancelProbeForEndpoint(active.outputId, active.preferenceEpoch)
        }
        if (active.preferredDeviceRequested && explicitSelection.value?.outputId == null) {
            applicationScope.launch { apply(null, epoch) }
        }
        publish()
    }

    private fun choose(id: String?, destination: OutputIntentOwner.Destination) {
        if (id != null && id !in live) {
            logger.info(
                LogCategory.Playback,
                "An output was chosen that is no longer connected",
                LogField.Public("kind", kindOf(id)),
            )
            return
        }
        handoffs.select(id, destination)
    }

    private suspend fun apply(id: String?, epoch: Long) = preferenceEffects.run(epoch) {
        // The generation also distinguishes consecutive Car and Automatic selections (both null),
        // player reattachments, and the recovery router silently asserting the same output ID.
        if (_selectedId.value != id) return@run
        val target = player ?: return@run
        if (!localOutputs.requestPreference(target, id)) return@run
        publish()
        val routed = routedIds()
        logger.info(
            LogCategory.Playback,
            "The audio output was set",
            LogField.Public("asked", if (id == null) "automatic" else kindOf(id)),
            LogField.Public("connected", (id != null && id in live).toString()),
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
        // Keep the delayed observation as a fallback for silent platform changes, off the recovery path.
        // It never certifies a route and cannot publish a superseded request.
        applicationScope.launch(mainDispatcher) {
            preferenceEffects.afterSettle(epoch, ROUTE_SETTLE_DELAY) { publish() }
        }
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
        localOutputs.refresh()
        val found = live
        val chosen = _selectedId.value
        when {
            chosen != null && chosen !in found -> onDisconnectedSelection(chosen, found)

            chosen != null && chosen == headsetGuard.endpointId && headsetGuard.endpointMissing ->
                onGuardedHeadsetReappeared(chosen)
        }
        publish()
    }

    private fun onDisconnectedSelection(chosen: String, connectedIds: Set<String>) {
        outputIntentOwner.state.value.lock?.takeIf { it.outputId == chosen }?.let(outputIntentOwner::markWaiting)
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
                player?.takeIf { it.isPlaying }?.let(headsetGuard::pauseForSafety)
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
    internal fun protectManualPlayOnGuardedHeadset(
        expectedPlayer: Player,
        willStartAudio: Boolean = true,
        holdPaused: Boolean = false,
    ): ManualPlayProtection = when {
        // A stale service callback must never borrow the router's current attachment to authorize a successor.
        player !== expectedPlayer -> ManualPlayProtection.Denied

        !willStartAudio -> if (!holdPaused || !expectedPlayer.playWhenReady || pauseForSafety(expectedPlayer)) {
            ManualPlayProtection.NoGuard
        } else {
            ManualPlayProtection.Denied
        }

        else -> beginManualPlayProtection().let { protection ->
            when {
                protection == ManualPlayProtection.Denied -> ManualPlayProtection.Denied
                holdPaused && !pauseForSafety(expectedPlayer) -> ManualPlayProtection.Denied
                else -> protection
            }
        }
    }

    private fun pauseForSafety(expectedPlayer: Player): Boolean {
        val current = player as? ExoPlayer ?: return false
        if (current !== expectedPlayer) return false
        return headsetGuard.pauseForSafety(current)
    }

    /** The raw service player is usable only while the router owns that same attachment. */
    internal fun isAttachedPlayer(expectedPlayer: Player): Boolean = player === expectedPlayer

    private fun beginManualPlayProtection(): ManualPlayProtection {
        if (explicitSelection.value?.destination?.let { it != OutputIntentOwner.Destination.Automatic } == true) {
            return handoffs.protectPlay()
        }
        val lock = outputIntentOwner.state.value.lock
        val id = headsetGuard.endpointId ?: return if (lock == null) {
            ManualPlayProtection.NoGuard
        } else {
            ManualPlayProtection.Denied
        }
        return when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> ManualPlayProtection.Denied
            _selectedId.value != id || id !in live || id in collidingOutputIds -> ManualPlayProtection.Denied
            lock != null && lock.outputId != id -> ManualPlayProtection.Denied
            startMutedProbe(id) -> ManualPlayProtection.MutedAllowed
            else -> ManualPlayProtection.Denied
        }
    }

    internal fun isRouteSafetyPause(target: ExoPlayer?): Boolean = headsetGuard.isPausingForSafety(target)

    /** Narrow service/controller seam for transient position proof; no independent checkpoint state. */
    internal inner class PositionAccess {
        var handlers: PositionCheckpointEffects? = null
            private set
        val guard: HeadsetRouteGuard get() = headsetGuard
        val intent: OutputIntentOwner.Snapshot get() = outputIntentOwner.state.value

        fun bind(handlers: PositionCheckpointEffects) {
            this.handlers = handlers
        }

        fun clear() {
            handlers = null
        }

        fun isEligible(probe: HeadsetRouteGuard.Probe): Boolean =
            probe.endpointId?.let { guard.isCurrentProbe(probe) && it in live && it !in collidingOutputIds } == true

        fun measure(): Set<String>? = currentTrackRoutes()

        /** Same live service position for controller/profile-handover reads, on Media3's dispatcher. */
        fun effectivePosition(controller: Player): kotlin.time.Duration {
            val current = player
            val item = controller.currentMediaItem
            val owned = current?.currentMediaItem
            return when {
                current == null || item == null || owned == null -> controller.bookPosition()

                MediaItems.bookIdOf(item) != MediaItems.bookIdOf(owned) ||
                    MediaItems.ownerOf(item) != MediaItems.ownerOf(owned) ->
                    controller.bookPosition()

                else -> handlers?.position(current) ?: controller.bookPosition()
            }
        }
    }

    private fun startMutedProbe(id: String): Boolean {
        val current = player ?: return false
        val epoch = preferenceEffects.snapshot()
        val existing = headsetGuard.currentProbe?.let {
            it.matches(id, current, epoch) && headsetGuard.isCurrentProbe(it)
        } == true
        val started = existing || headsetGuard.beginProbe(id, current, epoch)
        if (!started) return false
        val valid = isCurrentGuard(epoch, current, id) && id in live && id !in collidingOutputIds
        if (!valid) headsetGuard.cancelProbeForEndpoint(id, epoch)
        if (valid && !existing) {
            logger.info(LogCategory.Playback, "Car continuity began silent headset verification")
        }
        return valid
    }

    /** AudioTrack can change sinks without an AudioManager device-list callback. */
    internal fun onActualTrackRouteChanged() {
        verifyOutputLock()
        pauseUnsafeGuardedPlayback()
        publish()
    }

    /** Observation-only; a lock can be revalidated but this path never issues Play. */
    internal fun verifyOutputLock() {
        _canKeepAudioHere.value = currentLockTarget() != null
        val lock = outputIntentOwner.state.value.lock ?: return
        // The same probe owns muted verification and position compensation before lock proof can be renewed.
        if (headsetGuard.currentProbe != null) return
        val context = currentLockContext()
        if (context == null) {
            if (lock.phase == OutputIntentOwner.LockPhase.Active) outputIntentOwner.markWaiting(lock)
            return
        }
        val first = currentTrackRoutes()
        val second = currentTrackRoutes()
        if (!isCurrentOutputLock(lock, context)) return
        if (first == setOf(lock.outputId) && second == first && isEligibleLockOutput(lock.outputId)) {
            outputIntentOwner.activateProof(lock, context.generation, context.effectEpoch)
            headsetGuard.protect(lock.outputId)
        } else {
            outputIntentOwner.markUnverified(lock)
            if (headsetGuard.endpointId != lock.outputId) headsetGuard.protect(lock.outputId)
            pauseUnverifiedOutputLock(lock.copy(phase = OutputIntentOwner.LockPhase.Unverified), context)
        }
    }

    private fun isCurrentOutputLock(lock: OutputIntentOwner.Lock, context: LockContext): Boolean =
        outputIntentOwner.state.value.lock == lock && lock.selectionSequence == context.selectionSequence &&
            isCurrentLockContext(context)

    /** A retired observation may neither query nor pause its replacement's player. */
    private fun pauseUnverifiedOutputLock(lock: OutputIntentOwner.Lock, context: LockContext) {
        applicationScope.launch(mainDispatcher) {
            if (!isCurrentOutputLock(lock, context)) return@launch
            val finalRoute = currentTrackRoutes()
            if (!isCurrentOutputLock(lock, context)) return@launch
            if (finalRoute != setOf(lock.outputId) || !isEligibleLockOutput(lock.outputId)) {
                headsetGuard.pauseForSafety(context.player)
            }
        }
    }

    private fun publish() {
        // One publication must not mix a measured sink with a later policy/preference observation.
        // This snapshot describes presentation; the guard below still reads fresh actual-track proof
        // on Media3's dispatcher before deciding whether playback may become audible.
        val actual = currentTrackRoutes()
        val predicted = if (actual == null) routedIds() else null
        val chosen = _selectedId.value
        pauseUnsafeGuardedPlayback()
        _outputs.value = OutputActivityProjection.rows(availableOutputs(), actual, predicted, chosen)
        applicationScope.launch(mainDispatcher) {
            _canKeepAudioHere.value = currentLockTarget() != null
            presentationPublisher.refresh()
        }
    }

    private fun availableOutputs(): List<AudioOutput> = localOutputs.outputs()

    /**
     * Check again when Media3 transitions to audible playback. A Gearhead Play can arrive
     * without any AudioDeviceCallback, so checking only publish/resettle misses that route race.
     */
    private fun pauseUnsafeGuardedPlayback() {
        val transfer = headsetGuard.currentProbe?.takeIf { it.transferRequest != null }
        if (transfer != null) {
            observeTransfer(transfer)
        } else {
            observeGuardedEndpoint()
        }
    }

    private fun observeTransfer(transfer: HeadsetRouteGuard.Probe) {
        applicationScope.launch(mainDispatcher) {
            if (!headsetGuard.isCurrentProbe(transfer)) return@launch
            val actual = currentTrackRoutes()
            if (!headsetGuard.isCurrentProbe(transfer)) return@launch
            val playing = transfer.player.isPlaying
            if (!headsetGuard.isCurrentProbe(transfer)) return@launch
            headsetGuard.verifyProbeIfActive(actual, collidingOutputIds, playing)
        }
    }

    private fun observeGuardedEndpoint() {
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
            headsetGuard.pauseForSafety(current)
            logUnverifiedRoute(actualRoute, guarded)
        }
    }

    /** A queued safety observation belongs to one preference epoch, endpoint and attached player. */
    private fun isCurrentGuard(epoch: Long, target: ExoPlayer, guarded: String): Boolean =
        preferenceEffects.isCurrent(epoch) && player === target && headsetGuard.endpointId == guarded &&
            _selectedId.value == guarded

    private fun isCurrentProbeScope(probe: HeadsetRouteGuard.Probe): Boolean = if (probe.transferRequest != null) {
        preferenceEffects.isCurrent(probe.preferenceEpoch) && player === probe.player
    } else {
        probe.endpointId?.let { isCurrentGuard(probe.preferenceEpoch, probe.player, it) } == true
    }

    /** Preserve fail-closed playback if policy supersedes a probe but still owns a guarded route. */
    private fun handleInvalidatedProbe(probe: HeadsetRouteGuard.Probe): Boolean = if (probe.transferRequest != null) {
        handoffs.invalidate(probe)
        true
    } else {
        invalidateEndpointProbe(probe)
    }

    private fun invalidateEndpointProbe(probe: HeadsetRouteGuard.Probe): Boolean {
        val probePlayer = probe.player
        val currentGuard = headsetGuard.endpointId
        if (player !== probePlayer || currentGuard == null || _selectedId.value != currentGuard) return true
        val epoch = preferenceEffects.snapshot()
        if (!isCurrentGuard(epoch, probePlayer, currentGuard)) return false
        val actualRoute = currentTrackRoutes()
        if (!isCurrentGuard(epoch, probePlayer, currentGuard)) return false
        val currentGuardIsMeasured = actualRoute == setOf(currentGuard) && currentGuard !in collidingOutputIds
        if (!currentGuardIsMeasured && probePlayer.isPlaying) {
            headsetGuard.pauseForSafety(probePlayer)
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

    /** Framework-reported devices are prediction, never actual-track proof. */
    private fun routedIds(): Set<String>? = localOutputs.predictedIds()

    /** Kind half only; product names never reach logs. */
    private fun kindOf(id: String): String = id.substringBefore(':')

    /** Engine-specific effect port; all state is read from its existing owner, attachment and inventory. */
    private inner class HandoffAccess : OutputHandoffEffects.Source {
        override val owner: OutputIntentOwner get() = outputIntentOwner
        override val guard: HeadsetRouteGuard get() = headsetGuard
        override val preferences: RoutePreferenceEffects get() = preferenceEffects
        override val positions: PositionCheckpointEffects? get() = this@AudioOutputRouter.positions.handlers

        override fun player(): ExoPlayer? = this@AudioOutputRouter.player
        override fun observation(): AudioTrackObservation? = currentTrackObservation()

        override fun targets(): OutputProofTargets {
            val eligible = live - collidingOutputIds
            val cars = outputs.value.filter {
                it.id in eligible && it.role == com.example.shelfplayer.core.model.playback.AudioOutputRole.Car
            }.map { it.id }.toSet()
            return OutputProofTargets(eligible, cars, currentProjection())
        }

        override fun selected(selection: OutputIntentOwner.Selection) {
            automaticProtection = null
            val id = selection.outputId
            if (id == null) headsetGuard.clearOwnership() else headsetGuard.protect(id)
            _selectedId.value = id
            publish()
        }

        override fun releasedRouteId(): String? = lastHeardRouteId()

        override fun enqueue(epoch: Long, effect: () -> Unit) {
            applicationScope.launch { preferenceEffects.run(epoch) { effect() } }
        }

        override fun requestPreference(player: ExoPlayer, outputId: String?): Boolean =
            localOutputs.requestPreference(player, outputId)

        override fun changed() {
            publish()
        }
    }

    private companion object {
        val ROUTE_SETTLE_DELAY = 400.milliseconds
    }
}

/**
 * Pure route-safety guard used by the shared router's actual publish callback.
 * The caller prefers a measured AudioTrack sink over AudioManager's predicted output.
 * Unknown, multiple and other sinks fail closed even when a preference was accepted.
 */
private fun statusFor(lock: OutputIntentOwner.Lock?): AudioOutputLockStatus? = when (lock?.phase) {
    OutputIntentOwner.LockPhase.Active -> AudioOutputLockStatus.Active
    OutputIntentOwner.LockPhase.Waiting -> AudioOutputLockStatus.Waiting
    OutputIntentOwner.LockPhase.Unverified -> AudioOutputLockStatus.Unverified
    null -> null
}

/** No guard is distinct from a guard that cannot safely prepare this Play. */
internal enum class ManualPlayProtection { NoGuard, MutedAllowed, Denied }

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
