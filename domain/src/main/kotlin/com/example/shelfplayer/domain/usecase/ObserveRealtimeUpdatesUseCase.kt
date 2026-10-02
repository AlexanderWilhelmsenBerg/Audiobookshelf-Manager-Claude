package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.realtime.RealtimeEvent
import com.example.shelfplayer.domain.realtime.RealtimeProgressEvidenceStore
import com.example.shelfplayer.domain.realtime.RealtimeUpdates
import com.example.shelfplayer.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.collect
import javax.inject.Inject

/**
 * PRODUCT_SPEC SYNC-002 / 13.2 — applies what the server pushes, through the paths that already exist.
 *
 * `user_updated` carries the whole user object, while current playback-session writes emit
 * `user_item_progress_updated` with one media-progress row. Both are handed to [LibraryRepository.writeProgress]
 * instead of giving the socket a second persistence path. That repository already owns the careful rules
 * around unsynced local progress, stale timestamps and profile visibility.
 *
 * The socket's contribution is latency. A pushed progress row is not permission to seek the live player.
 * The process-foreground owner (`ForegroundRealtimeSyncCoordinator`) is the only caller allowed to collect the
 * socket, through [observeForeground]; no screen can create a second collector (#133).
 */
class ObserveRealtimeUpdatesUseCase @Inject constructor(
    private val realtime: RealtimeUpdates,
    private val libraryRepository: LibraryRepository,
    private val logger: Logger,
    private val progressEvidence: RealtimeProgressEvidenceStore = RealtimeProgressEvidenceStore(),
) {
    /** The one process-foreground collection path. Cancelling this call closes the underlying socket. */
    suspend fun observeForeground(profileId: ProfileId) {
        realtime.events(profileId).collect { event ->
            when (event) {
                is RealtimeEvent.AccountChanged -> {
                    logger.info(LogCategory.Sync, "Applying a realtime account update")
                    libraryRepository.writeProgress(profileId, event.account.progress)
                }

                is RealtimeEvent.ProgressChanged -> {
                    logger.info(LogCategory.Sync, "Realtime progress event received")
                    val result = libraryRepository.writeProgress(profileId, listOf(event.progress))
                    when {
                        result is AppResult.Success && result.value > 0 -> {
                            logger.info(LogCategory.Sync, "Realtime progress update accepted")
                            progressEvidence.record(profileId, event.progress, event.sessionId)
                        }

                        result is AppResult.Success -> {
                            logger.info(LogCategory.Sync, "Realtime progress update rejected by conflict boundary")
                        }

                        else -> {
                            logger.info(LogCategory.Sync, "Realtime progress update could not be applied")
                        }
                    }
                }

                is RealtimeEvent.TaskChanged -> Unit
            }
        }
    }
}
