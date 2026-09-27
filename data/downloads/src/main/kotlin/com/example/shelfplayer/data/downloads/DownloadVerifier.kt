package com.example.shelfplayer.data.downloads

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.core.model.download.DownloadStorageState
import com.example.shelfplayer.core.model.download.OfflineBook
import com.example.shelfplayer.core.model.download.VerificationReport
import com.example.shelfplayer.core.model.getOrNull
import com.example.shelfplayer.core.model.resultOf
import com.example.shelfplayer.domain.download.DownloadLocations
import com.example.shelfplayer.domain.download.OfflineVerification
import com.example.shelfplayer.domain.repository.DownloadRepository
import kotlinx.coroutines.flow.first
import java.io.File
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC DL-002 — "on app start, an incremental verifier checks manifests, not every byte", and
 * "a full verification action is available in diagnostics".
 *
 * ### Two levels, and the difference is what they read
 *
 * [verifyManifests] reads **metadata only**: does each committed file exist, and is it the length the
 * manifest recorded. That is a `stat` per file — microseconds — so it can run on every launch without
 * anybody noticing, and it catches the failure that actually happens: a user clearing the app's storage,
 * an SD card removed, a filesystem that lost a file.
 *
 * [verifyFully] additionally opens each file as media. That costs milliseconds per file rather than
 * microseconds, which is affordable when a person pressed a button and is not on a cold start.
 *
 * ### Neither claims the bytes are the server's
 *
 * The `ETag` is a validator, not a checksum (ADR-0018): it changes when the file changes, and nothing
 * requires it to be derived from the bytes. So *Repair* compares the stored validator against the server's
 * current one and reports **staleness**, which is a different and weaker claim than integrity — and the
 * button says so.
 *
 * ### A failed check does not delete anything
 *
 * DL-002: *"corrupt files are quarantined or removed only after user-visible confirmation."* This marks the
 * file `Failed`, which makes its book incomplete, which makes the download button offer a retry. The bytes
 * stay until somebody says otherwise.
 */
@Singleton
class DownloadVerifier @Inject constructor(
    private val repository: DownloadRepository,
    private val verifier: MediaContainerVerifier,
    private val locations: DownloadLocations,
    private val logger: Logger,
) : OfflineVerification {

    override suspend fun verifyManifests(): AppResult<VerificationReport> = verify(readContainers = false)

    override suspend fun verifyFully(): AppResult<VerificationReport> = verify(readContainers = true)

    private suspend fun verify(readContainers: Boolean): AppResult<VerificationReport> {
        val books = repository.observeAll().first().filter(OfflineBook::isComplete)
        var checked = 0
        var repaired = 0

        books.forEach { book ->
            val ownerAvailability = locations.availability(book.storageVolumeUuid)
            if (ownerAvailability == DownloadStorageState.Unavailable) {
                // #20: the physical copy is preserved exactly as-is while its known removable volume is absent.
                return@forEach
            }

            val classified = book.files.map { file ->
                checked++
                file to classifyStorage(
                    uri = file.uri,
                    expectedBytes = file.downloadedBytes,
                    readContainer = readContainers,
                    ownerAvailability = ownerAvailability,
                    verifier = verifier,
                )
            }

            // Unknown + unreachable is not evidence of corruption. A legacy row whose card is absent has no
            // durable owner to distinguish that from a missing internal file, so the conservative answer is
            // to preserve it until runtime evidence becomes available.
            if (classified.any { (_, state) -> state == DownloadStorageState.Unknown }) return@forEach

            val broken = classified.filter { (_, state) ->
                state == DownloadStorageState.Missing || state == DownloadStorageState.Corrupt
            }
            if (broken.isEmpty()) return@forEach

            broken.forEach { (file, _) ->
                repository.updateFile(book.serverId, book.itemId, file.copy(state = DownloadState.Failed))
            }
            repository.markFailed(
                book.serverId,
                book.itemId,
                summary = "${broken.size} of ${book.files.size} files are missing or unreadable.",
            )
            repaired++
            logger.warn(
                LogCategory.Sync,
                "A downloaded book is no longer intact",
                LogField.Count("brokenFiles", broken.size),
            )
        }

        logger.info(
            LogCategory.Sync,
            if (readContainers) "Downloads fully verified" else "Downloads checked against their manifests",
            LogField.Count("books", books.size),
            LogField.Count("files", checked),
            LogField.Count("brokenBooks", repaired),
        )
        return AppResult.Success(
            VerificationReport(booksChecked = books.size, filesChecked = checked, booksBroken = repaired),
        )
    }

    /**
     * Whether one committed file is still what the manifest says it is.
     *
     * The length check is the load-bearing one and is nearly free. A file truncated by a full disk, or
     * replaced by a filesystem that lost it, has the wrong length; a file that is simply gone fails the
     * first test. The container read is the expensive claim and is only made when asked for.
     */
    internal fun classifyStorage(
        uri: String,
        expectedBytes: Long,
        readContainer: Boolean,
        ownerAvailability: DownloadStorageState,
        verifier: MediaContainerVerifier,
    ): DownloadStorageState = when {
        ownerAvailability == DownloadStorageState.Unavailable -> DownloadStorageState.Unavailable
        uri.startsWith(CONTENT_SCHEME) -> DownloadStorageState.Unknown

        else -> {
            val file = fileOf(uri)
            when {
                file == null || !file.isFile -> if (ownerAvailability == DownloadStorageState.Unknown) {
                    DownloadStorageState.Unknown
                } else {
                    DownloadStorageState.Missing
                }

                expectedBytes > 0 && file.length() != expectedBytes -> DownloadStorageState.Missing
                readContainer && !verifier.isReadable(file) -> DownloadStorageState.Corrupt
                else -> DownloadStorageState.Available
            }
        }
    }

    /** The `file://` URI as a path, or `null` when it is not one or cannot be parsed. */
    private fun fileOf(uri: String): File? =
        resultOf { URI(uri).takeIf { it.scheme == FILE_SCHEME }?.let(::File) }.getOrNull()

    private companion object {
        const val FILE_SCHEME = "file"
        const val CONTENT_SCHEME = "content://"
    }
}
