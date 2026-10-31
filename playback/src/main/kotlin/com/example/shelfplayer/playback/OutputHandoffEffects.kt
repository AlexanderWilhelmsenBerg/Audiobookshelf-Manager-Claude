package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException

/** Effect orchestration only. The intent owner and existing Probe remain the only mutable state owners. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class OutputHandoffEffects(private val source: Source) {
    internal interface Source {
        val owner: OutputIntentOwner
        val guard: HeadsetRouteGuard
        val preferences: RoutePreferenceEffects
        val positions: PositionCheckpointEffects?
        fun player(): ExoPlayer?
        fun observation(): AudioTrackObservation?
        fun endpointRoutes(): Set<String>?
        fun stage(probe: HeadsetRouteGuard.Probe, stage: OutputTransferStage)
        fun targets(): OutputProofTargets
        fun selected(selection: OutputIntentOwner.Selection)
        fun releasedRouteId(): String?
        fun enqueue(epoch: Long, effect: () -> Unit)
        fun requestPreference(player: ExoPlayer, outputId: String?): Boolean
        fun changed()
    }

    private data class Origin(
        val player: ExoPlayer?,
        val item: MediaItem?,
        val context: MutedPositionEffects.Context?,
        val owner: OutputIntentOwner.Snapshot,
        val epoch: Long,
    )

    private data class Claim(
        val request: OutputIntentOwner.TransferRequest,
        val player: ExoPlayer,
        val item: MediaItem?,
        val context: MutedPositionEffects.Context,
    )

    private data class ReleaseClaim(val origin: Origin, val routeId: String?)
    private data class Rollback(val claim: Claim, val original: AudioTrackPath.Endpoint)

    /** Policy reassertion reuses a still-owned request; it cannot reset that request's physical epoch. */
    fun isPendingFor(id: String): Boolean {
        val probe = source.guard.currentProbe ?: return false
        val request = probe.transferRequest ?: return false
        return request.selection.outputId == id && currentRequest(request) &&
            source.positions?.isAuthorized(probe) == true
    }

    /** A physical effect epoch may be retired without changing listener intent. Fail closed in that scope. */
    fun invalidate(probe: HeadsetRouteGuard.Probe) {
        val claim = claimOf(probe) ?: return
        val current = source.owner.state.value
        val stillOwned = current.selection == claim.request.selection &&
            current.transfer?.request == claim.request && current.activeBookGeneration == claim.request.bookGeneration
        if (stillOwned && playbackMatches(claim) && claim.player.playWhenReady) {
            source.guard.pauseForSafety(claim.player)
        }
    }

    fun select(id: String?, destination: OutputIntentOwner.Destination): Boolean {
        if (destination == OutputIntentOwner.Destination.Automatic) return releaseAutomatic()
        val origin = prepareOrigin() ?: return false
        val path = source.observation()?.path
        if (!unchanged(origin)) return false
        val prepared = source.owner.prepareTransfer(id, destination, source.preferences.supersede(), path)
        return start(prepared, origin, forPlay = false)
    }

    /** Confirmed departure may expire an unlocked paused Car choice, without release suppression or Play. */
    fun retirePausedCar(expected: OutputIntentOwner.Selection?): Boolean {
        val origin = pausedCarOrigin(expected) ?: return false
        val active = source.guard.currentProbe
        val epoch = source.preferences.supersede()
        source.guard.clearOwnership()
        if (active != null) source.guard.cancelProbe(active)
        if (!canRetireCar(expected) || !selectionScope(origin, expected, epoch)) return false
        val selection = source.owner.select(null, OutputIntentOwner.Destination.Automatic)
        if (!selectionScope(origin, selection, epoch)) return false
        source.selected(selection)
        return selectionScope(origin, selection, epoch)
    }

    private fun canRetireCar(expected: OutputIntentOwner.Selection?): Boolean {
        val current = source.owner.state.value
        val sameCar = expected?.destination == OutputIntentOwner.Destination.Car && current.selection == expected
        val pausedUnlocked = current.lock == null && source.player()?.playWhenReady == false
        return sameCar && pausedUnlocked && !source.targets().projection
    }

    private fun pausedCarOrigin(expected: OutputIntentOwner.Selection?): Origin? {
        if (!canRetireCar(expected)) return null
        val origin = prepareOrigin() ?: return null
        val active = source.guard.currentProbe
        val matchingProbe = active == null || active.transferRequest?.selection == expected
        return origin.takeIf { matchingProbe && canRetireCar(expected) && unchanged(it) }
    }

    /** The caller owns the one raw Play. This method only reserves its mute and requests the existing destination. */
    fun protectPlay(): ManualPlayProtection {
        val selection = source.owner.selection.value
        if (selection?.destination == OutputIntentOwner.Destination.Output &&
            selection.outputId !in source.targets().eligible
        ) {
            return ManualPlayProtection.Denied
        }
        val active = source.guard.currentProbe
        if (active?.transferRequest != null && currentRequest(active.transferRequest) &&
            source.positions?.isAuthorized(active) == true
        ) {
            return ManualPlayProtection.MutedAllowed
        }
        return prepareExistingPlay()
    }

    private fun prepareExistingPlay(): ManualPlayProtection {
        val origin = prepareOrigin() ?: return ManualPlayProtection.Denied
        val path = source.observation()?.path
        if (!unchanged(origin)) return ManualPlayProtection.Denied
        val prepared = source.owner.prepareExistingTransfer(origin.epoch, path) ?: return ManualPlayProtection.Denied
        return if (start(
                prepared,
                origin,
                forPlay = true,
            )
        ) {
            ManualPlayProtection.MutedAllowed
        } else {
            ManualPlayProtection.Denied
        }
    }

    private fun prepareOrigin(): Origin? {
        val current = source.player()
        val origin = Origin(
            current,
            current?.currentMediaItem,
            source.positions?.authority(),
            source.owner.state.value,
            source.preferences.snapshot(),
        )
        if (current != null && source.positions?.beforeTransfer() != true) return null
        return origin.takeIf(::unchanged)
    }

    private fun unchanged(origin: Origin): Boolean {
        val current = source.player()
        val playerMatches = current === origin.player && current?.currentMediaItem === origin.item
        val contextMatches = source.positions?.authority() == origin.context
        return playerMatches && contextMatches && source.owner.state.value === origin.owner &&
            source.preferences.isCurrent(origin.epoch)
    }

    private fun start(prepared: OutputIntentOwner.PreparedTransfer, origin: Origin, forPlay: Boolean): Boolean {
        val current = origin.player ?: return publishUnloaded(prepared)
        val context = origin.context ?: return false
        val claim = Claim(prepared.request, current, origin.item, context)
        return if (origin.item == null) publishEmptyPlayer(prepared, claim) else startLoaded(prepared, claim, forPlay)
    }

    private fun publishEmptyPlayer(prepared: OutputIntentOwner.PreparedTransfer, claim: Claim): Boolean {
        if (claim.player.playWhenReady && !source.guard.pauseForSafety(claim.player)) return false
        if (!playbackMatches(claim)) return false
        return publishUnloaded(prepared).also { if (it) enqueuePreference(claim, verify = false) }
    }

    private fun startLoaded(prepared: OutputIntentOwner.PreparedTransfer, claim: Claim, forPlay: Boolean): Boolean {
        val preserved = if (forPlay) null else preserveCurrentOutput(prepared, claim)
        return preserved ?: startMuted(prepared, claim, forPlay)
    }

    private fun startMuted(prepared: OutputIntentOwner.PreparedTransfer, claim: Claim, forPlay: Boolean): Boolean {
        val verify = forPlay || claim.player.playWhenReady
        val accepted = source.guard.beginTransfer(prepared.request, claim.player) { active ->
            playbackMatches(claim) && source.owner.publishTransfer(prepared, OutputIntentOwner.TransferPhase.Waiting) &&
                source.positions?.bindTransferScope(active, prepared.request) == true
        }
        if (!accepted || !isCurrent(claim)) return false
        val active = source.guard.currentProbe ?: return false
        source.selected(prepared.request.selection)
        if (!isCurrent(claim)) return false
        enqueuePreference(claim, verify)
        if (!verify) source.guard.cancelProbe(active)
        return true
    }

    /** New listener intent need not perturb an already measured output. No pending mute may be bypassed. */
    private fun preserveCurrentOutput(prepared: OutputIntentOwner.PreparedTransfer, claim: Claim): Boolean? {
        if (claim.request.bookGeneration == null || source.guard.preservablePlayer !== claim.player ||
            !claim.player.isPlaying
        ) {
            return null
        }
        val measured = measureProof(claim.request)?.takeIf { it.permitsAudio } ?: return null
        if (!playbackMatches(claim) || !source.preferences.isCurrent(claim.request.effectEpoch)) return false
        return publishPreservation(prepared, claim, measured)
    }

    private fun publishPreservation(
        prepared: OutputIntentOwner.PreparedTransfer,
        claim: Claim,
        measured: MutedProbeCompletion,
    ): Boolean {
        val previousLock = prepared.expected.lock
        if (!source.owner.publishTransfer(prepared, OutputIntentOwner.TransferPhase.Verifying)) return false
        source.selected(claim.request.selection)
        if (!isCurrent(claim)) return false
        if (!confirmsPreservedOutput(claim, measured)) {
            return refusePreservation(claim, previousLock)
        }
        val phase = if (measured == MutedProbeCompletion.Delegated) {
            OutputIntentOwner.TransferPhase.Delegated
        } else {
            OutputIntentOwner.TransferPhase.Verified
        }
        val published = source.owner.transitionTransfer(claim.request, phase)
        return if (published && confirmsPreservedOutput(claim, measured)) {
            true
        } else {
            refusePreservation(claim, previousLock)
        }
    }

    private fun confirmsPreservedOutput(claim: Claim, measured: MutedProbeCompletion): Boolean =
        isCurrent(claim) && measureProof(claim.request) == measured && isCurrent(claim)

    private fun refusePreservation(claim: Claim, previousLock: OutputIntentOwner.Lock?): Boolean {
        if (isCurrent(claim)) source.guard.pauseForSafety(claim.player)
        if (isCurrent(claim)) {
            source.owner.transitionTransfer(claim.request, OutputIntentOwner.TransferPhase.Failed, previousLock)
        }
        return false
    }

    private fun publishUnloaded(prepared: OutputIntentOwner.PreparedTransfer): Boolean {
        if (!source.owner.publishTransfer(prepared, OutputIntentOwner.TransferPhase.Waiting)) return false
        source.selected(prepared.request.selection)
        return currentRequest(prepared.request)
    }

    private fun enqueuePreference(claim: Claim, verify: Boolean) = source.enqueue(claim.request.effectEpoch) {
        if (isCurrent(claim) && source.owner.state.value.transfer?.phase != OutputIntentOwner.TransferPhase.Failed) {
            requestPreference(claim, verify)
        }
    }

    private fun requestPreference(claim: Claim, verify: Boolean) {
        val issued = tryPreference(claim.player, claim.request.selection.outputId)
        if (!isCurrent(claim)) return
        if (issued) {
            if (verify) source.owner.transitionTransfer(claim.request, OutputIntentOwner.TransferPhase.Verifying)
            source.changed()
        } else {
            fail(claim)
        }
    }

    private fun tryPreference(player: ExoPlayer, outputId: String?): Boolean = try {
        source.requestPreference(player, outputId)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: IllegalStateException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: UnsupportedOperationException) {
        false
    }

    private fun fail(claim: Claim) {
        val active = source.guard.currentProbe?.takeIf { it.transferRequest == claim.request }
        if (active != null && source.positions?.isAuthorized(active) == true) {
            source.guard.quiesceProbe(active)
            rollback(active)
            if (!isCurrent(claim)) return
            source.guard.pauseForSafety(claim.player)
            source.guard.finishProbe(active, verified = false)
        } else {
            source.owner.transitionTransfer(claim.request, OutputIntentOwner.TransferPhase.Failed)
        }
    }

    fun proof(probe: HeadsetRouteGuard.Probe): MutedProbeCompletion? {
        if (!source.guard.isCurrentProbe(probe)) return MutedProbeCompletion.Superseded
        val request = probe.transferRequest ?: return endpointProof(probe)
        if (!currentRequest(request)) return MutedProbeCompletion.Superseded
        return if (source.owner.state.value.transfer?.phase == OutputIntentOwner.TransferPhase.Verifying) {
            measureProof(request)
        } else {
            null
        }
    }

    suspend fun complete(probe: HeadsetRouteGuard.Probe, verified: Boolean): MutedProbeCompletion {
        if (!verified) return refuseProbe(probe)
        val positions = source.positions ?: return MutedProbeCompletion.Refused
        return OutputTransferTransaction(probe, positions, ::proof, ::refuseProbe, source::stage).complete()
    }

    private fun endpointProof(probe: HeadsetRouteGuard.Probe): MutedProbeCompletion? {
        val first = source.endpointRoutes()
        val second = source.endpointRoutes()
        if (!source.guard.isCurrentProbe(probe) || source.positions?.isAuthorized(probe) != true) {
            return MutedProbeCompletion.Superseded
        }
        return when {
            first == setOf(probe.endpointId) && second == first && probe.endpointId in source.targets().eligible ->
                MutedProbeCompletion.Verified

            !first.isNullOrEmpty() || !second.isNullOrEmpty() -> MutedProbeCompletion.Refused

            else -> null
        }
    }

    private fun refuseProbe(probe: HeadsetRouteGuard.Probe): MutedProbeCompletion {
        val positions = source.positions ?: return MutedProbeCompletion.Refused
        if (!source.guard.isCurrentProbe(probe) ||
            !positions.isAuthorized(probe)
        ) {
            return MutedProbeCompletion.Superseded
        }
        rollback(probe)
        if (!source.guard.isCurrentProbe(probe) || !positions.isAuthorized(probe) ||
            !source.guard.pauseForSafety(probe.player)
        ) {
            return MutedProbeCompletion.Superseded
        }
        return if (positions.restoreCurrent(probe)) MutedProbeCompletion.Refused else MutedProbeCompletion.Superseded
    }

    private fun measureProof(request: OutputIntentOwner.TransferRequest): MutedProbeCompletion? {
        val before = source.targets()
        val first = source.observation()
        val second = source.observation()
        val after = source.targets()
        val targets = after.copy(projection = before.projection && after.projection)
        return OutputTargetProof.evaluate(request, first, second, targets)
    }

    fun commit(probe: HeadsetRouteGuard.Probe, proof: MutedProbeCompletion): MutedProbeCompletion {
        if (source.positions?.isAuthorized(probe) != true) return MutedProbeCompletion.Superseded
        val request = probe.transferRequest ?: return commitEndpoint(probe, proof)
        return commitTransfer(probe, request, proof)
    }

    private fun commitEndpoint(probe: HeadsetRouteGuard.Probe, proof: MutedProbeCompletion): MutedProbeCompletion {
        if (source.positions?.isSettled(probe) == true) return proof
        if (source.positions?.isAuthorized(probe) != true || !source.guard.pauseForSafety(probe.player)) {
            return MutedProbeCompletion.Superseded
        }
        source.positions?.restorePausedProbe(probe)
        return if (source.positions?.isAuthorized(probe) == true) {
            MutedProbeCompletion.Refused
        } else {
            MutedProbeCompletion.Superseded
        }
    }

    private fun commitTransfer(
        probe: HeadsetRouteGuard.Probe,
        request: OutputIntentOwner.TransferRequest,
        proof: MutedProbeCompletion,
    ): MutedProbeCompletion {
        val held = probe.positionCheckpoint ?: return MutedProbeCompletion.Refused
        val previousLock = source.owner.lock.value
        if (!currentRequest(request)) return MutedProbeCompletion.Superseded
        val phase = if (proof == MutedProbeCompletion.Delegated) {
            OutputIntentOwner.TransferPhase.Delegated
        } else {
            OutputIntentOwner.TransferPhase.Verified
        }
        if (!source.owner.transitionTransfer(request, phase)) return supersededConfirmation(request, previousLock)
        return confirmPublished(probe, request, held, proof, previousLock)
    }

    /** Synchronous observers may replace the route, selection or held point during status publication. */
    private fun confirmPublished(
        probe: HeadsetRouteGuard.Probe,
        request: OutputIntentOwner.TransferRequest,
        held: MutedPositionCheckpoint,
        proof: MutedProbeCompletion,
        previousLock: OutputIntentOwner.Lock?,
    ): MutedProbeCompletion {
        if (!sameConfirmation(probe, request, held)) return supersededConfirmation(request, previousLock)
        val fresh = measureProof(request)
        val settled = source.positions?.isSettled(probe) == true
        if (!sameConfirmation(probe, request, held)) return supersededConfirmation(request, previousLock)
        return if (fresh == proof && settled) proof else refusePublished(probe, request, held, previousLock)
    }

    private fun sameConfirmation(
        probe: HeadsetRouteGuard.Probe,
        request: OutputIntentOwner.TransferRequest,
        held: MutedPositionCheckpoint,
    ): Boolean = probe.positionCheckpoint === held && currentRequest(request) &&
        source.positions?.isAuthorized(probe) == true

    private fun supersededConfirmation(
        request: OutputIntentOwner.TransferRequest,
        previousLock: OutputIntentOwner.Lock?,
    ): MutedProbeCompletion {
        val phase = source.owner.state.value.transfer?.phase
        if (previousLock != null && phase == OutputIntentOwner.TransferPhase.Verifying && currentRequest(request)) {
            source.owner.transitionTransfer(request, phase, previousLock)
        }
        return MutedProbeCompletion.Superseded
    }

    private fun refusePublished(
        probe: HeadsetRouteGuard.Probe,
        request: OutputIntentOwner.TransferRequest,
        held: MutedPositionCheckpoint,
        previousLock: OutputIntentOwner.Lock?,
    ): MutedProbeCompletion {
        source.owner.transitionTransfer(request, OutputIntentOwner.TransferPhase.Failed, previousLock)
        if (!sameConfirmation(probe, request, held)) return MutedProbeCompletion.Superseded
        if (!source.guard.pauseForSafety(probe.player)) return MutedProbeCompletion.Superseded
        source.positions?.restorePausedProbe(probe)
        return if (sameConfirmation(probe, request, held)) {
            MutedProbeCompletion.Refused
        } else {
            MutedProbeCompletion.Superseded
        }
    }

    /** A seek delivered inside success publication supersedes that proof without creating new listener intent. */
    fun rebased(probe: HeadsetRouteGuard.Probe) {
        val request = probe.transferRequest ?: return
        val phase = source.owner.state.value.transfer?.phase
        val wasConfirmed =
            phase == OutputIntentOwner.TransferPhase.Verified || phase == OutputIntentOwner.TransferPhase.Delegated
        if (wasConfirmed && currentRequest(request) && source.positions?.isAuthorized(probe) == true) {
            source.owner.transitionTransfer(request, OutputIntentOwner.TransferPhase.Verifying)
        }
    }

    /** A previous preference may be restored only from fresh unique proof while the failed attempt still owns mute. */
    fun rollback(probe: HeadsetRouteGuard.Probe) {
        val rollback = prepareRollback(probe) ?: return
        val first = source.observation() ?: return
        val second = source.observation()
        if (first != second || first.path != rollback.original ||
            rollback.original.id !in source.targets().eligible
        ) {
            return
        }
        if (isCurrent(rollback.claim) && source.positions?.isAuthorized(probe) == true) {
            tryPreference(rollback.claim.player, rollback.original.id)
        }
    }

    private fun prepareRollback(probe: HeadsetRouteGuard.Probe): Rollback? {
        val original = probe.transferRequest?.originalPath as? AudioTrackPath.Endpoint ?: return null
        val claim = claimOf(probe) ?: return null
        return Rollback(claim, original).takeIf { isCurrent(claim) && source.positions?.isAuthorized(probe) == true }
    }

    fun cancelled(probe: HeadsetRouteGuard.Probe) {
        val request = probe.transferRequest ?: return
        if (source.guard.currentProbe?.transferRequest == request) return
        if (source.owner.state.value.transfer?.phase == OutputIntentOwner.TransferPhase.Verifying) {
            source.owner.transitionTransfer(request, OutputIntentOwner.TransferPhase.Waiting)
        }
    }

    fun settled(probe: HeadsetRouteGuard.Probe, completion: MutedProbeCompletion) {
        if (completion.permitsAudio || completion == MutedProbeCompletion.Superseded) return
        val claim = claimOf(probe) ?: return
        if (isCurrent(claim)) source.owner.transitionTransfer(claim.request, OutputIntentOwner.TransferPhase.Failed)
    }

    private fun claimOf(probe: HeadsetRouteGuard.Probe): Claim? {
        val request = probe.transferRequest ?: return null
        val held = probe.positionCheckpoint ?: return null
        return Claim(
            request,
            probe.player,
            held.item,
            MutedPositionEffects.Context(
                held.controllerCommandGeneration,
                held.mediaItemGeneration,
                held.profileGeneration,
            ),
        )
    }

    private fun playbackMatches(claim: Claim): Boolean = source.player() === claim.player &&
        claim.player.currentMediaItem === claim.item && source.positions?.authority() == claim.context

    private fun isCurrent(claim: Claim): Boolean = playbackMatches(claim) && currentRequest(claim.request)

    private fun currentRequest(request: OutputIntentOwner.TransferRequest): Boolean {
        val current = source.owner.state.value
        return current.selection == request.selection && current.activeBookGeneration == request.bookGeneration &&
            current.transfer?.request == request && source.preferences.isCurrent(request.effectEpoch)
    }

    private fun prepareRelease(): ReleaseClaim? {
        val origin = prepareOrigin() ?: return null
        val active = source.guard.currentProbe
        if (active != null && !source.guard.pauseForSafety(active.player)) return null
        val released = source.releasedRouteId()
        return if (unchanged(origin)) ReleaseClaim(origin, released) else null
    }

    private fun releaseAutomatic(): Boolean {
        val release = prepareRelease() ?: return false
        val active = source.guard.currentProbe
        val epoch = source.preferences.supersede()
        source.guard.clearOwnership()
        if (active != null) source.guard.cancelProbe(active)
        if (!selectionScope(release.origin, release.origin.owner.selection, epoch)) return false
        val selection = source.owner.select(null, OutputIntentOwner.Destination.Automatic, release.routeId)
        if (!selectionScope(release.origin, selection, epoch)) return false
        source.selected(selection)
        source.enqueue(epoch) {
            val current = release.origin.player
            if (current != null && selectionScope(release.origin, selection, epoch) &&
                tryPreference(current, null)
            ) {
                source.changed()
            }
        }
        return true
    }

    private fun selectionScope(origin: Origin, selection: OutputIntentOwner.Selection?, epoch: Long): Boolean {
        val current = source.player()
        val playerMatches = current === origin.player && current?.currentMediaItem === origin.item
        val contextMatches = source.positions?.authority() == origin.context
        return playerMatches && contextMatches && source.owner.selection.value == selection &&
            source.preferences.isCurrent(epoch)
    }
}
