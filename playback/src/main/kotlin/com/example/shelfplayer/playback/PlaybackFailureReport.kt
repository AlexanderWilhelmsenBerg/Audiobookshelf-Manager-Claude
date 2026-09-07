package com.example.shelfplayer.playback

import androidx.media3.common.PlaybackException
import androidx.media3.session.SessionError

/**
 * PRODUCT_SPEC PLAY-001 — what a car should be *told* when a book will not play.
 *
 * ### Why this exists
 *
 * A device run reported the Android Auto player as silent about everything. The routing half of that is
 * ADR-0029 §8; this is the other half. When a self-hosted server's credentials have expired, or the server
 * is simply not reachable from the car's network, BookWave stops the book and says nothing — so the driver
 * sees a book that does not start and has no way to learn why. `MediaSession.sendError` puts a sentence on
 * the player screen instead, and for the credential case it can carry a labelled action.
 *
 * ### The failure's *kind* decides, not just its HTTP status
 *
 * The first version of this read only the HTTP status out of the data-source chain, and a review caught
 * what that cost: a missing downloaded file, an unsupported container and a decoder failure all carry no
 * HTTP status at all, so every one of them told the driver **"Can't reach your server from here"** — a
 * confident sentence about the wrong thing, on a phone that had never left the driveway. Media3's own
 * error code is the fact that separates them, and it comes in bands: `2xxx` is I/O, `3xxx` parsing, `4xxx`
 * decoding, `5xxx` the audio track.
 *
 * ### Why the classification is a pure function
 *
 * The mapping from *what went wrong* to *what a driver should read* is a product decision with four
 * branches, one of which is silence. Every other decision on this branch left inline at a call site turned
 * out to be untestable and, twice, silently wrong. This one is asserted.
 *
 * Deliberately **not** a general error taxonomy: `AppError` already is one, and it lives on the other side
 * of a module boundary. This maps the narrow set of cases a car can usefully be told about.
 */
internal object PlaybackFailureReport {

    /** What the car should be told. */
    data class Report(val code: Int, val message: Message, val isCredentialFailure: Boolean)

    /** Which sentence to draw. The strings live in resources; this names one without resolving it. */
    enum class Message { CredentialsExpired, ServerUnreachable, FileNotPlayable }

    /**
     * Classifies a failure that playback has **given up** on, or `null` to say nothing.
     *
     * @param errorCode Media3's `PlaybackException.errorCode`, whose band is what distinguishes a network
     *   failure from a local one.
     * @param httpStatus the response code from the data-source chain, or `null` if the failure never
     *   reached the server — a refused connection, an unreachable host, or a failure with no server in it.
     * @param willRetry whether `PlaybackRecovery` intends to try again. A retry in flight is not something
     *   to interrupt a driver over: the book may simply resume, and a message that appears and vanishes is
     *   worse than a moment's silence.
     */
    fun of(errorCode: Int, httpStatus: Int?, willRetry: Boolean): Report? {
        if (willRetry) return null
        // Credentials first, because they are the only failure a person can actually do something about,
        // and they arrive as an ordinary bad-status I/O error. 403 is a token the server accepts but will
        // not honour here; to a driver that is the same thing as an expired one, with the same remedy.
        if (httpStatus == HTTP_UNAUTHORIZED || httpStatus == HTTP_FORBIDDEN) {
            return Report(
                code = SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED,
                message = Message.CredentialsExpired,
                isCredentialFailure = true,
            )
        }
        return when (errorCode) {
            in IO_BAND -> report(Message.ServerUnreachable)
            in LOCAL_MEDIA_BANDS -> report(Message.FileNotPlayable)
            // `ERROR_CODE_UNSPECIFIED`, `REMOTE_ERROR`, `BEHIND_LIVE_WINDOW`, `FAILED_RUNTIME_CHECK` and
            // anything Media3 adds later. **Silence is the honest answer**: there is no sentence that is
            // true of an unknown failure, and the review above is what a confident wrong one looks like.
            // The event log still records it, which is where a diagnosis belongs.
            else -> null
        }
    }

    private fun report(message: Message) = Report(
        code = SessionError.ERROR_IO,
        message = message,
        isCredentialFailure = false,
    )

    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403

    /**
     * Media3's I/O band — a refused connection, a timeout, a bad status, a file the *server* does not have.
     *
     * `ERROR_CODE_IO_FILE_NOT_FOUND` sits here and is genuinely ambiguous: it covers both an HTTP 404 and a
     * local file that has gone. It stays in this band because the streamed case is the common one for this
     * app — a download that vanishes is rarer than a server that has moved a file — and because "can't
     * reach your server" is the less wrong of the two sentences when the file was being fetched.
     */
    private val IO_BAND = PlaybackException.ERROR_CODE_IO_UNSPECIFIED..IO_BAND_END

    /** Parsing, decoding and audio-track failures: the media or this device, never the network. */
    private val LOCAL_MEDIA_BANDS = PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..LOCAL_BAND_END

    private const val IO_BAND_END = 2999
    private const val LOCAL_BAND_END = 5999
}
