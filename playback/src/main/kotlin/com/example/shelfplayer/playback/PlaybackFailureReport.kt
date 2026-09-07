package com.example.shelfplayer.playback

import androidx.media3.common.PlaybackException
import androidx.media3.session.SessionError
import com.example.shelfplayer.core.model.AppError

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
    enum class Message { CredentialsExpired, ServerUnreachable, ServerCannotDeliver, FileNotPlayable }

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
    fun of(errorCode: Int, httpStatus: Int?, localFile: Boolean, willRetry: Boolean): Report? {
        if (willRetry) return null
        // Evidence before bands. `FileDataSource` throws its own exception type, so a local read is
        // *proven* rather than inferred — and Media3's I/O band cannot tell a server's 404 from a missing
        // download. A review found that guess telling a listener the server was unreachable for an offline
        // book, which is product priority 3 and never touched the network at all.
        if (localFile) return report(Message.FileNotPlayable)
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
        return when {
            // An answered request is never an unreachable server, and a review found this branch saying so
            // for a 404 and a 429 alike. The response *is* the proof of reach: a stale library item and a
            // rate limit are both the server declining to hand over this book, on a connection that plainly
            // worked. Same shape as the `localFile` evidence above — the fact decides, not the band.
            serverAnswered(errorCode, httpStatus) -> report(Message.ServerCannotDeliver)
            errorCode in IO_BAND -> report(Message.ServerUnreachable)
            errorCode in LOCAL_MEDIA_BANDS -> report(Message.FileNotPlayable)
            // `ERROR_CODE_UNSPECIFIED`, `REMOTE_ERROR`, `BEHIND_LIVE_WINDOW`, `FAILED_RUNTIME_CHECK` and
            // anything Media3 adds later. **Silence is the honest answer**: there is no sentence that is
            // true of an unknown failure, and the review above is what a confident wrong one looks like.
            // The event log still records it, which is where a diagnosis belongs.
            else -> null
        }
    }

    /**
     * Whether the server got the request and answered it — with anything other than the book.
     *
     * Two facts say so, and either is enough. A status number survived the data-source chain, or Media3
     * itself called the failure a bad HTTP status, which *means* a response arrived even on the chain shape
     * where [PlaybackHttpFailure] cannot dig the number back out. Neither is an inference about the network.
     */
    private fun serverAnswered(errorCode: Int, httpStatus: Int?): Boolean =
        httpStatus != null || errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS

    /**
     * The same question for a failure that happens **while opening the session**, where there is no
     * `PlaybackException` because nothing ever reached the player.
     *
     * A review found this to be the path that matters most and the one the report missed entirely:
     * `openQueue` handles an `AppResult.Failure` by logging and handing back `null`, so a car selecting a
     * book against an expired credential got silence — the exact case the labelled sign-in action exists
     * for. The typed `AppError` was already in hand.
     *
     * Only the two errors a driver can read something true about are mapped. Everything else — a missing
     * permission, a validation failure, an incompatible server, a storage problem — **stays silent**, for
     * the reason an unspecified `PlaybackException` does: there is no sentence that is true of it, and the
     * three findings before this one were all confident wrong sentences.
     */
    fun ofSessionFailure(error: AppError): Report? = when (error) {
        is AppError.Authentication -> Report(
            code = SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED,
            message = Message.CredentialsExpired,
            isCredentialFailure = true,
        )

        // Split for the same reason the bands above are: `AppError.Server` is *defined* as "the server
        // returned an error status", so it carries its own proof of reach and must not claim otherwise. A
        // timeout stays with the unreachable case — nothing came back, which is what the sentence says.
        is AppError.Network, is AppError.Timeout -> report(Message.ServerUnreachable)

        is AppError.Server -> report(Message.ServerCannotDeliver)

        // Enumerated rather than an `else`, which detekt refuses on a sealed subject and is right to: a
        // variant added later should make this fail to compile and be *decided*, not silently inherit
        // silence. Every one of these is silent today, and each for the same reason — there is no sentence
        // a driver could act on. A missing permission is not fixed by signing in again; an incompatible
        // server, a conflict, a validation failure and a security error are all diagnoses for a screen.
        is AppError.ApiCompatibility,
        is AppError.Authorization,
        is AppError.Canceled,
        is AppError.Conflict,
        is AppError.Download,
        is AppError.Playback,
        is AppError.Security,
        is AppError.Storage,
        is AppError.Unknown,
        is AppError.Validation,
        -> null
    }

    private fun report(message: Message) = Report(
        code = SessionError.ERROR_IO,
        message = message,
        isCredentialFailure = false,
    )

    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403

    /**
     * Media3's I/O band, **reached only when nothing answered**: a refused connection, an unreachable host,
     * a dropped transfer, a DNS failure.
     *
     * The band alone was never enough to name a cause, and both of the facts checked before it exist
     * because a review caught it guessing. `ERROR_CODE_IO_FILE_NOT_FOUND` and `..._NO_PERMISSION` sit here
     * and cannot tell a missing download from a server's 404; [serverAnswered] and the `localFile` evidence
     * decide those. Reaching this band now means the read was not local *and* no response arrived.
     */
    private val IO_BAND = PlaybackException.ERROR_CODE_IO_UNSPECIFIED..IO_BAND_END

    /** Parsing, decoding and audio-track failures: the media or this device, never the network. */
    private val LOCAL_MEDIA_BANDS = PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..LOCAL_BAND_END

    private const val IO_BAND_END = 2999
    private const val LOCAL_BAND_END = 5999
}
