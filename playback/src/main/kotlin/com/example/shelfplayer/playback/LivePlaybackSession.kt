package com.example.shelfplayer.playback

import androidx.media3.session.SessionToken
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC PLAY-001 / issue #75 — the direct token of the MediaSession that already exists in this
 * process.
 *
 * This is deliberately **not playback state**. A token says only that [PlaybackService] has built a live
 * session; whether that session contains a book, is playing, is paused, or is empty remains a fact owned by
 * Media3 and projected by [PlaybackController].
 *
 * The distinction is what keeps ordinary app launch lazy. [SessionConnector] can use this direct session
 * token to attach the phone UI to a service that is already alive, while the normal service-component token
 * remains reserved for explicit playback commands that are allowed to start the service.
 *
 * The service and activity share a process (the service declares no `android:process`), so no persisted
 * flag, exported intent extra, or second playback repository is needed.
 */
@Singleton
class LivePlaybackSession @Inject constructor() {
    private val lock = Any()
    private var token: SessionToken? = null

    /** Publishes the exact session the service just built. */
    fun publish(sessionToken: SessionToken) = synchronized(lock) {
        token = sessionToken
    }

    /** Returns a direct token only when this process currently owns a live session. */
    fun currentToken(): SessionToken? = synchronized(lock) { token }

    /**
     * Clears only the session being destroyed.
     *
     * Matching matters if service teardown ever overlaps replacement: a late destroy from an older session
     * must not erase the newer session's attach handle.
     */
    fun clear(sessionToken: SessionToken) = synchronized(lock) {
        if (token == sessionToken) token = null
    }
}
