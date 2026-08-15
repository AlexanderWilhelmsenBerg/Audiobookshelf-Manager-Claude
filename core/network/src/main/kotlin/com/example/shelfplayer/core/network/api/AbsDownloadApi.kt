package com.example.shelfplayer.core.network.api

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.debug
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.resultOf
import com.example.shelfplayer.core.network.gateway.DownloadApi
import com.example.shelfplayer.core.network.gateway.FileTransfer
import com.example.shelfplayer.core.network.gateway.ProfileConnectionResolver
import com.example.shelfplayer.core.network.http.NetworkErrorMapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * PRODUCT_SPEC DL-001 / DL-002 — the real [DownloadApi], against `contracts/item-file.json`.
 *
 * ### Three ways a range request can end, and only one of them is what was asked for
 *
 * `206` — the range was honoured. Append.
 *
 * `200` — the range was **declined**, and the body is the whole file from byte zero. Either the server
 * does not do ranges, or `If-Range` did not match because the file changed. Both mean the same thing to
 * the caller and it is reported as [FileTransfer.wasResumed]` == false`: whatever is on disk is stale and
 * must be replaced, not appended to. Getting this wrong produces a file of exactly the right size made of
 * two different files, which passes every length check there is.
 *
 * `416` — the range is unsatisfiable, which happens when the local part is *longer* than the file now is.
 * Mapped to a plain failure; the retry starts from zero because the caller discards the part.
 *
 * ### Cancellation is checked inside the copy loop
 *
 * A download runs for minutes. `withContext` alone would only notice a cancellation between suspension
 * points, and a tight read/write loop over a socket has none — so the job would keep writing after the
 * user pressed cancel, and after WorkManager stopped the worker. [ensureActive] in the loop is what makes
 * "stop" mean stop.
 *
 * ### What is not logged
 *
 * No URL, no token, no title (PRODUCT_SPEC 14.5). The file id is an opaque server identifier and is
 * logged as one; the byte counts are logged because "resumed at 4194304, wrote 0" is what a stalled
 * download looks like in a report and neither number names anything private.
 */
