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
 * and `SyncAccountUseCase` already knows how to store. Current Audiobookshelf playback syncs instead emit
 * `user_item_progress_updated`, carrying one progress object. Both shapes enter the same repository
 * boundary so the socket never gets a second implementation of conflict resolution.
 *
 * The repository is where unsynced local progress and timestamp ordering are protected. A realtime item
 * progress event therefore improves latency without gaining permission to overwrite a local pending write,
 * and it never seeks the live player. The shared paused->play decision is #91's responsibility.
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
                    logger.info(LogCategory.Sync, "Applying a realtime item progress update")
                    // Exactly one row, but through the same path as REST. `writeProgress` refuses an older
                    // server value and refuses to overwrite a local row still awaiting upload.
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
