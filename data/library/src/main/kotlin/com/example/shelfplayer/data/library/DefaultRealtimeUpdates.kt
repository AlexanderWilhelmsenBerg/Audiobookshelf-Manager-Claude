package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.realtime.RealtimeEvent
import com.example.shelfplayer.core.model.realtime.RealtimeStatus
import com.example.shelfplayer.core.network.gateway.RealtimeConnection
import com.example.shelfplayer.domain.realtime.RealtimeUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.shareIn
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC 9.1 / SYNC-002 — the adapter that lets `:domain` observe a connection it cannot name.
 *
 * ### Why this is no longer a pass-through
 *
 * It was, and the comment here said the temptation to do anything else should be resisted. What changed is
 * the number of collectors. `RealtimeConnection.events` is a `callbackFlow` that opens a socket *per
 * collector* — which was invisible while exactly one screen collected it, and became a second websocket to
 * somebody's home server the moment MGR-007 needed the task events on the book screen.
 *
 * So the flow is shared here, per profile. One socket, however many readers, and the connection still lives
 * only while something is listening — `WhileSubscribed` keeps that property, which is what
 * `RealtimeConnection` documents and what keeps a backgrounded app off the network.
 *
 * ### Why per profile and not one flow
 *
 * Because the profile is what the socket authenticates as. A shared flow keyed on nothing would hand a
 * second profile the first one's events, which is precisely the boundary PRODUCT_SPEC 5.2 draws.
 *
 * ### Why no replay
 *
 * A realtime event is news, and news that arrives twice is worse than news that arrives once. A late
 * subscriber that replayed a `task_finished` would report an embed as just-completed every time the book
 * screen was reopened. The screen's own state holds what it has already seen.
 */
@Singleton
class DefaultRealtimeUpdates @Inject constructor(
    private val connection: RealtimeConnection,
    @param:ApplicationScope private val scope: CoroutineScope,
) : RealtimeUpdates {
    override val status: StateFlow<RealtimeStatus> get() = connection.status

    private val shared = ConcurrentHashMap<String, Flow<RealtimeEvent>>()

    override fun events(profileId: ProfileId): Flow<RealtimeEvent> = shared.computeIfAbsent(profileId.value) {
        connection.events(profileId).shareIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(),
            replay = 0,
        )
    }
}
