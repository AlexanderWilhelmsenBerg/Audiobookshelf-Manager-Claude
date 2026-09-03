package com.example.shelfplayer.core.model.playback

/**
 * PRODUCT_SPEC PLAY-004 / SYNC-002 — whether a position **reached the server** or only reached the outbox.
 *
 * ### Why a successful sync is not the same as an accepted position
 *
 * `SessionSyncRepository` writes its row before it sends and returns success as soon as the position is
 * durably stored. That is the right answer for the outbox — nothing is lost, and the drain will carry it —
 * and it was the *only* answer available, which made a `Success` mean two different things: "Audiobookshelf
 * has this" and "this device has this and will send it eventually".
 *
 * Those two cannot be conflated by the freshness check. It decides from an *acknowledged pause*: a position
 * the server confirmed. If a queued-only sync counted, a book started offline — or one whose `/play` call
 * failed, so the session has no server id at all — would produce a baseline the server has never seen. The
 * next Play would compare against it, find the server holding something older, conclude that another device
 * had moved the book, and **rewind the listener onto a stale server position**. That is the same class of
 * data loss this whole path exists to prevent (product priority 2). So the distinction is carried in the
 * type rather than inferred by the caller.
 */
enum class SyncOutcome {
    /**
     * Audiobookshelf holds this position now.
     *
     * A `200` from the live sync route, or the redundancy skip — which fires only against a row already in
     * `SYNCED` whose position has not moved, and therefore *asserts* the server is still holding it
     * (`docs/risks.md` R-91).
     */
    Accepted,

    /**
     * Durably stored on this device and not sent.
     *
     * A session with no server id — one that never reached `/play`, so the offline batch route is the only
     * way its listening can ever be uploaded (PLAY-005). Nothing is lost and nothing is confirmed.
     */
    Queued,
}
