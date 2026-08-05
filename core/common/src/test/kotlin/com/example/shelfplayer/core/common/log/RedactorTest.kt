package com.example.shelfplayer.core.common.log

import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PRODUCT_SPEC 14.5 / AUTH-003 — nothing private survives redaction. */
class RedactorTest {

    private val redactor = DefaultRedactor(RedactionPolicy.Default)

    @Test
    fun `public values pass through unchanged`() {
        assertEquals("GET", redactor.render(LogField.Public("method", "GET")))
        assertEquals("200", redactor.render(LogField.Public("status", 200)))
        assertEquals("42", redactor.render(LogField.Count("items", 42)))
        assertEquals("125ms", redactor.render(LogField.Millis("elapsed", 125)))
    }

    /**
     * A [LogField.Secret] carries no value at all, so there is nothing to leak even if the policy
     * were somehow widened.
     */
    @Test
    fun `a secret never renders anything`() {
        assertEquals("<redacted>", redactor.render(LogField.Secret("authorization")))
    }

    @Test
    fun `server host and media title are redacted by default`() {
        val host = redactor.render(LogField.ServerHost("host", "books.example.org"))
        val title = redactor.render(LogField.MediaTitle("title", "The Salt Harbour"))

        assertFalse(host.contains("books.example.org"))
        assertFalse(title.contains("Salt Harbour"))
        assertTrue(host.startsWith("#"))
        assertTrue(title.startsWith("#"))
    }

    @Test
    fun `opting in reveals only the field that was opted into`() {
        val opted = DefaultRedactor(RedactionPolicy(includeServerHost = true))

        assertEquals("books.example.org", opted.render(LogField.ServerHost("host", "books.example.org")))
        assertFalse(
            opted.render(LogField.MediaTitle("title", "The Salt Harbour")).contains("Salt Harbour"),
        )
    }

    @Test
    fun `redaction is stable so two events about the same value correlate`() {
        val first = redactor.render(LogField.Identifier("item", "book-voyage-1"))
        val second = redactor.render(LogField.Identifier("item", "book-voyage-1"))
        val other = redactor.render(LogField.Identifier("item", "book-voyage-2"))

        assertEquals(first, second)
        assertFalse(first == other)
    }

    /** PRODUCT_SPEC 10.3 — a token that arrives in a query string must not reach the log. */
    @Test
    fun `url redaction drops query, fragment and credentials`() {
        val rendered = redactor.render(
            LogField.Url("url", "https://user:hunter2@books.example.org/api/items/42?token=SECRET#x"),
        )

        assertFalse(rendered.contains("SECRET"))
        assertFalse(rendered.contains("hunter2"))
        assertFalse(rendered.contains("user"))
        assertFalse(rendered.contains("books.example.org"))
        assertFalse(rendered.contains("42"))
        assertTrue(rendered.startsWith("https://"))
    }

    @Test
    fun `an unparseable url renders as fully redacted rather than raw`() {
        val rendered = redactor.render(LogField.Url("url", "ht!tp://not a url ?token=SECRET"))
        assertFalse(rendered.contains("SECRET"))
    }

    /** PRODUCT_SPEC 22.20 — a media path names the author, the series and the book. */
    @Test
    fun `file paths keep only the extension`() {
        val rendered = redactor.render(
            LogField.FilePath("file", "/storage/Audiobooks/Marisol Holt/The Salt Harbour/part01.mp3"),
        )

        assertFalse(rendered.contains("Salt Harbour"))
        assertFalse(rendered.contains("Marisol"))
        assertTrue(rendered.endsWith(".mp3"))
    }

    /**
     * PRODUCT_SPEC 14.5 — an exception message is not safe to log.
     *
     * `UnknownHostException.getMessage()` is the hostname, which is exactly what the default policy
     * redacts everywhere else.
     */
    @Test
    fun `throwables render as their class chain, never their message`() {
        val rendered = redactor.renderThrowable(
            IOException("failed", UnknownHostException("books.example.org")),
        )

        assertFalse(rendered.contains("books.example.org"))
        assertFalse(rendered.contains("failed"))
        assertEquals("java.io.IOException <- java.net.UnknownHostException", rendered)
    }
}
