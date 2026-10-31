package com.example.shelfplayer.playback

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * One process-scoped authority for explicit destination intent and the transient verified-output lock.
 * Mutations share the router's Media3 application dispatcher; consumers receive read-only projections.
 */
internal class OutputIntentOwner {
    internal enum class Destination { Automatic, Car, Output }
    internal enum class LockPhase { Active, Waiting, Unverified }
    internal enum class TransferPhase { Waiting, Verifying, Verified, Delegated, Failed }

    internal data class Selection(val sequence: Long, val outputId: String?, val destination: Destination)

    /** Immutable request claim; the current selection and transfer state are owned only by [Snapshot]. */
    internal data class TransferRequest(
        val selection: Selection,
        val effectEpoch: Long,
        val bookGeneration: Long?,
        val originalPath: AudioTrackPath?,
    )

    internal data class Transfer(val request: TransferRequest, val phase: TransferPhase)

    internal data class PreparedTransfer(val expected: Snapshot, val request: TransferRequest, val reservation: Long)

    internal data class Lock(
        val phase: LockPhase,
        val outputId: String,
        val bookGeneration: Long?,
        val selectionSequence: Long,
        val effectEpoch: Long,
    )

    internal data class Snapshot(
        val selection: Selection?,
        val lock: Lock?,
        val releasedRouteId: String? = null,
        val activeBookGeneration: Long? = null,
        val transfer: Transfer? = null,
    )

