package com.example.shelfplayer.data.downloads

import android.media.MediaMetadataRetriever
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.debug
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.resultOf
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC DL-002 — "readable media container", which is the fourth and least obvious of the four
 * minimum checks.
 *
 * The other three — status, expected length, non-zero size — all pass for a file that is the right number
 * of the wrong bytes: an HTML error page a proxy substituted, a captive-portal login form, a truncated
 * body a server sent with a full `Content-Length`. Each of those becomes a book that appears downloaded
 * and produces silence in a car with no network, which is the exact failure Phase 3 exists to prevent.
 *
 * ### Metadata, not decoding
 *
 * [MediaMetadataRetriever] parses the container's header and index. It does not decode audio, so this
 * costs milliseconds on a hundred-megabyte file rather than the minutes a real decode would — which is
 * what makes it affordable on every committed file, and what DL-002 means by "an incremental verifier
 * checks manifests, not every byte".
 *
 * It is therefore an honest but limited claim: *this is a container the platform can open and it declares
 * a duration*. It is not a claim that every frame decodes. Nothing available to a client can make the
 * stronger claim — the server sends an `ETag`, which is a validator rather than a checksum (ADR-0018).
 */
interface MediaContainerVerifier {
    /** Whether [file] is something the platform can open as media. */
    fun isReadable(file: File): Boolean
}

/**
 * The real check, on the platform's own parser.
 *
 * An interface with one implementation because the *test* for anything that depends on it cannot use this
 * one: Robolectric has no media stack, so [MediaMetadataRetriever] answers nothing there. The seam is what
 * lets a downloader test exercise the decision the check drives — commit or refuse — which is the part with
 * consequences.
 */
@Singleton
class AndroidMediaContainerVerifier @Inject constructor(private val logger: Logger) : MediaContainerVerifier {

    /**
     * Whether [file] is something the platform can open as media.
     *
     * A throwing retriever is the ordinary answer for a file that is not media, not an exceptional
     * condition — a captive-portal HTML page throws here, and that is the check working. `resultOf` is the
     * app's single exception boundary (ADR-0003) and is used for exactly that reason: it converts the
     * throw into an answer while still rethrowing cancellation, which a bare `catch` would swallow.
     *
     * The failure is logged rather than discarded, with the exception's *type* and nothing else. The
     * message can carry a path, and a path carries a book id (PRODUCT_SPEC 14.5).
     */
    override fun isReadable(file: File): Boolean {
        if (!file.isFile || file.length() <= 0) return false
        val retriever = MediaMetadataRetriever()
        val readable = resultOf {
            retriever.setDataSource(file.absolutePath)
            // A duration is the cheapest proof that the header parsed and the index is present. A
            // container that opens but declares no duration is one the player cannot seek in, which for an
            // audiobook is the same as unusable.
            (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0
        }
        retriever.release()

        return when (readable) {
            is AppResult.Success -> readable.value
            is AppResult.Failure -> {
                logger.debug(
                    LogCategory.Sync,
                    "A downloaded file could not be opened as media",
                    LogField.Public("failure", readable.error::class.simpleName.orEmpty()),
                )
                false
            }
        }
    }
}
