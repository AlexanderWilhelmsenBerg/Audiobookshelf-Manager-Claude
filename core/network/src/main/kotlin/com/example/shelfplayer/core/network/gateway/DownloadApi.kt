package com.example.shelfplayer.core.network.gateway

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import java.io.OutputStream

/**
 * PRODUCT_SPEC DL-001 / DL-002 — fetching one audio file, with resume.
 *
 * ### Why it takes a sink instead of returning bytes
 *
 * Every other method on the gateway returns a parsed model, because every other response fits in memory.
 * An audiobook file does not: buffering one to hand back would be an `OutOfMemoryError` on the exact
 * devices this feature is for. So the caller supplies the destination and this writes into it as bytes
 * arrive, which is also what makes an interrupted transfer resumable — there is a partial file to resume
 * into rather than a discarded buffer.
 *
 * The seam stays honest about the boundary: `:core:network` never learns *where* the file goes. It asks
 * for a stream and reports what it wrote.
 *
 * ### Why the sink is a function of one boolean
 *
 * Whether to **append** cannot be decided before the request, because it depends on the answer: the
 * server may decline the range and send the whole file. So the destination is opened only once the status
 * is known, and the flag says which mode to open it in.
 *
 * Passing an already-open stream instead would put that decision in the caller *before* it has the
 * information to make it, and the failure that follows is the worst one in this feature — a new file
 * appended to the tail of an old one, of exactly the right length, silently spliced.
 */
interface DownloadApi {

    /**
     * Streams one audio file into [sink].
     *
     * @param sink opens the destination. Called **once**, before any byte is written, with `true` when
     *   the server honoured the range and the stream should append, and `false` when it did not and the
     *   stream must start from empty. Not called at all when the request failed, so a failed attempt
     *   cannot truncate a good part.
     * @param resumeFrom the byte offset already on disk, or `0` for a fresh transfer. A non-zero value
     *   becomes `Range: bytes=<n>-`.
     * @param validator the `ETag` recorded when the earlier part was fetched. Sent as `If-Range` so the
     *   server refuses the range — with a plain `200` and the whole file — if the file has changed since.
     *   Passing `null` with a non-zero [resumeFrom] is legal and less safe: the resume then trusts that the
     *   file is unchanged, which is the best that can be done for a server that sent no validator.
     * @param onProgress called as bytes are written, with the running total **including** [resumeFrom].
     *   Called from the IO context this runs on, so an implementation must not block in it.
     *
     * The result reports what the server actually did — see [FileTransfer.wasResumed], which is how a
     * caller learns that its `Range` was declined and the bytes already on disk must be discarded.
     */
    suspend fun fetchFile(
        profileId: ProfileId,
        bookId: LibraryItemId,
        fileId: String,
        sink: (Boolean) -> OutputStream,
        resumeFrom: Long = 0,
        validator: String? = null,
        onProgress: (Long) -> Unit = {},
    ): AppResult<FileTransfer>
}

/**
 * PRODUCT_SPEC DL-002 — what one transfer attempt did, as the manifest needs to record it.
 *
 * @property bytesWritten how many bytes this attempt wrote. Not the file's size — a resumed transfer
 *   writes only the remainder — which is why [totalBytes] exists separately.
 * @property totalBytes the file's full length where the server said, `null` where it did not. A resumed
 *   transfer derives this from `Content-Range`, which carries the total even when `Content-Length` is only
 *   the length of the part.
 * @property wasResumed `false` when the server sent the whole file. The critical case is a **declined**
 *   range: the caller asked to resume, `If-Range` did not match, and the server answered `200` with the
 *   new file from byte zero. The caller must then treat everything already on disk as stale — appending
 *   would produce a file of the right size made of two different files.
 * @property eTag the validator to store, for the next resume and for the staleness check *Repair* performs.
 *   Not a checksum: it is only guaranteed to change when the file changes.
 * @property contentType the server's MIME type, which is what decides the file's extension. The server's
 *   *filename* never does — DL-003 criterion 1.
 */
data class FileTransfer(
    val bytesWritten: Long,
    val totalBytes: Long?,
    val wasResumed: Boolean,
    val eTag: String?,
    val lastModified: String?,
    val contentType: String?,
)
