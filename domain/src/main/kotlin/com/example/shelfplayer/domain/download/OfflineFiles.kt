package com.example.shelfplayer.domain.download

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId

/**
 * PRODUCT_SPEC DL-003 — the bytes on disk, as the only thing outside `:data:downloads` needs to say about
 * them.
 *
 * `DownloadRepository` is the *manifest*: rows, claims, states. This is the filesystem, and the two are kept
 * apart because their failure modes are different and their orderings matter. Releasing a claim is a
 * database write that can be undone; deleting a file is not.
 *
 * Only the operations a caller outside the module can correctly ask for are here. There is no "delete this
 * file" — a caller that could name one file could delete half a book and leave a manifest saying it was
 * complete.
 */
interface OfflineFiles {

    /**
     * Releases [profileId]'s claim, and deletes the files if it was the last one.
     *
     * @return `true` when bytes were actually removed, `false` when another profile still wants the copy.
     *   Both are success: a released claim on a shared copy is exactly the right outcome (DL-003 criteria
     *   4–5), and reporting it as a failure would make a working button look broken.
     */
    suspend fun remove(profileId: ProfileId, serverId: ServerId, bookId: LibraryItemId): AppResult<Boolean>

    /**
     * Deletes the temporary parts of a book, keeping its manifest and claim.
     *
     * The one case where a resumable part should not survive: a user who explicitly discarded an unfinished
     * download rather than merely cancelling it.
     *
     * @return how many bytes were reclaimed.
     */
    suspend fun discardPartials(serverId: ServerId, bookId: LibraryItemId): AppResult<Long>

    /**
     * PRODUCT_SPEC DL-001 — removes files belonging to no manifest at all.
     *
     * A `.part` is kept after a failure because it is what a retry resumes from. That stops being true once
     * the manifest goes: an item deleted upstream, a book removed while its worker was mid-write, or a crash
     * between the bytes and the row all leave files nothing will ever ask for and nothing else would ever
     * find. Run at start-up.
     *
     * @return how many bytes were reclaimed.
     */
    suspend fun sweepOrphans(): AppResult<Long>
}
