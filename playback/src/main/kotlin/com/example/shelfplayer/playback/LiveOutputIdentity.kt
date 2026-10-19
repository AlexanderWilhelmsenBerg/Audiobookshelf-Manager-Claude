package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.DeviceKind

/**
 * Permission-free, process-local collision evidence shared by the output chooser and
 * connection-policy observer. It never invents a physical identity from an advertised name.
 *
 * Android's AudioDeviceInfo.id is a live handle, not a durable hardware identifier.
 * Stored ROUTE-002 policies deliberately keep their older logical keys.
 */
internal object LiveOutputIdentity {
    data class Handle(val platformId: Int, val logicalId: String, val kind: DeviceKind)

    data class Snapshot(val ambiguousIds: Set<String>) {
        fun isAmbiguous(id: String): Boolean = id in ambiguousIds

        /** A logical policy route cannot verify an endpoint if multiple handles share its identity. */
        fun safeRoutedIds(reported: Set<String>?): Set<String>? =
            reported?.takeUnless { routes -> routes.any(ambiguousIds::contains) }
    }

    fun classify(handles: List<Handle>): Snapshot {
        val grouped = handles.distinctBy { it.platformId to it.logicalId }.groupBy(Handle::logicalId)
        val ambiguous = grouped.filterValues { group ->
            group.map(Handle::platformId).distinct().size > 1 &&
                group.any { it.kind == DeviceKind.Bluetooth ||
                    it.kind == DeviceKind.HearingAid ||
                    it.kind == DeviceKind.Other }
        }.keys
        // Wired/car/speaker logical keys are intentionally shared categories, not claims of unique devices.
        return Snapshot(ambiguousIds = ambiguous)
    }
}
