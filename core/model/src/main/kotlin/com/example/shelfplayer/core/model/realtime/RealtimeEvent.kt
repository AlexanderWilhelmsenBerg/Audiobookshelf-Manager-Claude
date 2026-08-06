package com.example.shelfplayer.core.model.realtime

import com.example.shelfplayer.core.model.auth.AccountState

/**
 * PRODUCT_SPEC SYNC-002 — something the server told us without being asked.
 *
 * One event so far, because one is all a real server has been observed to send in response to
 * anything this app does. `contracts/socket-event-after-progress.json` recorded it; item changes,
 * library scans and session events have never been seen and are therefore not modelled
 * (PRODUCT_SPEC 22.4).
 */
sealed interface RealtimeEvent {
    /**
     * The account changed, and the frame carries **all** of it.
     *
     * Not a progress delta — the capture showed a REST progress write coming back as the entire user
     * object, permissions and account state included. That is why one event serves three purposes:
     * a position played elsewhere, a grant changed on the server, and an account disabled.
     */
    data class AccountChanged(val account: AccountState) : RealtimeEvent
}

/**
 * PRODUCT_SPEC SYNC-002 — whether the realtime connection is up, and honestly so.
 *
 * [Connecting] is separate from [Disconnected] because a client that reconnects with backoff spends
 * real time in it, and a UI that showed "disconnected" throughout would be reporting a failure during
 * an ordinary recovery.
 */
enum class RealtimeStatus {
    /** Never started, or deliberately stopped — no profile, or the app is in the background. */
    Idle,

    Connecting,

    /** Connected *and* authenticated. A socket that is open but unauthenticated receives nothing. */
    Connected,

    /** The connection dropped and a retry is scheduled. REST remains the source of truth throughout. */
    Disconnected,
}
