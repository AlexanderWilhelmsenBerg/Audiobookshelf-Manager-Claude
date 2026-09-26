package com.example.shelfplayer.domain.download

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ServerId
import kotlinx.coroutines.flow.Flow

/**
 * BW-DL-04 / #19 — platform-neutral identity for one physical download execution.
 *
 * Physical media is shared by profiles, so execution identity deliberately remains (server, item).
 * Profile identity authorizes a queued worker but never creates a second physical copy.
 */
data class DownloadExecutionKey(
    val serverId: ServerId,
    val itemId: LibraryItemId,
)

/**
 * Read-only transient execution evidence for the device's physical download rows.
 *
 * Implementations may observe WorkManager or another platform execution owner, but callers see only the
 * small state vocabulary [DownloadRecoveryPolicy] needs. Nothing returned here is durable download truth.
 */
fun interface DownloadExecutionObserver {
    /**
     * Observes all [keys] as one aggregate projection.
     *
     * A missing key is first-class evidence that no current execution information can be reconstructed;
     * presentation must then fall back to the durable manifest rather than persisting or inventing state.
     */
    fun observe(keys: Set<DownloadExecutionKey>): Flow<Map<DownloadExecutionKey, DownloadExecutionEvidence>>
}
