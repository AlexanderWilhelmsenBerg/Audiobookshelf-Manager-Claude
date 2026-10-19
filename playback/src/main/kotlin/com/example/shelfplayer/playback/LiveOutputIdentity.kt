package com.example.shelfplayer.playback

/**
 * Privacy-preserving live handle resolution. A BookWave logical ID is a category/name-based
 * policy key, NOT a proof of one physical Bluetooth sink. Android's numeric handle IDs only
 * distinguish currently enumerated outputs, and must never be persisted or exported.
 */
internal object LiveOutputIdentity {
    data class Handle(val platformId: Int, val logicalId: String)

    data class Snapshot(
        val uniqueHandleByLogicalId: Map<String, Int>,
        val ambiguousLogicalIds: Set<String>,
        val presentLogicalIds: Set<String>,
    ) {
        fun isAmbiguous(logicalId: String): Boolean = logicalId in ambiguousLogicalIds

        /** Do not claim that a grouped name identifies the actual route. */
        fun safePredictedRoutes(routedIds: Set<String>?): Set<String>? =
            if (routedIds?.any { it in ambiguousLogicalIds } == true) null else routedIds
    }

    fun snapshot(handles: List<Handle>): Snapshot {
        // Android can repeat a callback for one handle. Count distinct handles, not callbacks.
        val groups = handles.distinctBy(Handle::platformId).groupBy(Handle::logicalId)
        return Snapshot(
            uniqueHandleByLogicalId = groups.filterValues { it.size == 1 }
                .mapValues { (_, rows) -> rows.single().platformId },
            ambiguousLogicalIds = groups.filterValues { it.size > 1 }.keys,
            presentLogicalIds = groups.keys,
        )
    }
}
