package com.example.shelfplayer.data.downloads

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerCapability
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.core.model.download.OfflineFile
import com.example.shelfplayer.core.model.isFailure
import com.example.shelfplayer.core.network.gateway.DownloadApi
import com.example.shelfplayer.core.network.gateway.FileTransfer
import com.example.shelfplayer.domain.repository.CapabilityRepository
import com.example.shelfplayer.domain.repository.DownloadRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC DL-001 / DL-002 — one audio file, fetched, verified and committed atomically.
 *
 * ### The order is the design
 *
 * Fetch into `<name>.part`; verify the part; rename. Nothing is ever written under a final name, so a file
 * that exists under one has been through the check — which is what lets the player and the start-up
 * verifier trust a name rather than re-reading a hundred megabytes.
 *
 * A crash at any point leaves either a `.part` (resumable, and not playable, because `.part` is not a media
 * extension) or a committed file. There is no third state, because the rename is atomic within one
 * filesystem.
 *
 * ### Resume, and the one way it can silently corrupt a file
 *
 * A retry sends `Range: bytes=<what is on disk>-` together with `If-Range: <the stored ETag>`. The server
 * either honours it — `206`, and the bytes are appended — or declines it and sends the whole file with
 * `200`.
 *
 * The declined case is the dangerous one. Appending a full file to a partial one produces a file of the
 * wrong size, which is caught; appending a *new* file to the tail of an old one whose length happens to
 * match produces a file of the right size that is two different recordings spliced together, which is not.
 * [FileTransfer.wasResumed] is how the server's answer reaches this decision, and a declined range
 * truncates the part before writing.
 *
 * ### What verification can and cannot claim
 *
 * DL-002's minimum: response status, expected content length where supplied, non-zero size, readable
 * container. All four are checked here. What is deliberately **not** claimed is that the bytes match the
 * server's — the `ETag` is a validator, not a checksum, and nothing in the response lets a client prove
 * byte equality. The ETag is stored for the next resume and for *Repair*'s staleness check.
 */
