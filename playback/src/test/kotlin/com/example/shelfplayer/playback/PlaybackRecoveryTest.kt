package com.example.shelfplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-001, product priority 1 — a stopped book gets put back on its feet, but not forever.
 *
 * The device report this exists for: a multi-file book stopped while being seeked, and then would not
 * start, would not seek, and only recovered when a different book was loaded. An errored player is
 * `STATE_IDLE`, and an idle player ignores everything except `prepare()`.
 */
@OptIn(UnstableApi::class)
class PlaybackRecoveryTest {

    private val recovery = PlaybackRecovery()

    @Test
    fun `a network error is retried, with a growing delay`() {
        assertEquals(1.seconds, recovery.onError(ioError()))
        assertEquals(2.seconds, recovery.onError(ioError()))
        assertEquals(3.seconds, recovery.onError(ioError()))
    }

    /**
     * The bound. A fourth attempt on a book that has failed three times running is not recovery, it is a
     * loop against somebody's self-hosted server — and the listener is owed the failure instead.
     */
    @Test
    fun `retrying stops after three attempts`() {
        repeat(3) { assertNotNull(recovery.onError(ioError())) }

        assertNull(recovery.onError(ioError()))
        assertEquals(3, recovery.attemptCount)
    }

    /**
     * Audio coming out is the only honest evidence that recovery worked.
     *
     * Without this, a book that drops a connection once an hour on a flaky network would exhaust its three
     * attempts over an afternoon and then stop recovering — a bug nobody would ever reproduce deliberately.
     */
    @Test
    fun `playing resets the count`() {
        repeat(3) { recovery.onError(ioError()) }
        recovery.onPlaying()

        assertEquals(1.seconds, recovery.onError(ioError()))
    }

    @Test
    fun `a new book starts with a clean slate`() {
        repeat(3) { recovery.onError(ioError()) }
        recovery.onBookChanged()

        assertEquals(1.seconds, recovery.onError(ioError()))
    }

    /**
     * A malformed file will still be malformed on the fourth attempt.
     *
     * Retrying it turns one honest failure into four seconds of pretending, and buries the error code that
     * would have told somebody what was actually wrong.
     */
    @Test
    fun `a decoding error is not retried`() {
        assertNull(recovery.onError(error(PlaybackException.ERROR_CODE_DECODING_FAILED)))
        assertNull(recovery.onError(error(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)))
    }

    /**
     * Two IO errors that are still not worth retrying.
     *
     * A file the server does not have will not appear, and a 401 needs a new token rather than another
     * attempt. PLAY-003 asks for stopping safely and offering repair, which is what surfacing them does.
     */
    @Test
    fun `a missing file and a refused request are not retried`() {
        assertNull(recovery.onError(error(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)))
        assertNull(recovery.onError(error(PlaybackException.ERROR_CODE_IO_NO_PERMISSION)))
    }

    /** The whole IO block is retryable, including codes a future Media3 might add to it. */
    @Test
    fun `every other io error is retried`() {
        listOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        ).forEach { code ->
            val fresh = PlaybackRecovery()
            assertNotNull(fresh.onError(error(code)), "code $code should be retried")
        }
    }

    private fun ioError() = error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

    private fun error(code: Int) = PlaybackException("test", null, code)
}
