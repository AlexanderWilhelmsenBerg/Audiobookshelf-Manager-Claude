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

    internal data class Selection(val sequence: Long, val outputId: String?, val destination: Destination)

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
        )
        return selection
    }

    fun keepHere(outputId: String, generation: Long, effectEpoch: Long): Lock {
        require(outputId.isNotBlank())
        val lock = Lock(LockPhase.Active, outputId, generation, nextSequence + 1, effectEpoch)
        selectAndLock(outputId, Destination.Output, lock)
        return lock
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
        _state.value = _state.value.copy(lock = null)
    }

    fun releaseRuntime() = _state.value.let { current ->
        _state.value = current.copy(lock = null, releasedRouteId = null, activeBookGeneration = null)
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
        )
    }

    private inline fun updateLock(expected: Lock, transform: (Lock) -> Lock) {
        val lock = _state.value.lock ?: return
        if (lock != expected) return
        _state.value = _state.value.copy(lock = transform(lock))
    }
}
