package com.example.shelfplayer.playback

import androidx.media3.common.PlaybackException
import androidx.media3.session.SessionError
import com.example.shelfplayer.core.model.AppError
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
        assertNull(PlaybackFailureReport.of(BAD_STATUS, httpStatus = 401, localFile = false, willRetry = true))
        assertNull(PlaybackFailureReport.of(NETWORK_FAILED, httpStatus = null, localFile = false, willRetry = true))
        assertNull(PlaybackFailureReport.of(BAD_STATUS, httpStatus = 500, localFile = false, willRetry = true))
    }

    @Test
    fun `an expired token is a credential failure the driver can act on`() {
        val report = PlaybackFailureReport.of(BAD_STATUS, httpStatus = 401, localFile = false, willRetry = false)

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
        val report = PlaybackFailureReport.of(BAD_STATUS, httpStatus = 403, localFile = false, willRetry = false)

        assertEquals(PlaybackFailureReport.Message.CredentialsExpired, report?.message)
        assertTrue(report?.isCredentialFailure == true)
    }

    /** A server error is not something a driver can fix, and must not offer them a sign-in. */
    @Test
    fun `a server error is reported without an action`() {
        val report = PlaybackFailureReport.of(BAD_STATUS, httpStatus = 500, localFile = false, willRetry = false)

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
        val report = PlaybackFailureReport.of(NETWORK_FAILED, httpStatus = null, localFile = false, willRetry = false)

        assertEquals(SessionError.ERROR_IO, report?.code)
        assertEquals(PlaybackFailureReport.Message.ServerUnreachable, report?.message)
        assertFalse(report?.isCredentialFailure == true)
    }

    /** A 404 is the server answering; it is not a credential problem and must not claim to be. */
    @Test
    fun `a missing file is not a credential failure`() {
        assertFalse(
            PlaybackFailureReport.of(
                BAD_STATUS,
                httpStatus = 404,
                localFile = false,
                willRetry = false,
            )?.isCredentialFailure ==
                true,
        )
    }

    /*
     * The band, not just the status. A review found every failure that never reached a server — a decoder
     * error, an unsupported container, a missing local file — telling the driver "Can't reach your server
     * from here", which is a confident sentence about the wrong thing.
     */

    @Test
    fun `a decoder failure is not a server outage`() {
        val report = PlaybackFailureReport.of(
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            httpStatus = null,
            localFile = false,
            willRetry = false,
        )

        assertEquals(PlaybackFailureReport.Message.FileNotPlayable, report?.message)
    }

    @Test
    fun `an unsupported container is not a server outage`() {
        val report = PlaybackFailureReport.of(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            httpStatus = null,
            localFile = false,
            willRetry = false,
        )

        assertEquals(PlaybackFailureReport.Message.FileNotPlayable, report?.message)
    }

    @Test
    fun `an audio-track failure is this device, not the network`() {
        val report = PlaybackFailureReport.of(
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            httpStatus = null,
            localFile = false,
            willRetry = false,
        )

        assertEquals(PlaybackFailureReport.Message.FileNotPlayable, report?.message)
    }

    /**
     * An unknown failure gets **silence**, which is the honest answer: no sentence is true of it. The event
     * log still records the code, which is where a diagnosis belongs.
     */
    @Test
    fun `an unspecified failure says nothing at all`() {
        assertNull(
            PlaybackFailureReport.of(
                PlaybackException.ERROR_CODE_UNSPECIFIED,
                httpStatus = null,
                localFile = false,
                willRetry = false,
            ),
        )
        assertNull(
            PlaybackFailureReport.of(
                PlaybackException.ERROR_CODE_REMOTE_ERROR,
                httpStatus = null,
                localFile = false,
                willRetry = false,
            ),
        )
    }

    /** Credentials win over the band: they arrive as an ordinary bad-status I/O error. */
    @Test
    fun `a credential failure is recognised inside the io band`() {
        val report = PlaybackFailureReport.of(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            httpStatus = 401,
            localFile = false,
            willRetry = false,
        )

        assertTrue(report?.isCredentialFailure == true)
    }

    /*
     * Evidence beats the band. `FileDataSource` throws its own exception type, so a local read is proven;
     * a review found the I/O band telling a listener the *server* was unreachable for a missing download,
     * which never touched the network and is product priority 3.
     */

    @Test
    fun `a missing download is a file problem, not a server outage`() {
        val report = PlaybackFailureReport.of(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            httpStatus = null,
            localFile = true,
            willRetry = false,
        )

        assertEquals(PlaybackFailureReport.Message.FileNotPlayable, report?.message)
    }

    @Test
    fun `an unreadable local file is a file problem too`() {
        val report = PlaybackFailureReport.of(
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            httpStatus = null,
            localFile = true,
            willRetry = false,
        )

        assertEquals(PlaybackFailureReport.Message.FileNotPlayable, report?.message)
    }

    /** The same code with no local read behind it is still the server's. */
    @Test
    fun `a file the server does not have is still a server failure`() {
        val report = PlaybackFailureReport.of(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            httpStatus = 404,
            localFile = false,
            willRetry = false,
        )

        assertEquals(PlaybackFailureReport.Message.ServerUnreachable, report?.message)
    }

    /*
     * The session-opening path, which a review found reported nothing at all — and it is the one an expired
     * credential actually travels, because nothing reaches the player for `onPlayerError` to see.
     */

    @Test
    fun `an authentication failure opening the session offers the sign-in`() {
        val report = PlaybackFailureReport.ofSessionFailure(AppError.Authentication("expired"))

        assertEquals(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, report?.code)
        assertTrue(report?.isCredentialFailure == true)
    }

    @Test
    fun `a network failure opening the session explains without an action`() {
        val report = PlaybackFailureReport.ofSessionFailure(AppError.Network("no route"))

        assertEquals(PlaybackFailureReport.Message.ServerUnreachable, report?.message)
        assertFalse(report?.isCredentialFailure == true)
    }

    /**
     * Everything else stays silent, for the reason an unspecified `PlaybackException` does: no sentence is
     * true of it. A missing permission is not fixed by signing in again, and saying so would be the fourth
     * confident-but-wrong message on this branch.
     */
    @Test
    fun `other session failures say nothing`() {
        assertNull(PlaybackFailureReport.ofSessionFailure(AppError.Authorization("no permission")))
        assertNull(PlaybackFailureReport.ofSessionFailure(AppError.Validation("bad request")))
    }

    private companion object {
        val BAD_STATUS = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        val NETWORK_FAILED = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
    }
}
