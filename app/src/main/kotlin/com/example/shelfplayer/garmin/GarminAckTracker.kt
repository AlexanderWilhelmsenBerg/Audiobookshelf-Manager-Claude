package com.example.shelfplayer.garmin

import javax.inject.Inject

internal sealed interface GarminAckResult {
    data class Accepted(val type: GarminMessageType) : GarminAckResult
    data object Duplicate : GarminAckResult
    data object Stale : GarminAckResult
}

internal class GarminAckTracker @Inject constructor() {
    private val pending = linkedMapOf<String, GarminMessageType>()
    private val processed = LinkedHashSet<String>()

    fun record(
        id: String,
        type: GarminMessageType,
    ) {
        pending[id] = type
        while (pending.size > MAX_PENDING_MESSAGES) {
            pending.remove(pending.keys.first())
        }
    }

    fun acknowledge(id: String): GarminAckResult {
        if (id in processed) return GarminAckResult.Duplicate
        val type = pending.remove(id) ?: return GarminAckResult.Stale

        processed += id
        while (processed.size > MAX_ACK_HISTORY) {
            processed.remove(processed.first())
        }
        return GarminAckResult.Accepted(type)
    }

    private companion object {
        const val MAX_PENDING_MESSAGES = 32
        const val MAX_ACK_HISTORY = 64
    }
}
