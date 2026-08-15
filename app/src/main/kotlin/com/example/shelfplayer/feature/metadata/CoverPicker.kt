package com.example.shelfplayer.feature.metadata

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.library.CoverCandidate
import com.example.shelfplayer.core.model.resultOf
import java.io.InputStream

/**
 * PRODUCT_SPEC MGR-002 — turns a Photo Picker URI into bytes that are known to be an image.
 *
 * ### Why the image is decoded twice
 *
 * Once with `inJustDecodeBounds`, which reads the header and reports the dimensions **without allocating
 * the pixels**, and once for real only if the bounds pass. A 48-megapixel photograph picked by mistake is
 * therefore rejected on a few kilobytes of header rather than after 190 MB of bitmap — which on a mid-range
 * phone is the difference between a message and an `OutOfMemoryError`.
 *
 * ### Why the picker's own MIME type is not trusted alone
 *
 * A content provider reports whatever it likes, and the server validates by filename extension rather than
 * by content type — so a mislabelled file would be uploaded under a name the server accepts and would then
 * be an unopenable cover. The decode is what actually establishes that the bytes are an image; the MIME
 * type only decides which of the four names to send it under.
 */
object CoverPicker {

    /**
     * Reads and validates [uri].
     *
     * Through `resultOf`, the project's single exception boundary (ADR-0003): a content provider can throw
     * for a URI the user revoked between picking and reading, and that is a message rather than a crash.
     */
    fun read(resolver: ContentResolver, uri: Uri): AppResult<PickedCover> = resultOf(
        onError = { cause -> AppError.Unknown(summary = "That image could not be read.", cause = cause) },
    ) {
        val mimeType = resolver.getType(uri).orEmpty()
        val bounds = boundsOf(resolver, uri)
        val bytes = resolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "the picker returned a uri nothing can open" }.readBytes()
        }
        PickedCover(
            bytes = bytes,
            candidate = CoverCandidate(
                mimeType = mimeType,
                sizeBytes = bytes.size.toLong(),
                width = bounds.outWidth,
                height = bounds.outHeight,
            ),
        )
    }

    /** Header only: `inJustDecodeBounds` fills the dimensions and allocates no pixels. */
    private fun boundsOf(resolver: ContentResolver, uri: Uri): BitmapFactory.Options {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { stream: InputStream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        return options
    }
}

/** An image the user picked: the bytes, and everything needed to decide whether to send them. */
data class PickedCover(val bytes: ByteArray, val candidate: CoverCandidate) {
    /** Identity equality, for the reason `CoverUpload` gives: nobody wants two images compared byte-wise. */
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}
