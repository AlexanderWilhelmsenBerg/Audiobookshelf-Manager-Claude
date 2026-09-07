package com.example.shelfplayer.playback

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
 * ### Why the classification is a pure function
 *
 * The only fact available at the failure is an HTTP status buried in a Media3 data-source exception chain,
 * and the mapping from that to *what a driver should read* is a product decision with three branches. Every
 * other decision on this branch that was left inline at a call site turned out to be untestable and, twice,
 * silently wrong. This one is asserted.
 *
 * Deliberately **not** a general error taxonomy: `AppError` already is one, and it lives on the other side
 * of a module boundary. This maps the narrow case a car can act on.
 */
internal object PlaybackFailureReport {

    /** What the car should be told, or `null` when the failure is not worth interrupting a driver over. */
    data class Report(val code: Int, val message: Message, val isCredentialFailure: Boolean)

    /** Which sentence to draw. The strings live in resources; this names one without resolving it. */
    enum class Message { CredentialsExpired, ServerUnreachable }

    /**
     * Classifies a failure that playback has **given up** on.
     *
     * @param httpStatus the response code from the data-source chain, or `null` if the failure never
     *   reached the server — a DNS failure, a refused connection, an unreachable host on the car's network.
     * @param willRetry whether `PlaybackRecovery` intends to try again. A retry in flight is not something
     *   to interrupt a driver over: the book may simply resume, and a message that appears and vanishes is
     *   worse than a moment's silence.
     */
    fun of(httpStatus: Int?, willRetry: Boolean): Report? {
        if (willRetry) return null
        return when (httpStatus) {
            // The credential cases, and the only ones a person can act on. 401 is an expired or rejected
            // token; 403 is a token the server accepts but will not honour for this item, which presents to
            // a driver as the same thing and has the same remedy — open the app.
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> Report(
                code = SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED,
                message = Message.CredentialsExpired,
                isCredentialFailure = true,
            )
            // Everything else, including `null`. A driver cannot distinguish a 500 from a timeout and can
            // do nothing about either, so both get the sentence that is true of both.
            else -> Report(
                code = SessionError.ERROR_IO,
                message = Message.ServerUnreachable,
                isCredentialFailure = false,
            )
        }
    }

    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
}
