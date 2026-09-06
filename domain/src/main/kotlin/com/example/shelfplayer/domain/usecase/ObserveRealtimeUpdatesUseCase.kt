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
 * `user_updated` carries the whole user object, while current playback-session writes emit
 * `user_item_progress_updated` with one media-progress row. Both are handed to [LibraryRepository.writeProgress]
 * instead of giving the socket a second persistence path. That repository already owns the careful rules
 * around unsynced local progress, stale timestamps and profile visibility.
 *
 * The socket's contribution is *latency*: the same server state arrives seconds after it happened rather
 * than at the next REST refresh. A pushed progress row is not permission to seek the live player. Issue #91
 * owns the shared resume/freshness decision and may consume the same event as evidence for a later Play.
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

                is RealtimeEvent.ProgressChanged -> {
                    logger.info(LogCategory.Sync, "Applying a realtime progress update")
                    // One row through the exact same conflict boundary as REST. In particular, an
                    // unsynced local position cannot be overwritten by a socket echo or another device.
                    libraryRepository.writeProgress(profileId, listOf(event.progress))
                }

                // PRODUCT_SPEC MGR-007 — not this use case's business. A task's outcome belongs to whoever
                // started it, which is one screen and not the whole app: applying it here would mean
                // deciding what "an embed finished" changes globally, and the honest answer is nothing —
                // the item's own fields did not move, only the bytes in files this app never reads.
                //
                // `ObserveEmbedTaskUseCase` reads the same shared stream for the screen that asked.
                is RealtimeEvent.TaskChanged -> Unit
            }
        }
    }
}
