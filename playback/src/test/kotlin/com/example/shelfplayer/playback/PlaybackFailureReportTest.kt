package com.example.shelfplayer.playback

import androidx.media3.session.SessionError
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC PLAY-001 — what a car is told when a book will not play.
 *
 * The device report was that the Android Auto player says nothing about anything. A book that does not
 * start, with no sentence and no action, is the case this decides.
 */
class PlaybackFailureReportTest {

    /** A retry in flight is not worth interrupting a driver over: the book may simply resume. */
    @Test
    fun `nothing is reported while a retry is still coming`() {
        assertNull(PlaybackFailureReport.of(httpStatus = 401, willRetry = true))
        assertNull(PlaybackFailureReport.of(httpStatus = null, willRetry = true))
        assertNull(PlaybackFailureReport.of(httpStatus = 500, willRetry = true))
    }

    @Test
    fun `an expired token is a credential failure the driver can act on`() {
        val report = PlaybackFailureReport.of(httpStatus = 401, willRetry = false)

        assertEquals(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, report?.code)
        assertEquals(PlaybackFailureReport.Message.CredentialsExpired, report?.message)
        assertTrue(report?.isCredentialFailure == true)
    }

    /**
     * 403 is a token the server accepts but will not honour here. To a driver that is the same thing as an
     * expired one and has the same remedy, so it gets the same sentence.
     */
    @Test
    fun `a refused token reads as a credential failure too`() {
        val report = PlaybackFailureReport.of(httpStatus = 403, willRetry = false)

        assertEquals(PlaybackFailureReport.Message.CredentialsExpired, report?.message)
        assertTrue(report?.isCredentialFailure == true)
    }

    /** A server error is not something a driver can fix, and must not offer them a sign-in. */
    @Test
    fun `a server error is reported without an action`() {
        val report = PlaybackFailureReport.of(httpStatus = 500, willRetry = false)

        assertEquals(SessionError.ERROR_IO, report?.code)
        assertEquals(PlaybackFailureReport.Message.ServerUnreachable, report?.message)
        assertFalse(report?.isCredentialFailure == true)
    }

    /**
     * The failure never reached the server at all — a refused connection or an unreachable host on the
     * car's network. The commonest case in a car, and the one a `null` status represents.
     */
    @Test
    fun `a failure that never reached the server reads as unreachable`() {
        val report = PlaybackFailureReport.of(httpStatus = null, willRetry = false)

        assertEquals(SessionError.ERROR_IO, report?.code)
        assertEquals(PlaybackFailureReport.Message.ServerUnreachable, report?.message)
        assertFalse(report?.isCredentialFailure == true)
    }

    /** A 404 is the server answering; it is not a credential problem and must not claim to be. */
    @Test
    fun `a missing file is not a credential failure`() {
        assertFalse(PlaybackFailureReport.of(httpStatus = 404, willRetry = false)?.isCredentialFailure == true)
    }
}
