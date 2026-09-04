package com.example.shelfplayer.domain.download

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.TrafficCategory

/**
 * PRODUCT_SPEC DL-001 / §12 — persistent work that outlives the screen that started it.
 *
 * WorkManager may execute long after the UI state that queued a job has changed. The scheduler therefore
 * receives the profile that authorized the transfer as durable job input rather than asking the worker to
 * reconstruct identity from whichever profile happens to be active later.
 *
 * A unique work name remains per (server, item): downloaded bytes are intentionally shared between profile
 * claims. [profileId] is authorization ownership for the transfer, not a second physical-copy identity.
 */
interface DownloadScheduler {

    /**
     * Ensures work exists to fetch this book's files.
     *
     * Real schedulers override this profile-aware form. The default delegates to the older overload only so
     * existing in-memory test doubles can remain tiny while this API gains ownership; production's
     * WorkManager implementation overrides this method and persists [profileId] into the work request.
     */
    suspend fun enqueue(
        profileId: ProfileId,
        serverId: ServerId,
        itemId: LibraryItemId,
        category: TrafficCategory,
    ) {
        enqueue(serverId, itemId, category)
    }

    /**
     * Compatibility seam for existing test doubles. Production code uses the profile-aware overload.
     * New real implementations should not expose an identity-less execution path.
     */
    suspend fun enqueue(serverId: ServerId, itemId: LibraryItemId, category: TrafficCategory) {
        throw UnsupportedOperationException("A persistent download requires an owning profile")
    }

    /** Stops the work for one shared book copy, leaving resumable parts on disk. */
    suspend fun cancel(serverId: ServerId, itemId: LibraryItemId)
}
