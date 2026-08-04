package com.example.shelfplayer.core.model

import java.time.Instant

/**
 * PRODUCT_SPEC LIB-001 — sync status is visible but non-blocking.
 *
 * A failed sync keeps [lastSuccessfulSyncAt] so the UI can say "showing content from 10 minutes ago"
 * instead of replacing a perfectly usable cached library with an error screen.
 */
data class SyncState(
    val serverId: ServerId,
    val profileId: ProfileId,
    val status: SyncStatus,
    val lastSuccessfulSyncAt: Instant?,
    val lastAttemptedAt: Instant?,
    val lastError: AppError?,
) {
    companion object {
        fun idle(serverId: ServerId, profileId: ProfileId): SyncState = SyncState(
            serverId = serverId,
            profileId = profileId,
            status = SyncStatus.NeverSynced,
            lastSuccessfulSyncAt = null,
            lastAttemptedAt = null,
            lastError = null,
        )
    }
}

enum class SyncStatus {
    NeverSynced,
    Syncing,
    Succeeded,

    /** PRODUCT_SPEC LIB-001: a failed optional section does not fail the whole sync. */
    PartiallySucceeded,
    Failed,
}
