package com.example.shelfplayer.core.model.download

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import java.time.Instant
import kotlin.time.Duration

/**
 * PRODUCT_SPEC DL-002 — the offline manifest, as the rest of the app sees it.
 *
 * ### Why this is a manifest and not a flag on the book
 *
 * DL-002 lists what has to be recorded: server id, profile entitlements, item id, file ids, paths, sizes,
 * MIME types, durations and completion state. None of that fits on a book row, and the reason it is
 * required is the failure it prevents — a book marked `Downloaded` whose third file is a zero-byte stub.
 * The manifest is the thing the start-up verifier reads and the thing the player resolves URIs from, so it
 * has to be able to answer "which files, where, how big" without touching the filesystem.
 *
 * ### One copy per device, entitlements per profile
 *
 * The owner's decision 6: *"If two different users share a book in the library, I want progress to stay per
 * user and not have to download per user."* That is also DL-003 criteria 4 and 5 — a physical blob may be
 * referenced by several profiles, and removing one decrements the reference rather than deleting the file.
 * So this type is keyed by **(server, item)** and carries [requestedBy]; progress stays where it already
 * is, on `media_progress`, which is keyed by profile.
 *
 * ### Locations are URIs, not paths
 *
 * Decision 4 allows a user-chosen folder and an SD card, reached through the Storage Access Framework,
 * where the app does not construct paths at all. A `String` path would have made app-private storage the
 * only representable case and forced a migration the first time somebody picked a folder. Every location
 * here is a URI: `file:///…` under the app's own directory, `content://…` for a document tree.
 */
data class OfflineBook(
    val serverId: ServerId,
    val itemId: LibraryItemId,
    val state: DownloadState,
    /** Every audio file the book needs, in playback order. */
    val files: List<OfflineFile>,
    /** The cover, if one was fetched. Absent is normal and is not a reason to call a download incomplete. */
    val coverUri: String?,
    /**
     * PRODUCT_SPEC DL-003 criteria 4–5 — the profiles that asked for this copy.
     *
     * A reference count with names on it. The physical files are removed when this becomes empty and not
     * before, which is what lets two profiles on one device share one copy.
     */
    val requestedBy: Set<ProfileId>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** The bytes actually on disk, which is what a progress bar and a storage screen both want. */
    val downloadedBytes: Long get() = files.sumOf(OfflineFile::downloadedBytes)

    /** The bytes the server said to expect, where it said. */
    val totalBytes: Long get() = files.sumOf { it.expectedBytes ?: it.downloadedBytes }

    /**
     * Whether every file this book needs is committed.
     *
     * Derived rather than trusted from [state], because the two answer different questions: [state] is what
     * the download *job* believes, and this is what the files say. They disagree exactly when something went
     * wrong, which is the case worth catching.
     */
    val isComplete: Boolean
        get() = files.isNotEmpty() && files.all { it.state == DownloadState.Complete }
}

/**
 * PRODUCT_SPEC DL-002 — one audio file of one book.
 *
 * [remoteFileId] is the server's `ino` for the file, which is what `/api/items/{id}/file/{fileId}` takes.
 * It is the identity; [uri] is only where this device happens to have put it, and a user who moves their
 * download folder changes the second without changing the first.
 */
data class OfflineFile(
    val remoteFileId: String,
    /** Playback order within the book. The server's order, not the filesystem's. */
    val index: Int,
    val uri: String,
    val state: DownloadState,
    /** `Content-Length`, where the server sent one. `null` is not an error — it means resume has no total. */
    val expectedBytes: Long?,
    val downloadedBytes: Long,
    val mimeType: String?,
    val duration: Duration?,
    /**
     * PRODUCT_SPEC DL-002 — the server's validator, persisted so a resume and a staleness check are possible.
     *
     * `contracts/item-file.json` records that Audiobookshelf sends both an `ETag` and a `Last-Modified`. An
     * ETag is **not** a checksum: it is only guaranteed to change when the file changes, and nothing requires
     * it to be derived from the bytes. So this answers *"is my copy stale"* and never *"are my bytes intact"*
     * — the second comes from hashing what was written before committing it. ADR-0018 records the
     * distinction, because it decides what the *Repair* action may claim.
     */
    val eTag: String?,
    val lastModified: String?,
)

/**
 * PRODUCT_SPEC 12 — where a download is in its life.
 *
 * Deliberately small. §12 names more job states than this — backoff, waiting for a network — but those are
 * WorkManager's business and asking a manifest to mirror them would mean two places that can disagree about
 * whether a file is on disk. This is only what the *stored* thing can be.
 */
enum class DownloadState {
    /** Requested, nothing fetched. */
    Queued,

    /** Bytes are arriving, or arrived and stopped. A `.part` exists; nothing is playable. */
    Running,

    /** Written, verified and committed under its final name. */
    Complete,

    /** Given up on for now, with a reason recorded elsewhere. A retry starts from what is on disk. */
    Failed,
}

/**
 * PRODUCT_SPEC DL-003 / decision 4 — where downloads are written.
 *
 * The default is the app-private directory the requirement mandates. The owner also asked for a folder they
 * pick, including an SD card: *"the files should live in the app folder, but it should also be possible to
 * select a folder, and sd should be possible to select."*
 *
 * ADR-0018 records this as a deliberate deviation from DL-003 criterion 3 ("downloads are not exposed to
 * other apps by default"). A folder the user chose is a folder the user can read, which is usually the point
 * of choosing one; criteria 1 and 2 — sanitised names, no traversal — are satisfied by construction under
 * SAF, because the app hands a display name to the framework rather than building a path.
 */
sealed interface StorageRoot {

    /** `files/offline` inside the app's own directory. Removed with the app; unreadable by other apps. */
    data object AppPrivate : StorageRoot

    /**
     * A document tree the user picked, which may be on removable media.
     *
     * Two consequences worth naming here rather than discovering later: files here **survive uninstall**,
     * and an SD card **can be removed**. A book whose files are on absent media has to read as "not
     * downloaded" and offer a re-download, not fail as corrupt — the same handling PLAY-003 already requires
     * for an unreadable local file.
     */
    data class Tree(val treeUri: String) : StorageRoot
}
