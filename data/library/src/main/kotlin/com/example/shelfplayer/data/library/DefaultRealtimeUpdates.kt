package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.realtime.RealtimeEvent
import com.example.shelfplayer.core.model.realtime.RealtimeStatus
import com.example.shelfplayer.core.network.gateway.RealtimeConnection
import com.example.shelfplayer.domain.realtime.RealtimeUpdates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC 9.1 — the adapter that lets `:domain` observe a connection it cannot name.
 *
 * A pass-through, and deliberately nothing more. The temptation is to filter or transform here; the
 * reason not to is that this is the only class that can see both sides, so anything it does is
 * invisible from either. Behaviour belongs in the connection or in the use case, both of which are
 * testable on their own.
 */
@Singleton
class DefaultRealtimeUpdates @Inject constructor(private val connection: RealtimeConnection) : RealtimeUpdates {
    override val status: StateFlow<RealtimeStatus> get() = connection.status

    override fun events(profileId: ProfileId): Flow<RealtimeEvent> = connection.events(profileId)
}
