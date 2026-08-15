package com.example.shelfplayer.data.downloads

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.core.model.download.OfflineBook
import com.example.shelfplayer.core.model.download.OfflineFile
import com.example.shelfplayer.core.model.isFailure
import com.example.shelfplayer.core.network.gateway.DownloadApi
import com.example.shelfplayer.domain.download.OfflineFiles
import com.example.shelfplayer.domain.repository.DownloadRepository
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC DL-001 — a whole book: every file in turn, then the commit that makes it playable offline.
 *
 * ### One file at a time, in order
 *
 * Not four in parallel, though the transport would allow it. Two reasons, and the second is the one that
 * matters. A self-hosted Audiobookshelf is very often a Raspberry Pi or a NAS, and four concurrent reads of
 * a hundred-megabyte file is a different load from one. And a book downloaded **in order** is a book that
 * becomes *partly listenable* early — the first file is committed while the twelfth is still arriving — which
 * is what a listener who started a download five minutes before leaving actually wants.
 *
 * ShelfPlayer takes the same shape: its download subsystem pulls one asset at a time off a queue, with a
 * small concurrency cap across *books* rather than within one.
 *
 * ### Failure stops the book, and keeps everything
 *
 * The first file that cannot be fetched ends the run. Continuing would spend a metered connection on files
 * that cannot make the book playable anyway — a book is not `Downloaded` until all of them are committed —
 * and would turn one bad network moment into twelve failed requests.
 *
 * Nothing is deleted. Committed files stay committed and partial ones stay partial, so a retry is a resume of
 * the book and not just of one file.
 */