    private var nextSequence = 0L
    private val _state = MutableStateFlow(Snapshot(selection = null, lock = null))
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    val selection: StateFlow<Selection?> = object : StateFlow<Selection?> {
        override val value: Selection? get() = _state.value.selection
        override val replayCache: List<Selection?> get() = listOf(value)
        override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<Selection?>): Nothing {
            _state.map { it.selection }.distinctUntilChanged().collect(collector)
            awaitCancellation()
        }
    }

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    val lock: StateFlow<Lock?> = object : StateFlow<Lock?> {
        override val value: Lock? get() = _state.value.lock
        override val replayCache: List<Lock?> get() = listOf(value)
        override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<Lock?>): Nothing {
            _state.map { it.lock }.distinctUntilChanged().collect(collector)
            awaitCancellation()
        }
    }

    fun select(outputId: String?, destination: Destination, releasedRouteId: String? = null): Selection =
        selectAndLock(outputId, destination, null, releasedRouteId)

    private fun selectAndLock(
        outputId: String?,
        destination: Destination,
        lock: Lock?,
        releasedRouteId: String? = null,
    ): Selection {
        val selection = Selection(++nextSequence, outputId, destination)
        val old = _state.value
        _state.value = old.copy(
            selection = selection,
            lock = lock,
            releasedRouteId = if (destination == Destination.Automatic) releasedRouteId else null,
            transfer = null,
        )
        return selection
    }

    fun keepHere(outputId: String, generation: Long, effectEpoch: Long): Lock {
        require(outputId.isNotBlank())
        val lock = Lock(LockPhase.Active, outputId, generation, nextSequence + 1, effectEpoch)
        selectAndLock(outputId, Destination.Output, lock)
        return lock
    }

    /** Reserve the existing sequence without publication: mute/checkpoint must precede listener callbacks. */
    fun prepareTransfer(
        outputId: String?,
        destination: Destination,
        effectEpoch: Long,
        originalPath: AudioTrackPath?,
    ): PreparedTransfer {
        require(destination != Destination.Automatic)
        require(destination != Destination.Output || !outputId.isNullOrBlank())
        val current = _state.value
        val selection = Selection(++nextSequence, outputId, destination)
        return PreparedTransfer(
            current,
            TransferRequest(selection, effectEpoch, current.activeBookGeneration, originalPath),
            nextSequence,
        )
    }

    /** Later deliberate Play revalidates the existing choice; it is not another listener selection. */
    fun prepareExistingTransfer(effectEpoch: Long, originalPath: AudioTrackPath?): PreparedTransfer? {
        val current = _state.value
        val selection = current.selection?.takeUnless { it.destination == Destination.Automatic } ?: return null
        return PreparedTransfer(
            current,
            TransferRequest(selection, effectEpoch, current.activeBookGeneration, originalPath),
            nextSequence,
        )
    }

    fun publishTransfer(prepared: PreparedTransfer, phase: TransferPhase): Boolean {
        require(phase == TransferPhase.Waiting || phase == TransferPhase.Verifying)
        if (_state.value !== prepared.expected || nextSequence != prepared.reservation) return false
        val request = prepared.request
        _state.value = prepared.expected.copy(
            selection = request.selection,
            transfer = Transfer(request, phase),
            releasedRouteId = null,
        )
        return _state.value.selection == request.selection && _state.value.transfer?.request == request
    }

    /** A failed attempt keeps the prior lock; proof may commit only from the current Verifying phase. */
    fun transitionTransfer(expected: TransferRequest, phase: TransferPhase, retainedLock: Lock? = null): Boolean {
        val current = _state.value
        val transfer = current.transfer?.takeIf { it.request == expected } ?: return false
        if (current.selection != expected.selection ||
            current.activeBookGeneration != expected.bookGeneration
        ) {
            return false
        }
        val verified = phase == TransferPhase.Verified || phase == TransferPhase.Delegated
        if (verified && (transfer.phase != TransferPhase.Verifying || expected.bookGeneration == null)) return false
        val lock = when {
            verified -> current.lock?.takeIf {
                it.selectionSequence == expected.selection.sequence && it.outputId == expected.selection.outputId
            }?.copy(
                phase = LockPhase.Active,
                bookGeneration = expected.bookGeneration,
                effectEpoch = expected.effectEpoch,
            )

            phase == TransferPhase.Failed -> (current.lock ?: retainedLock)?.copy(phase = LockPhase.Waiting)

            else -> current.lock ?: retainedLock
        }
        _state.value = current.copy(lock = lock, transfer = transfer.copy(phase = phase))
        return _state.value.transfer == transfer.copy(phase = phase)
    }

    fun markWaiting(expected: Lock) = updateLock(expected) { it.copy(phase = LockPhase.Waiting) }
    fun markUnverified(expected: Lock) = updateLock(expected) { it.copy(phase = LockPhase.Unverified) }

    /** Existing generation/attachment boundaries retire proof, while preserving a nonempty logical target. */
    fun invalidateProof(expected: Lock) =
        updateLock(expected) { it.copy(phase = LockPhase.Waiting, bookGeneration = null) }

    fun activateProof(expected: Lock, generation: Long, effectEpoch: Long) = updateLock(expected) {
        it.copy(phase = LockPhase.Active, bookGeneration = generation, effectEpoch = effectEpoch)
    }

    /** Stop may retain Media3's loaded queue, so it releases the lock without inventing a book change. */
    fun onPlaybackStopped() {
        val current = _state.value
        _state.value = current.copy(lock = null, transfer = current.transfer?.copy(phase = TransferPhase.Waiting))
    }

    fun releaseRuntime() = _state.value.let { current ->
        _state.value = current.copy(lock = null, releasedRouteId = null, activeBookGeneration = null, transfer = null)
    }

    fun isReleasedRoute(outputId: String): Boolean = _state.value.releasedRouteId == outputId

    /** Positive evidence of a different route clears the Automatic release block; unknown evidence does not. */
    fun reportHeardRoute(outputId: String) {
        val current = _state.value
        if (current.releasedRouteId != null && current.releasedRouteId != outputId) {
            _state.value = current.copy(releasedRouteId = null)
        }
    }

    fun onOutputDisconnected(connectedIds: Set<String>) {
        val current = _state.value
        if (current.releasedRouteId != null && current.releasedRouteId !in connectedIds) {
            _state.value = current.copy(releasedRouteId = null)
        }
    }

    /** Queue/book ownership changes permit a later Automatic observation to establish a new route. */
    fun onBookChanged(generation: Long?) {
        val current = _state.value
        if (generation == null) {
            _state.value = current.copy(
                releasedRouteId = null,
                activeBookGeneration = null,
                lock = null,
                transfer = null,
            )
            return
        }
        _state.value = current.copy(
            releasedRouteId = null,
            activeBookGeneration = generation,
            lock = current.lock?.copy(
                phase = LockPhase.Waiting,
                bookGeneration = null,
            ),
            transfer = current.transfer?.let {
                Transfer(it.request.copy(bookGeneration = generation), TransferPhase.Waiting)
            },
        )
    }

    private inline fun updateLock(expected: Lock, transform: (Lock) -> Lock) {
        val lock = _state.value.lock ?: return
        if (lock != expected) return
        _state.value = _state.value.copy(lock = transform(lock))
    }
}
