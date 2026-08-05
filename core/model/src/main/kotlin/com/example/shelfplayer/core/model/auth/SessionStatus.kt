package com.example.shelfplayer.core.model.auth

/**
 * PRODUCT_SPEC AUTH-004 — what a profile's stored session is currently worth.
 *
 * There are deliberately only two answers, and neither of them is "signed out". A profile whose token
 * expired keeps its downloads, its local progress and its preferences; the only thing it has lost is
 * the ability to make new requests. Collapsing that into a sign-out is what loses a user's library,
 * and PRODUCT_SPEC AUTH-004 requires reauthentication to restore the account rather than rebuild it.
 */
enum class SessionStatus {
    /** A usable credential is loaded for this profile. */
    Active,

    /**
     * The credential is gone or rejected and cannot be renewed without the user.
     *
     * Reached when a session was never renewable (`AuthSession.isRenewable` was false), when the
     * refresh itself was refused, or when the stored token could no longer be decrypted. All three
     * lead to the same place, so they are one state rather than three (PRODUCT_SPEC 14.4: the user
     * needs an action, not a cause).
     */
    ReauthenticationRequired,
}