@Singleton
class BookDownloader @Inject constructor(
    private val repository: DownloadRepository,
    private val downloads: DownloadApi,
    private val fileDownloader: FileDownloader,
    private val storage: DownloadStorage,
    private val logger: Logger,
) : OfflineFiles {

    /**
     * Downloads everything [itemId] still needs.
     *
     * @param onProgress the book's completed fraction, `0.0`–`1.0`, weighted by each file's expected size.
     *   Called as bytes arrive; it must not block.
     * @return the completed book, or the reason it is not complete.
     */
    suspend fun download(
        profileId: ProfileId,
        serverId: ServerId,
        itemId: LibraryItemId,
        onProgress: (Float) -> Unit = {},
    ): AppResult<OfflineBook> {
        val manifest = repository.observe(serverId, itemId).first()
            ?: return AppResult.Failure(
                AppError.Unknown(summary = "There is nothing recorded to download for this book."),
            )

        // Already here. Not an error and not a reason to re-fetch: a second request for a downloaded book is
        // the shared-copy case, and the claim it added is all that was needed.
        if (manifest.isComplete) {
            return repository.markComplete(serverId, itemId, coverUri = manifest.coverUri)
        }

        val weights = Weights(manifest.files)
        onProgress(weights.fractionOf(manifest.files))

        manifest.files.filter { it.state != DownloadState.Complete }.forEach { file ->
            val fetched = fileDownloader.download(profileId, serverId, itemId, file) { bytes ->
                onProgress(weights.fractionWith(file, bytes))
            }
            if (fetched.isFailure()) {
                logger.warn(
                    LogCategory.Sync,
                    "A book download stopped at one of its files",
                    LogField.Count("remaining", manifest.files.count { it.state != DownloadState.Complete }),
                )
                repository.markFailed(serverId, itemId, summary = fetched.error.summary)
                return AppResult.Failure(fetched.error)
            }
            weights.complete(file)
            onProgress(weights.fraction())
        }

        val completed = repository.markComplete(
            serverId,
            itemId,
            coverUri = manifest.coverUri ?: fetchCover(profileId, serverId, itemId),
        )
        if (completed is AppResult.Success) {
            logger.info(
                LogCategory.Sync,
                "A book is available offline",
                LogField.Count("files", completed.value.files.size),
            )
            onProgress(1f)
        }
        return completed
    }

    /**
     * PRODUCT_SPEC DL-001 — the cover, fetched once the audio is safely down.
     *
     * Last, and failure-tolerant. A book with every audio file is completely listenable, and a server with
     * no artwork for an item is ordinary — so a cover that cannot be had returns `null` and the book is
     * still `Downloaded`. Refusing to complete for a cosmetic gap would leave a permanently stuck download.
     *
     * Fetched only when the manifest has none, so a retry of a book whose audio failed does not re-fetch
     * artwork it already has.
     */
    private suspend fun fetchCover(profileId: ProfileId, serverId: ServerId, itemId: LibraryItemId): String? {
        var destination: File? = null
        val fetched = downloads.fetchCover(profileId, itemId) {
            // The type is not known until the response arrives, and the name depends on it — so the file is
            // named inside the sink, which is the first moment both facts exist.
            storage.coverFor(serverId.value, itemId.value, mimeType = null)
                .also { destination = it }
                .outputStream()
        }
        return when (fetched) {
            is AppResult.Success -> destination?.toURI()?.toString()
            is AppResult.Failure -> {
                logger.info(LogCategory.Sync, "A book was downloaded without its cover, which is not a failure")
                null
            }
        }
    }

    /**
     * PRODUCT_SPEC DL-003 — removes one profile's claim, and the files when it was the last.
     *
     * The two halves are in this order for a reason: the claim goes through the repository, and only if
     * nothing else references the copy do the bytes go. Reversing it would delete a book another profile on
     * the same device is halfway through.
     *
     * @return `true` when the files were actually removed.
     */
    override suspend fun remove(profileId: ProfileId, serverId: ServerId, bookId: LibraryItemId): AppResult<Boolean> {
        val itemId = bookId
        val released = repository.release(serverId, itemId, profileId)
        if (released.isFailure()) return AppResult.Failure(released.error)
        if ((released as AppResult.Success).value) {
            // Somebody else still wants it. Nothing on disk changes.
            return AppResult.Success(false)
        }

        storage.deleteItem(serverId.value, itemId.value)
        val forgotten = repository.forget(serverId, itemId)
        if (forgotten.isFailure()) return AppResult.Failure(forgotten.error)

        logger.info(LogCategory.Sync, "A downloaded book was removed")
        return AppResult.Success(true)
    }

    /**
     * PRODUCT_SPEC DL-001 — removes the temporary parts of a download the user gave up on.
     *
     * Separate from [remove] because it is a different decision: this keeps the manifest and the claim, so
     * the book still shows as *not downloaded* rather than disappearing, and a later tap starts it cleanly.
     */
    override suspend fun discardPartials(serverId: ServerId, bookId: LibraryItemId): AppResult<Long> {
        val itemId = bookId
        val reclaimed = storage.deleteParts(serverId.value, itemId.value)
        val manifest = repository.observe(serverId, itemId).first()
        manifest?.files
            ?.filter { it.state != DownloadState.Complete }
            ?.forEach { file -> repository.updateFile(serverId, itemId, file.copy(downloadedBytes = 0)) }
        return AppResult.Success(reclaimed)
    }

    /**
     * PRODUCT_SPEC DL-001 — everything under `offline/` that no manifest claims.
     *
     * The live set comes from the manifests rather than from the claims: a book with no claim left is still
     * a manifest, and its files are removed by [remove] in a deliberate order. This sweep is for the case
     * where even the manifest is gone, which is the only case nothing else can reach.
     */
    override suspend fun sweepOrphans(): AppResult<Long> {
        val live = repository.observeAll().first()
            .mapTo(mutableSetOf()) { book -> book.serverId.value to book.itemId.value }
        val reclaimed = storage.sweepOrphans(live)
        if (reclaimed > 0) {
            logger.info(
                LogCategory.Sync,
                "Removed downloaded files that no longer belong to anything",
                LogField.Count("megabytes", (reclaimed / BYTES_PER_MEGABYTE).toInt()),
            )
        }
        return AppResult.Success(reclaimed)
    }

    /**
     * A book's completed fraction, weighted by size rather than by file count.
     *
     * Counting files would make a progress bar jump: an audiobook's first "file" is often a two-minute
     * introduction and its last a four-hour part, and 1-of-2 is not half. Weighting by
     * [OfflineFile.expectedBytes] makes the bar move at the speed the bytes actually arrive.
     *
     * A file with no expected size — a server that sent no `Content-Length` — is given the mean of the rest,
     * which keeps the total at 1.0 and is a better guess than zero. ShelfPlayer does the same thing with its
     * per-asset `progressWeight`.
     */
    private class Weights(files: List<OfflineFile>) {
        private val sizes: Map<String, Long>
        private val total: Long
        private val done = mutableSetOf<String>()
        private var partial: Pair<String, Long>? = null

        init {
            val known = files.mapNotNull { it.expectedBytes?.takeIf { bytes -> bytes > 0 } }
            val fallback = if (known.isEmpty()) 1L else known.sum() / known.size
            sizes = files.associate { file ->
                file.remoteFileId to (file.expectedBytes?.takeIf { it > 0 } ?: fallback)
            }
            total = sizes.values.sum().coerceAtLeast(1)
            files.filter { it.state == DownloadState.Complete }.forEach { done += it.remoteFileId }
        }

        fun complete(file: OfflineFile) {
            done += file.remoteFileId
            partial = null
        }

        fun fractionOf(files: List<OfflineFile>): Float {
            files.filter { it.state == DownloadState.Complete }.forEach { done += it.remoteFileId }
            return fraction()
        }

        /** The running fraction while [file] is being written, with [bytes] of it on disk. */
        fun fractionWith(file: OfflineFile, bytes: Long): Float {
            partial = file.remoteFileId to bytes.coerceAtMost(sizes[file.remoteFileId] ?: bytes)
            return fraction()
        }

        fun fraction(): Float {
            val finished = done.sumOf { id -> sizes[id] ?: 0 }
            val inFlight = partial?.takeIf { it.first !in done }?.second ?: 0
            return ((finished + inFlight).toFloat() / total).coerceIn(0f, 1f)
        }
    }
}

/** For the one log line that reports reclaimed space, where bytes would be unreadable. */
private const val BYTES_PER_MEGABYTE = 1_048_576L