@Singleton
internal class AbsDownloadApi @Inject constructor(
    private val services: AudiobookshelfServiceFactory,
    private val connections: ProfileConnectionResolver,
    private val errors: NetworkErrorMapper,
    private val logger: Logger,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) : DownloadApi {

    override suspend fun fetchFile(
        profileId: ProfileId,
        bookId: LibraryItemId,
        fileId: String,
        sink: (Boolean) -> OutputStream,
        resumeFrom: Long,
        validator: String?,
        onProgress: (Long) -> Unit,
    ): AppResult<FileTransfer> {
        val connection = connections.connectionFor(profileId)
            ?: return AppResult.Failure(AppError.Authentication())

        return withContext(ioDispatcher) {
            resultOf(onError = errors::fromThrowable) {
                val response = services.downloadService(connection.serverUrl).file(
                    bearer = bearerOf(connection.accessToken.value),
                    itemId = bookId.value,
                    fileId = fileId,
                    // Open-ended: the client knows where it stopped, not where the file ends.
                    range = resumeFrom.takeIf { it > 0 }?.let { "bytes=$it-" },
                    // Only alongside a range. On its own, `If-Range` turns an ordinary GET into a
                    // conditional one that a server may answer with no body at all.
                    ifRange = validator?.takeIf { resumeFrom > 0 },
                )
                response.use { transferOf(it, sink, resumeFrom, onProgress) }
            }.flatten()
        }
    }

    override suspend fun fetchCover(
        profileId: ProfileId,
        bookId: LibraryItemId,
        sink: () -> OutputStream,
    ): AppResult<String?> {
        val connection = connections.connectionFor(profileId)
            ?: return AppResult.Failure(AppError.Authentication())

        return withContext(ioDispatcher) {
            resultOf(onError = errors::fromThrowable) {
                val response = services.downloadService(connection.serverUrl)
                    .cover(bearerOf(connection.accessToken.value), bookId.value)
                response.use { coverOf(it, sink) }
            }.flatten()
        }
    }

    /**
     * Writes a cover, or says why not.
     *
     * The sink is opened only on a successful response, like [transferOf]'s, so a server with no artwork
     * for an item leaves no zero-byte file behind pretending to be one.
     */
    private suspend fun coverOf(
        response: retrofit2.Response<ResponseBody>,
        sink: () -> OutputStream,
    ): AppResult<String?> {
        if (!response.isSuccessful) return AppResult.Failure(errors.fromStatus(response.code()))
        val body = response.body() ?: return AppResult.Failure(
            AppError.ApiCompatibility(
                summary = "This server answered a cover request with no body.",
                missingField = "body",
            ),
        )
        sink().use { stream -> copy(body, stream, alreadyOnDisk = 0) {} }
        return AppResult.Success(body.contentType()?.let { "${'$'}{it.type}/${'$'}{it.subtype}" })
    }

    /**
     * Reads one response into [sink], or explains why it could not be.
     *
     * Returns a nested result that the caller flattens, rather than throwing: a `403` from a server that
     * revoked the download permission is an ordinary answer to record, not an exception to unwind through.
     */
    private suspend fun transferOf(
        response: retrofit2.Response<ResponseBody>,
        sink: (Boolean) -> OutputStream,
        resumeFrom: Long,
        onProgress: (Long) -> Unit,
    ): AppResult<FileTransfer> {
        if (!response.isSuccessful) {
            return AppResult.Failure(errors.fromStatus(response.code()))
        }
        val body = response.body() ?: return AppResult.Failure(
            AppError.ApiCompatibility(
                summary = "This server answered a file request with no body.",
                missingField = "body",
            ),
        )

        // 206 means the range was honoured. Anything else successful means it was not, and the body is
        // the file from its beginning — whatever is already on disk is stale.
        val wasResumed = response.code() == HTTP_PARTIAL_CONTENT
        val headers = response.headers()
        // Opened only now, and only on a successful response: a failed attempt must not be able to
        // truncate a part that a later one could have resumed from.
        val written = sink(wasResumed).use { stream ->
            copy(body, stream, alreadyOnDisk = if (wasResumed) resumeFrom else 0, onProgress)
        }

        logger.debug(
            LogCategory.Sync,
            "Audio file transferred",
            LogField.Count("resumedFrom", if (wasResumed) resumeFrom.toInt() else 0),
            LogField.Count("bytesWritten", written.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
        )

        return AppResult.Success(
            FileTransfer(
                bytesWritten = written,
                totalBytes = totalFrom(headers[CONTENT_RANGE], headers[CONTENT_LENGTH], wasResumed, resumeFrom),
                wasResumed = wasResumed,
                eTag = headers[ETAG],
                lastModified = headers[LAST_MODIFIED],
                contentType = body.contentType()?.let { "${it.type}/${it.subtype}" },
            ),
        )
    }

    /**
     * The copy loop, cancellable between buffers.
     *
     * 64 KiB because it is comfortably larger than a TCP window and small enough that a cancellation is
     * noticed within milliseconds. The progress callback reports the running total *including* what was
     * already on disk, so a caller can drive a progress bar without knowing whether this was a resume.
     */
    private suspend fun copy(
        body: ResponseBody,
        sink: OutputStream,
        alreadyOnDisk: Long,
        onProgress: (Long) -> Unit,
    ): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var written = 0L
        body.byteStream().use { source ->
            while (true) {
                coroutineContext.ensureActive()
                val read = source.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
                written += read
                onProgress(alreadyOnDisk + written)
            }
        }
        sink.flush()
        return written
    }

    /**
     * The file's full length, which is not always `Content-Length`.
     *
     * On a `206`, `Content-Length` is the length of the *part*. The total is the number after the slash in
     * `Content-Range: bytes 1024-9999/10000`, and a server is allowed to write `*` there instead, in which
     * case there is no total to be had and `null` is the honest answer. On a `200` the two coincide.
     */
    private fun totalFrom(
        contentRange: String?,
        contentLength: String?,
        wasResumed: Boolean,
        resumeFrom: Long,
    ): Long? = when {
        wasResumed -> contentRange?.substringAfterLast('/')?.toLongOrNull()
            ?: contentLength?.toLongOrNull()?.let { it + resumeFrom }
        else -> contentLength?.toLongOrNull()
    }

    /** Unwraps the result the call produced from the one `resultOf` wrapped around it. */
    private fun <T> AppResult<AppResult<T>>.flatten(): AppResult<T> = when (this) {
        is AppResult.Failure -> AppResult.Failure(error)
        is AppResult.Success -> value
    }

    private inline fun <T> retrofit2.Response<ResponseBody>.use(block: (retrofit2.Response<ResponseBody>) -> T): T =
        try {
            block(this)
        } finally {
            body()?.close()
        }

    private companion object {
        const val HTTP_PARTIAL_CONTENT = 206
        const val BUFFER_BYTES = 64 * 1024
        const val CONTENT_RANGE = "Content-Range"
        const val CONTENT_LENGTH = "Content-Length"
        const val ETAG = "ETag"
        const val LAST_MODIFIED = "Last-Modified"
    }
}
