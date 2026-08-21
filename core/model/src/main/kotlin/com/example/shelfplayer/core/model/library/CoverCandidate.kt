package com.example.shelfplayer.core.model.library

/**
 * PRODUCT_SPEC MGR-002 — an image the user picked, and whether it can be sent.
 *
 * ### Why the app validates at all when the server also does
 *
 * The server checks one thing: the filename's extension. It does not decode the image, does not look at
 * its dimensions, and enforces no size limit — so an eight-megabyte photograph of a bookshelf, a zero-byte
 * file, and a `.png` that is actually a text document are all accepted and all become somebody's cover.
 *
 * MGR-002 asks for "MIME type, decode success, dimensions, and configured size limit" precisely because
 * the server asks for none of them. This is the only place they are checked.
 */
data class CoverCandidate(val mimeType: String, val sizeBytes: Long, val width: Int, val height: Int) {
    /** `null` when the image can be uploaded. */
    fun rejection(): CoverRejection? = when {
        mimeType.lowercase() !in SUPPORTED_TYPES -> CoverRejection.UnsupportedType
        // A decode that produced no dimensions is a decode that failed, whatever the extension claimed.
        width <= 0 || height <= 0 -> CoverRejection.NotAnImage
        sizeBytes > MAX_BYTES -> CoverRejection.TooLarge
        width < MIN_EDGE || height < MIN_EDGE -> CoverRejection.TooSmall
        else -> null
    }

    companion object {
        /**
         * The four the server's own validator names. Plus `image/jpg`, which is not a real MIME type and
         * which some Android content providers report anyway.
         */
        val SUPPORTED_TYPES = setOf("image/png", "image/jpeg", "image/jpg", "image/webp")

        /**
         * PRODUCT_SPEC MGR-002's "configured size limit", configured here because nothing else configures
         * it: the server accepts any size.
         *
         * Ten megabytes is chosen against what a cover is *for*. Audiobookshelf serves covers resized, and
         * the largest sensible source is a scan of a physical sleeve — comfortably under this. What the
         * limit actually prevents is the accident: a 48-megapixel phone photograph picked by mistake, held
         * whole in memory on the way to the wire.
         */
        const val MAX_BYTES: Long = 10L * 1024 * 1024

        /**
         * Below this, the image is a thumbnail rather than a cover.
         *
         * Chosen at the low end deliberately. A self-hosted library may hold genuinely old, genuinely small
         * scans, and refusing one because it is not crisp would be this app deciding what somebody's own
         * library should look like. The bar is only high enough to catch an icon picked by mistake.
         */
        const val MIN_EDGE = 200
    }
}

/** Why an image cannot be used as a cover. The wording is the caller's. */
enum class CoverRejection {
    UnsupportedType,
    NotAnImage,
    TooLarge,
    TooSmall,
}
