package com.example.shelfplayer.core.network.http

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** PRODUCT_SPEC AUTH-001 — base URL normalization. */
class ServerUrlNormalizerTest {

    private val normalizer = ServerUrlNormalizer()

    private fun normalize(input: String) = normalizer.normalize(input)

    private fun value(input: String): NormalizedServerUrl {
        val result = normalize(input)
        assertIs<AppResult.Success<NormalizedServerUrl>>(result)
        return result.value
    }

    @Test
    fun `a url without a scheme is proposed as https`() {
        val normalized = value("books.example.org")
        assertEquals("https://books.example.org", normalized.value)
        assertTrue(normalized.wasSchemeAssumed)
        assertEquals(false, normalized.isCleartext)
    }

    @Test
    fun `trailing slashes are removed`() {
        assertEquals("https://books.example.org", value("https://books.example.org/").value)
        assertEquals("https://books.example.org", value("https://books.example.org///").value)
    }

    /** The acceptance criterion that makes trailing-slash trimming non-trivial. */
    @Test
    fun `a required subpath is preserved`() {
        assertEquals(
            "https://example.org/audiobooks",
            value("https://example.org/audiobooks/").value,
        )
        assertEquals(
            "https://example.org/media/abs",
            value("https://example.org/media/abs").value,
        )
    }

    @Test
    fun `a non-default port is preserved and a default port is dropped`() {
        assertEquals("https://example.org:13378", value("https://example.org:13378").value)
        assertEquals("https://example.org", value("https://example.org:443").value)
        assertEquals("http://example.org", value("http://example.org:80").value)
    }

    @Test
    fun `an explicit http scheme is preserved and reported as cleartext`() {
        val normalized = value("http://192.168.1.10:13378")
        assertEquals("http://192.168.1.10:13378", normalized.value)
        assertTrue(normalized.isCleartext)
        assertEquals(false, normalized.wasSchemeAssumed)
    }

    /**
     * PRODUCT_SPEC 10.3 / AUTH-003 — a pasted deep link often carries a token.
     *
     * Silently dropping the query would look like it worked and would store a base URL the user did
     * not intend; rejecting it is the honest outcome.
     */
    @Test
    fun `a query string is rejected rather than silently dropped`() {
        val result = normalize("https://example.org/?token=SECRET")
        assertIs<AppResult.Failure>(result)
        val error = result.error
        assertIs<AppError.Validation>(error)
        assertEquals("unexpected_query", error.fieldErrors["serverUrl"])
    }

    @Test
    fun `credentials embedded in the url are rejected`() {
        val result = normalize("https://user:hunter2@example.org")
        assertIs<AppResult.Failure>(result)
        assertIs<AppError.Validation>(result.error)
    }

    @Test
    fun `blank and malformed input produce field-level validation errors`() {
        assertIs<AppResult.Failure>(normalize("   "))
        assertIs<AppResult.Failure>(normalize("https://"))
        assertIs<AppResult.Failure>(normalize("not a url at all"))
    }
}
