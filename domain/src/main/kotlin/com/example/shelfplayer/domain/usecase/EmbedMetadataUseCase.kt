package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ManagementAction
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.asFailure
import com.example.shelfplayer.core.model.library.EmbedRequest
import com.example.shelfplayer.core.model.realtime.RealtimeEvent
import com.example.shelfplayer.core.model.realtime.RealtimeStatus
import com.example.shelfplayer.core.model.realtime.ServerTask
import com.example.shelfplayer.domain.realtime.RealtimeUpdates
import com.example.shelfplayer.domain.repository.MetadataRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import javax.inject.Inject

/**
 * PRODUCT_SPEC MGR-007 — asks the server to embed metadata, and enforces the permission a second time.
 *
 * ### Why the check is here as well as in the UI
 *
 * PRODUCT_SPEC principle 4. The screen hides the action for a non-administrator; this refuses it. The two
 * are not redundant, because the screen's copy of the permission is a snapshot and this one is read at the
 * moment of the call — an account demoted while the confirmation dialog was open is exactly the case the
 * second check exists for, and the alternative is a `403` after telling somebody their files were about to
 * be rewritten.
 *
 * ### What it deliberately does not do
 *
 * Wait. The server answers as soon as the task is queued, and [EmbedTaskWatcher] is what reports the
 * outcome. A use case that suspended until the embed finished would hold a coroutine across an operation
 * that can take minutes on a long book, and would have nothing to say if the socket dropped in the middle.
 */
class EmbedMetadataUseCase @Inject constructor(
    private val metadata: MetadataRepository,
    private val permissions: ObserveManagementPermissionsUseCase,
    private val logger: Logger,
) {
    suspend operator fun invoke(bookId: LibraryItemId): AppResult<EmbedRequest> {
        // The same computation the screen used to decide whether to offer the action, read again now. Not a
        // second implementation of the rule — a second reading of it, which is the only kind of double
        // enforcement that cannot disagree with itself.
        val scope = permissions(bookId).first()
            ?: return AppError.Authentication(summary = "No profile is signed in.").asFailure()

        val block = scope.permissions.blockOn(ManagementAction.EmbedMetadata)
        if (block != null) {
            logger.info(
                LogCategory.Sync,
                "Refused an embed request",
                LogField.Public("block", block.name),
            )
            return AppError.Authorization(
                summary = "Only a server administrator can embed metadata into the source files.",
            ).asFailure()
        }
        return metadata.embedMetadata(scope.profileId, bookId)
    }
}

/**
 * PRODUCT_SPEC MGR-007 — *"the operation is non-blocking and has visible status"*, which is the socket's job.
 *
 * ### Why this reads events rather than polling
 *
 * Because there is nothing to poll. The route that starts the embed answers `200` and forgets the caller;
 * the item's own fields do not change when it finishes, so a `GET` cannot tell the difference between
 * running and done. `task_finished` is the only signal the server produces, and it produces it once.
 *
 * ### Filtered by action *and* item
 *
 * A server runs tasks nobody on this device asked for — library scans, an m4b encode somebody started in
 * the web interface — and they arrive on the same stream with the same shape. Matching on the action alone
 * would report another book's failure against the one on screen.
 */
class EmbedTaskWatcher @Inject constructor(private val realtime: RealtimeUpdates) {

    /**
     * PRODUCT_SPEC MGR-007 / SYNC-002 — whether the live connection is up.
     *
     * Exposed because a dropped socket is not the same as a task that has not finished, and the screen has
     * to be able to say so. Nothing replays a missed `task_finished`, so a connection that went down while
     * an embed was running means the outcome is **unknown** — which is the one thing MGR-007's last
     * criterion forbids reporting as success.
     */
    val connection: StateFlow<RealtimeStatus> get() = realtime.status

    /**
     * The embed task events for one item, as they arrive.
     *
     * Collecting this is what holds the socket open (see `DefaultRealtimeUpdates`), so the caller scopes it
     * to the screen. Nothing is replayed: a subscriber that arrives after the task finished sees nothing,
     * which is correct — it did not watch, so it does not know, and claiming otherwise is what MGR-007's
     * last criterion forbids.
     */
    fun outcomes(profileId: ProfileId, bookId: LibraryItemId): Flow<EmbedTaskState> = realtime.events(profileId)
        .filterIsInstance<RealtimeEvent.TaskChanged>()
        .mapNotNull { event -> event.task.stateFor(bookId) }

    private fun ServerTask.stateFor(bookId: LibraryItemId): EmbedTaskState? = when {
        !isEmbedMetadata -> null
        libraryItemId != bookId.value -> null
        isFailed -> EmbedTaskState.Failed(hasError)
        isFinished -> EmbedTaskState.Finished
        else -> EmbedTaskState.Running
    }
}

/**
 * PRODUCT_SPEC MGR-007 — what the server has said about an embed so far.
 *
 * [Failed] carries whether the server attached an error, never the error itself: the text can quote a path
 * inside somebody's library, and PRODUCT_SPEC 14.5 keeps that out of anything this app displays twice or
 * writes down. What the user needs is "it failed, and the server said why — look there", which is what
 * `hasServerError` supports.
 */
sealed interface EmbedTaskState {
    /** `task_started` — the server picked the job up. */
    data object Running : EmbedTaskState

    /** `task_finished` with no failure. The audio files have been rewritten. */
    data object Finished : EmbedTaskState

    data class Failed(val hasServerError: Boolean) : EmbedTaskState
}