@Singleton
class FileDownloader @Inject constructor(
    private val downloads: DownloadApi,
    private val storage: DownloadStorage,
    private val repository: DownloadRepository,
    private val verifier: MediaContainerVerifier,
    private val capabilities: CapabilityRepository,
    private val logger: Logger,
) {

    /**
     * Fetches one file and records the outcome in the manifest.
     *
     * @param onProgress the running byte total for this file, for a notification. Called often; it must
     *   not block.
     * @return the committed file's description, or the reason it is not committed. Ordinary failures leave
     *   the `.part` in place for resuming; an unsatisfied stale range clears it before one clean restart.
     */
    @Suppress("ReturnCount")
    suspend fun download(
        profileId: ProfileId,
        serverId: ServerId,
        itemId: LibraryItemId,
        file: OfflineFile,
        onProgress: (Long) -> Unit = {},
    ): AppResult<OfflineFile> {
        val part = storage.partFor(serverId.value, itemId.value, file.remoteFileId, file.mimeType)
        val onDisk = storage.bytesOnDisk(part)

        // Two conditions for even attempting a resume: bytes on disk, and a validator to guard them with.
        // Without the second the server cannot tell us the file changed, and a resume would be a guess.
        //
        // Deliberately *not* a third condition on `ServerCapability.RangeDownload`. That capability records
        // what a past transfer observed, and `ServerCapabilities.supports` cannot tell "this server refused
        // a range" apart from "nothing has asked yet" — both read as `false`. Gating on it would mean the
        // first retry after any interrupted download never asks for a range, which would disable resuming
        // everywhere rather than only against the servers that cannot do it. The capability is recorded for
        // diagnostics; the decision to try is made from what is on disk.
        val resumeFrom = if (file.eTag != null) onDisk else 0
        var manifestFile = file

        var transfer = fetch(profileId, itemId, file, part, resumeFrom, onProgress)
        if (transfer.isFailure()) {
            record(
                serverId,
                itemId,
                file.copy(state = DownloadState.Failed, downloadedBytes = storage.bytesOnDisk(part)),
            )
            return AppResult.Failure(transfer.error)
        }
        var outcome = (transfer as AppResult.Success).value
        observe(serverId, askedForRange = resumeFrom > 0, outcome = outcome)

        if (outcome.rangeNotSatisfiable && !canCommitUnsatisfiedPart(file, part, outcome)) {
            val restartFile = file.copy(
                expectedBytes = null,
                downloadedBytes = 0,
                eTag = null,
                lastModified = null,
            )
            if (!storage.delete(part)) {
                val error = AppError.Storage(summary = "The stale partial download could not be cleared.")
                record(
                    serverId,
                    itemId,
                    restartFile.copy(state = DownloadState.Failed, downloadedBytes = storage.bytesOnDisk(part)),
                )
                return AppResult.Failure(error)
            }

            // Persist the cleared validator before touching the network again. If this request or the
            // process dies, the next worker starts from zero instead of sending the same impossible range.
            record(serverId, itemId, restartFile)
            manifestFile = restartFile
            transfer = fetch(
                profileId,
                itemId,
                restartFile,
                part,
                resumeFrom = 0,
                onProgress = onProgress,
            )
            if (transfer.isFailure()) {
                record(
                    serverId,
                    itemId,
                    restartFile.copy(
                        state = DownloadState.Failed,
                        downloadedBytes = storage.bytesOnDisk(part),
                    ),
                )
                return AppResult.Failure(transfer.error)
            }
            outcome = (transfer as AppResult.Success).value
            if (outcome.rangeNotSatisfiable) {
                val error = AppError.ApiCompatibility(
                    summary = "The server rejected a full-file download as an unsatisfied range.",
                )
                record(
                    serverId,
                    itemId,
                    restartFile.copy(
                        state = DownloadState.Failed,
                        downloadedBytes = storage.bytesOnDisk(part),
                    ),
                )
                return AppResult.Failure(error)
            }
            observe(serverId, askedForRange = false, outcome = outcome)
        }

        verify(part, outcome)?.let { problem ->
            // The part is left where it is. A short file resumes; a corrupt one is replaced by the next
            // attempt, which cannot resume because the validator will not match.
            record(
                serverId,
                itemId,
                manifestFile.copy(state = DownloadState.Failed, downloadedBytes = part.length()),
            )
            return AppResult.Failure(problem)
        }

        val committed = storage.commit(part)
            ?: return AppResult.Failure(
                AppError.Unknown(summary = "The downloaded file could not be moved into place."),
            )

        val stored = manifestFile.copy(
            uri = committed.toURI().toString(),
            state = DownloadState.Complete,
            expectedBytes = outcome.totalBytes ?: manifestFile.expectedBytes,
            downloadedBytes = committed.length(),
            mimeType = outcome.contentType ?: manifestFile.mimeType,
            eTag = outcome.eTag ?: manifestFile.eTag,
            lastModified = outcome.lastModified ?: manifestFile.lastModified,
        )
        record(serverId, itemId, stored)

        logger.info(
            LogCategory.Sync,
            "Audio file committed",
            LogField.Count("bytes", committed.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
            LogField.Public("resumed", outcome.wasResumed.toString()),
        )
        return AppResult.Success(stored)
    }

    /**
     * A `416` can mean the interrupted request actually wrote the final byte before the connection ended.
     * Commit only when the server's authoritative complete length matches, a returned validator does not
     * contradict the one that guarded the request, and the ordinary media checks pass.
     */
    private fun canCommitUnsatisfiedPart(file: OfflineFile, part: File, outcome: FileTransfer): Boolean {
        val total = outcome.totalBytes ?: return false
        if (total <= 0 || part.length() != total) return false
        if (outcome.eTag != null && file.eTag != null && outcome.eTag != file.eTag) return false
        return verify(part, outcome) == null
    }

    /**
     * PRODUCT_SPEC SYNC-001 / DL-001 — what this transfer proved about the server.
     *
     * The two `ObservedOnly` capabilities, both answered by the response that just arrived and by nothing
     * else. `/status` cannot be asked either question, so a real file is the only evidence there is — which
     * is why this lives on the download path rather than in the handshake.
     *
     * The range answer is recorded only when a range was actually **asked for**. A `200` to a request that
     * carried no `Range` header says nothing at all, and recording it as a refusal would teach the app that
     * every server is one that cannot resume, on the first file of the first book.
     */
    private suspend fun observe(serverId: ServerId, askedForRange: Boolean, outcome: FileTransfer) {
        if (askedForRange) {
            capabilities.record(
                serverId,
                ServerCapability.RangeDownload,
                outcome.wasResumed || outcome.rangeNotSatisfiable,
            )
        }
        if (outcome.eTag != null || outcome.lastModified != null) {
            capabilities.record(serverId, ServerCapability.ChecksumOrETag, isSupported = true)
        }
    }

    /**
     * The transfer, with the part opened in the mode the server's answer justifies.
     *
     * The sink is a function rather than a stream because whether to append is decided by the *response*:
     * a declined range means the bytes on disk are stale and the file must start from empty. See
     * [DownloadApi.fetchFile].
     */
    private suspend fun fetch(
        profileId: ProfileId,
        itemId: LibraryItemId,
        file: OfflineFile,
        part: File,
        resumeFrom: Long,
        onProgress: (Long) -> Unit,
    ): AppResult<FileTransfer> = downloads.fetchFile(
        profileId = profileId,
        bookId = itemId,
        fileId = file.remoteFileId,
        sink = { append ->
            if (!append && resumeFrom > 0) logDeclinedRange()
            storage.sink(part, append = append)
        },
        resumeFrom = resumeFrom,
        validator = file.eTag,
        onProgress = onProgress,
    )

    /**
     * PRODUCT_SPEC DL-002 — the four checks the requirement names as the minimum.
     *
     * Status is already a failure by the time this runs. What is left: the expected length where the
     * server supplied one, a non-zero size, and a container the platform can actually open. Returns the
     * problem or `null`, rather than a boolean, because *which* check failed is what the storage screen
     * shows and what a retry decision depends on.
     */
    private fun verify(part: File, transfer: FileTransfer): AppError? {
        val size = part.length()
        if (size <= 0) {
            return AppError.Unknown(summary = "The download produced an empty file.")
        }
        val expected = transfer.totalBytes
        if (expected != null && size != expected) {
            return AppError.Unknown(
                summary = "The download is $size bytes but the server said $expected.",
            )
        }
        if (!verifier.isReadable(part)) {
            return AppError.Unknown(summary = "The downloaded file is not readable as audio.")
        }
        return null
    }

    private suspend fun record(serverId: ServerId, itemId: LibraryItemId, file: OfflineFile) {
        repository.updateFile(serverId, itemId, file)
    }

    /**
     * A declined range is worth a log line even though it is handled correctly.
     *
     * It means the file changed on the server between two attempts — a rescan, a re-tag, a replaced
     * recording — and a user who notices a download restarting from zero deserves an explanation that
     * exists somewhere.
     */
    private fun logDeclinedRange() {
        logger.info(
            LogCategory.Sync,
            "The server declined a resume; the file changed and the download restarted",
        )
    }
}
