package com.example.shelfplayer.core.network.http

import com.example.shelfplayer.core.model.AppError
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** PRODUCT_SPEC 14.1 / 14.3 — HTTP and transport failures map to the documented taxonomy. */
class NetworkErrorMapperTest {

    private val mapper = NetworkErrorMapper()

    @Test
    fun `transport failures map to their specific case`() {
        assertIs<AppError.Timeout>(mapper.fromThrowable(SocketTimeoutException()))
        assertIs<AppError.Network>(mapper.fromThrowable(UnknownHostException("host")))
        assertIs<AppError.Security>(mapper.fromThrowable(SSLHandshakeException("bad cert")))
        assertIs<AppError.Network>(mapper.fromThrowable(IOException("reset")))
        assertIs<AppError.Unknown>(mapper.fromThrowable(IllegalStateException("bug")))
    }

    /** PRODUCT_SPEC AUTH-004 — a 401 requires reauthentication and is never blind-retried. */
    @Test
    fun `401 maps to authentication and is not retryable`() {
        val error = mapper.fromStatus(401)
        assertIs<AppError.Authentication>(error)
        assertTrue(error.requiresReauthentication)
        assertFalse(error.isRetryable)
    }

    /** PRODUCT_SPEC 5.2 — a 403 refreshes permissions rather than retrying. */
    @Test
    fun `403 maps to authorization and is not retryable`() {
        val error = mapper.fromStatus(403)
        assertIs<AppError.Authorization>(error)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `409 maps to conflict so the user can be shown the divergence`() {
        assertIs<AppError.Conflict>(mapper.fromStatus(409))
    }

    @Test
    fun `429 carries Retry-After and stays retryable`() {
        val error = mapper.fromStatus(429, retryAfterSeconds = 30)
        assertIs<AppError.Server>(error)
        assertEquals(30L, error.retryAfterSeconds)
        assertTrue(error.isRetryable)
    }

    @Test
    fun `4xx is not retryable but 5xx is`() {
        assertFalse(mapper.fromStatus(404).isRetryable)
        assertFalse(mapper.fromStatus(422).isRetryable)
        assertTrue(mapper.fromStatus(500).isRetryable)
        assertTrue(mapper.fromStatus(502).isRetryable)
    }

    /** PRODUCT_SPEC 14.5 — a user-facing summary must not embed the host or the response body. */
    @Test
    fun `summaries never contain transport detail`() {
        val error = mapper.fromThrowable(UnknownHostException("books.example.org"))
        assertFalse(error.summary.contains("books.example.org"))
    }
}
