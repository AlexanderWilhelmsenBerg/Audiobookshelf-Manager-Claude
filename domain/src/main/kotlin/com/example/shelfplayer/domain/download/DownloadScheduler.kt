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
     * @param profileId the profile that authorized this request. It must survive delayed execution, process
     *   restart and profile switching unchanged.
     * @param category PRODUCT_SPEC DL-004 — why these bytes are moving, which decides whether the job may run
     *   on a metered network.
     */
    suspend fun enqueue(
        profileId: ProfileId,
        serverId: ServerId,
        itemId: LibraryItemId,
        category: TrafficCategory,
    )

    /** Stops the work for one shared book copy, leaving resumable parts on disk. */
    suspend fun cancel(serverId: ServerId, itemId: LibraryItemId)
}
