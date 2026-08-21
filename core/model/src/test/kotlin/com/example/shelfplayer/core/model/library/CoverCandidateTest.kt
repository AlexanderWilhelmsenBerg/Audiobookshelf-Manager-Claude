package com.example.shelfplayer.core.model.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * PRODUCT_SPEC MGR-002 — "validates MIME type, decode success, dimensions, and configured size limit".
 *
 * All four are this app's own checks. The server validates the filename's extension and nothing else, so
 * every rule below is the only thing standing between a mis-tapped 48-megapixel photograph and somebody's
 * library.
 */
class CoverCandidateTest {

    @Test
    fun `an ordinary cover is accepted`() {
        assertNull(candidate().rejection())
    }

    @Test
    fun `a file that is not one of the four types is refused`() {
        assertEquals(CoverRejection.UnsupportedType, candidate(mimeType = "image/gif").rejection())
        assertEquals(CoverRejection.UnsupportedType, candidate(mimeType = "application/pdf").rejection())
    }

    /**
     * `image/jpg` is not a real MIME type and some Android content providers report it anyway.
     *
     * Refusing a JPEG because a device spelled its type unusually would be this app's bug, not the user's.
     */
    @Test
    fun `the misspelled jpeg type is accepted`() {
        assertNull(candidate(mimeType = "image/jpg").rejection())
    }

    /**
     * Dimensions of zero mean the decoder could not read it, whatever the extension claimed.
     *
     * This is the check that catches a `.png` that is really a text file — the case the server's
     * extension-only validation passes straight through.
     */
    @Test
    fun `a file that does not decode is refused`() {
        assertEquals(CoverRejection.NotAnImage, candidate(width = 0, height = 0).rejection())
    }

    @Test
    fun `an image over the size limit is refused`() {
        val huge = candidate(sizeBytes = CoverCandidate.MAX_BYTES + 1)

        assertEquals(CoverRejection.TooLarge, huge.rejection())
        assertNull(candidate(sizeBytes = CoverCandidate.MAX_BYTES).rejection())
    }

    @Test
    fun `an icon-sized image is refused`() {
        assertEquals(CoverRejection.TooSmall, candidate(width = 64, height = 64).rejection())
    }

    /**
     * The order matters: an unreadable file is reported as unreadable rather than as too small.
     *
     * A zero-by-zero image is technically also under the minimum edge, and telling somebody their file is
     * "too small" when it is actually not an image sends them to find a bigger version of the wrong thing.
     */
    @Test
    fun `an unreadable file is reported as unreadable rather than as too small`() {
        assertEquals(CoverRejection.NotAnImage, candidate(width = 0, height = 0, sizeBytes = 12).rejection())
    }

    private fun candidate(
        mimeType: String = "image/jpeg",
        sizeBytes: Long = 400_000,
        width: Int = 1400,
        height: Int = 1400,
    ) = CoverCandidate(mimeType = mimeType, sizeBytes = sizeBytes, width = width, height = height)
}
