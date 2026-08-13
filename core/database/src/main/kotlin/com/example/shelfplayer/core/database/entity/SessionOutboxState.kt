package com.example.shelfplayer.core.database.entity

/**
 * PRODUCT_SPEC PLAY-005 — the three states a [PlaybackSessionEntity] passes through.
 *
 * Stored as strings rather than ordinals, for the reason PRODUCT_SPEC 13 gives about type converters: an
 * exported schema and a hand-written migration are read by a person, and `'Pending'` says what `2` does not.
 * A stored value the app does not recognize is read back as [PENDING] — the safe direction, because a
 * session the app cannot classify is one it has not proved it uploaded (PRODUCT_SPEC SYNC-001).
 */
object SessionOutboxState {
    /** Being listened to now. Still uploaded on a drain — see `SessionOutboxDao.pending`. */
    const val OPEN: String = "Open"

    /** Final, and the server has not accepted it. */
    const val PENDING: String = "Pending"

    /** Accepted. Kept for seven days, then compacted. */
    const val SYNCED: String = "Synced"
}
