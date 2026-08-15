package com.example.shelfplayer.data.downloads

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.core.model.download.OfflineBook
import com.example.shelfplayer.core.model.download.VerificationReport
import com.example.shelfplayer.core.model.getOrNull
import com.example.shelfplayer.core.model.resultOf
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
    private val logger: Logger,
) : OfflineVerification {

    override suspend fun verifyManifests(): AppResult<VerificationReport> = verify(readContainers = false)

    override suspend fun verifyFully(): AppResult<VerificationReport> = verify(readContainers = true)

    private suspend fun verify(readContainers: Boolean): AppResult<VerificationReport> {
        val books = repository.observeAll().first().filter(OfflineBook::isComplete)
        var checked = 0
        var repaired = 0

        books.forEach { book ->
            val broken = book.files.filter { file ->
                checked++
                !isIntact(file.uri, file.downloadedBytes, readContainers)
            }
            if (broken.isEmpty()) return@forEach

            // PLAY-003's deferred criterion: "a missing local part prevents a false downloaded state". The
            // file is marked failed, which makes `isComplete` false, which turns the button back into a
            // retry. Nothing is deleted — the user is not asked to lose a book because one part went.
            broken.forEach { file ->
                repository.updateFile(book.serverId, book.itemId, file.copy(state = DownloadState.Failed))
            }
            repository.markFailed(
                book.serverId,
                book.itemId,
                summary = "${broken.size} of ${book.files.size} files are missing or the wrong size.",
            )
            repaired++
            logger.warn(
                LogCategory.Sync,
                "A downloaded book is no longer intact",
                LogField.Count("missingFiles", broken.size),
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
    private fun isIntact(uri: String, expectedBytes: Long, readContainer: Boolean): Boolean {
        // A `content://` location — decision 4's user-chosen folder — cannot be checked with `File`, and is
        // reported intact rather than broken. A verifier that failed a book because it could not read its
        // storage would be destroying exactly what it exists to protect. SAF checking arrives with SAF
        // writing; until then no manifest carries one of these.
        if (uri.startsWith(CONTENT_SCHEME)) return true

        val file = fileOf(uri) ?: return false
        return file.isFile &&
            (expectedBytes <= 0 || file.length() == expectedBytes) &&
            (!readContainer || verifier.isReadable(file))
    }

    /** The `file://` URI as a path, or `null` when it is not one or cannot be parsed. */
    private fun fileOf(uri: String): File? =
        resultOf { URI(uri).takeIf { it.scheme == FILE_SCHEME }?.let(::File) }.getOrNull()

    private companion object {
        const val FILE_SCHEME = "file"
        const val CONTENT_SCHEME = "content://"
    }
}
