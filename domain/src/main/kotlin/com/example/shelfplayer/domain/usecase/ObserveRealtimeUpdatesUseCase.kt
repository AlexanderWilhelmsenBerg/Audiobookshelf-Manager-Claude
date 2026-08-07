package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.realtime.RealtimeEvent
import com.example.shelfplayer.domain.realtime.RealtimeUpdates
import com.example.shelfplayer.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.collect
import javax.inject.Inject

/**
 * PRODUCT_SPEC SYNC-002 / 13.2 — applies what the server pushes, through the paths that already exist.
 *
 * ### Why this writes nothing of its own
 *
 * `user_updated` carries the whole user object, which is the same thing `POST /api/authorize` returns
 * and `SyncAccountUseCase` already knows how to store. Giving the socket its own write path would mean
 * two implementations of "what does a changed account mean", and they would drift — the REST one being
 * the one with the careful rules about not overwriting an unsynced local position.
 *
 * So the event is applied by handing its payload to the same repository call. The socket's contribution
 * is *latency*: the same update, seconds after it happened rather than at the next resume.
 *
 * ### Suspends for as long as it is collected
 *
 * This is the connection's lifetime. The caller scopes it — to a screen, to the foreground — and
 * cancelling it closes the socket. PRODUCT_SPEC SYNC-003 keeps a persistent background connection out
 * of scope: a socket held open by a backgrounded app is a wake lock with extra steps.
 */
class ObserveRealtimeUpdatesUseCase @Inject constructor(
    private val realtime: RealtimeUpdates,
    private val libraryRepository: LibraryRepository,
    private val logger: Logger,
) {
    suspend operator fun invoke(profileId: ProfileId) {
        realtime.events(profileId).collect { event ->
            when (event) {
                is RealtimeEvent.AccountChanged -> {
                    logger.info(LogCategory.Sync, "Applying a realtime account update")
                    // Positions only. The grant in the same frame is deliberately *not* applied here:
                    // storing permissions is the auth layer's job and it does so with a marking policy
                    // this use case has no business duplicating. The next SyncAccountUseCase picks it
                    // up, and until then the stored grant is merely a few minutes old rather than wrong.
                    libraryRepository.writeProgress(profileId, event.account.progress)
                }
            }
        }
    }
}
