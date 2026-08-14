package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.OfflineBook
import com.example.shelfplayer.core.model.download.OfflineFile
import kotlinx.coroutines.flow.Flow

/**
 * PRODUCT_SPEC DL-002 / DL-003 — the offline manifest, and who has a claim on each copy.
 *
 * ### This records; it does not fetch
 *
 * Nothing here opens a connection. The manifest is written by whatever is doing the transfer, read by the
 * player to resolve a local URI, read by the start-up verifier, and read by the storage screen. Keeping the
 * transfer out means the shape files are committed in can be tested without a server, which is the whole
 * reason it is built before the downloader rather than alongside it.
 *
 * ### One copy, several claims
 *
 * A download is keyed by (server, item) and carries the set of profiles that asked for it — the owner's
 * decision 6, and DL-003 criteria 4 and 5. [release] removes one claim; the files become deletable only when
 * the last one goes, and even then the deletion is a separate, deliberate step, because DL-003 requires that
 * logging out and removing a server both leave local media alone unless the user chose otherwise.
 */
interface DownloadRepository {

    /**
     * Every download on this device, newest first.
     *
     * Not scoped to a profile, by decision 6: the storage screen answers *"what is using space here"*, which
     * is a fact about the device. PRODUCT_SPEC 5.2 is honoured at the screen — a book the current profile
     * cannot see is listed with its size and without its title — because the deletion this list exists for
     * has to work on a row nobody can name.
     */
    fun observeAll(): Flow<List<OfflineBook>>

    /** One book's manifest, or `null` when this device has no copy. */
    fun observe(serverId: ServerId, itemId: LibraryItemId): Flow<OfflineBook?>

    /** The books this profile has claimed and that are completely downloaded, for marking a shelf. */
    fun observeCompletedFor(profileId: ProfileId): Flow<Set<LibraryItemId>>

    /** How many bytes the downloads occupy, for the top of the storage screen. */
    fun observeTotalBytes(): Flow<Long>

    /**
     * Records that [profileId] wants this book, creating the manifest if this device has no copy yet.
     *
     * Idempotent in the way that matters: a second request from the same profile does not reset the
     * manifest, re-time the claim, or clear a pin. A request from a *different* profile for a book already
     * downloaded is the shared-copy case and adds only a claim — no bytes move.
     *
     * @param files the audio files to expect, in playback order, with the locations they will be written to.
     */
    suspend fun request(
        serverId: ServerId,
        itemId: LibraryItemId,
        profileId: ProfileId,
        files: List<OfflineFile>,
    ): AppResult<OfflineBook>

    /**
     * Updates one file after a transfer step — bytes written, state, validators the server sent.
     *
     * Separate from [request] because it runs repeatedly while [request] runs once, and because the two have
     * different failure meanings: a failed request is a download that never started, a failed update is a
     * download whose progress was not recorded.
     */
    suspend fun updateFile(serverId: ServerId, itemId: LibraryItemId, file: OfflineFile): AppResult<Unit>

    /**
     * PRODUCT_SPEC DL-001 — marks a book complete, and refuses to when it is not.
     *
     * *"A book becomes `Downloaded` only after all required audio tracks, cover, and offline manifest are
     * committed"*, and *"atomic commit prevents a crash from creating a false complete state"*. So this
     * checks rather than trusts: a manifest whose files are not all complete stays incomplete and the caller
     * is told, instead of a book that looks playable offline and stops on its third track.
     */
    suspend fun markComplete(serverId: ServerId, itemId: LibraryItemId, coverUri: String?): AppResult<OfflineBook>

    /** Records that a download stopped, with a summary safe to show — never a URL, never a token. */
    suspend fun markFailed(serverId: ServerId, itemId: LibraryItemId, summary: String): AppResult<Unit>

    /**
     * PRODUCT_SPEC DL-006 — protects a copy from automatic cleanup, or stops protecting it.
     *
     * Any profile's pin protects the shared copy. One person deciding to keep a book is enough, and asking
     * everyone to agree before something is kept would make the pin useless on exactly the device it is for.
     */
    suspend fun setPinned(
        serverId: ServerId,
        itemId: LibraryItemId,
        profileId: ProfileId,
        isPinned: Boolean,
    ): AppResult<Unit>

    /**
     * PRODUCT_SPEC DL-003 criterion 5 — one profile stops claiming a copy.
     *
     * Returns whether any claim remains. `false` means the files are now unreferenced and may be deleted —
     * *may*, not *will*: the deletion is the caller's decision and its own step, which is what lets DL-003's
     * "keep orphaned local media" choice exist at all.
     */
    suspend fun release(serverId: ServerId, itemId: LibraryItemId, profileId: ProfileId): AppResult<Boolean>

    /** The books no profile claims any more, for a cleanup pass. */
    suspend fun unreferenced(): AppResult<List<OfflineBook>>

    /**
     * Forgets a manifest whose files are gone.
     *
     * Called *after* the filesystem work, never instead of it. A manifest removed first would leave files
     * nothing knows about — invisible in the storage screen and impossible to delete from inside the app.
     */
    suspend fun forget(serverId: ServerId, itemId: LibraryItemId): AppResult<Unit>
}
