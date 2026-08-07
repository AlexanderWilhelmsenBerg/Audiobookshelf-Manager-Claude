package com.example.shelfplayer.domain.realtime

import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.realtime.RealtimeEvent
import com.example.shelfplayer.core.model.realtime.RealtimeStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * PRODUCT_SPEC SYNC-002 — the domain's view of the realtime connection.
 *
 * `:domain` cannot see `:core:network`, and the presentation layer must not: a ViewModel that could
 * name a websocket is a ViewModel that could open one. This is the same seam shape as every other
 * repository here.
 */
interface RealtimeUpdates {
    val status: StateFlow<RealtimeStatus>

    fun events(profileId: ProfileId): Flow<RealtimeEvent>
}
