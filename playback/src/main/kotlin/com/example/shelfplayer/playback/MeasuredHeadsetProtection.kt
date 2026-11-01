package com.example.shelfplayer.playback

import android.os.Build
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Scoped Automatic-route protection, using the router's existing intent, guard and preference executor. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MeasuredHeadsetProtection(
    private val scope: CoroutineScope,
    private val effects: RoutePreferenceEffects,
    private val outputs: AndroidLocalOutputAdapter,
    private val guard: HeadsetRouteGuard,
    private val owner: OutputIntentOwner,
    private val selected: MutableStateFlow<String?>,
    private val player: () -> ExoPlayer?,
    private val routes: () -> Set<String>?,
    private val publish: () -> Unit,
) {
    data class Claim(
        val outputId: String,
        val generation: Long,
        val selectionSequence: Long,
        val preferenceEpoch: Long,
        val nativeHandle: Int?,
        val preferredDeviceRequested: Boolean = false,
    ) {
        fun matches(id: String, bookGeneration: Long, sequence: Long, handle: Int?): Boolean =
            outputId == id && generation == bookGeneration && selectionSequence == sequence && nativeHandle == handle
    }

    var claim: Claim? = null
        private set

    /** Retires bookkeeping only; the owning router performs guard/probe cleanup in its existing order. */
    fun clear() {
        claim = null
    }

    fun markRequested(id: String, epoch: Long) {
        claim = claim?.takeIf { it.outputId == id }?.copy(preferenceEpoch = epoch, preferredDeviceRequested = true)
    }

    fun protect(id: String, generation: Long, sequence: Long, isCurrentClaim: () -> Boolean): Boolean {
        if (!canProtect(id, sequence)) return false
        val matching = claim?.matches(id, generation, sequence, outputs.nativeHandle(id)) == true
        if (matching && selected.value == id && guard.endpointId == id) return true
        val attached = player() ?: return false
        val epoch = effects.snapshot()
        val measured = routes()
        val sameAttachment = effects.isCurrent(epoch) && player() === attached
        return when {
            measured != setOf(id) || !sameAttachment -> false

            !canProtect(id, sequence) || !isCurrentClaim() -> false

            else -> {
                val protection = Claim(id, generation, sequence, effects.supersede(), outputs.nativeHandle(id))
                if (selected.value == null) selected.value = id
                guard.protect(id)
                claim = protection
                requestPreference(protection, attached, isCurrentClaim)
                publish()
                true
            }
        }
    }

    /** One native request, with checks on both sides of route reads that may synchronously deliver callbacks. */
    private fun requestPreference(expected: Claim, attached: ExoPlayer, isCurrentClaim: () -> Boolean) {
        val isCurrent = { player() === attached && claim === expected && isCurrentClaim() }
        scope.launch {
            effects.run(expected.preferenceEpoch) {
                if (!isCurrent() || routes() != setOf(expected.outputId)) return@run
                val sameHandle = outputs.nativeHandle(expected.outputId) == expected.nativeHandle
                if (!effects.isCurrent(expected.preferenceEpoch) || !isCurrent() || !sameHandle) return@run
                // Set before the native call so reentrant book/selection cleanup releases this request.
                claim = expected.copy(preferredDeviceRequested = true)
                outputs.requestPreference(attached, expected.outputId)
            }
        }
    }

    private fun canProtect(id: String, sequence: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || id !in outputs.endpointIds ||
            id in outputs.collidingOutputIds
        ) {
            return false
        }
        val selection = owner.selection.value
        if (selection?.destination == OutputIntentOwner.Destination.Car ||
            (selection?.sequence ?: 0L) != sequence
        ) {
            return false
        }
        return (selected.value == null || selected.value == id) && (guard.endpointId == null || guard.endpointId == id)
    }
}
