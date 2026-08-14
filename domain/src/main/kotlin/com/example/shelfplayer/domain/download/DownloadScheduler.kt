package com.example.shelfplayer.domain.download

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ServerId

/**
 * PRODUCT_SPEC DL-001 / §12 — persistent work that outlives the screen that started it.
 *
 * ### Why an interface in `:domain`
 *
 * The same split as [com.example.shelfplayer.domain.sync.BackgroundSync]: WorkManager needs a `Context` and
 * `:domain` has none, while *which* work exists and when it is cancelled is domain logic. Keeping the policy
 * here means it can be read in one place instead of inferred from a builder chain in `:app`.
 *
 * ### One job per book, named after the book
 *
 * DL-001 requires pause, resume, cancel, retry and remove, and every one of those is "find the job for this
 * book". A unique name per (server, item) is what makes that possible, and it also makes a second tap on
 * *Download* harmless rather than a second job racing the first over the same files.
 */
interface DownloadScheduler {

    /**
     * Ensures work exists to fetch this book's files.
     *
     * Idempotent: enqueueing a book that is already downloading keeps the running job rather than restarting
     * it. Retrying a failed one is the same call — the job resumes from the parts on disk.
     */
    suspend fun enqueue(serverId: ServerId, itemId: LibraryItemId)

    /**
     * Stops the work for one book, leaving the parts on disk.
     *
     * *Cancel* and *remove* are different actions and this is only the first. The partial files are what a
     * later retry resumes from; deleting them is a separate, deliberate step, because a user who cancelled a
     * download on a train has not asked to throw away the eighty per cent they already have.
     */
    suspend fun cancel(serverId: ServerId, itemId: LibraryItemId)
}
