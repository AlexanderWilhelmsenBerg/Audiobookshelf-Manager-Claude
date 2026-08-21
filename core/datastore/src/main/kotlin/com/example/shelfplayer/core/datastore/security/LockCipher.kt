package com.example.shelfplayer.core.datastore.security

/**
 * PRODUCT_SPEC AUTH-005 — the seam between "a lock record is stored" and "it is encrypted at rest".
 *
 * ### Why this is not [TokenCipher]
 *
 * Three concrete bugs, each of which would have been silent:
 *
 *  1. `SessionTokenStore.clear(profileId)` iterates `SessionTokenKind.entries` and deletes a file per
 *     kind. Storing the lock record as a fourth kind would delete somebody's passcode when they
 *     signed out — and AUTH-003 is explicit that the lock is *not* server-authentication state.
 *  2. `SessionTokenStore.clearAll()` calls `cipher.clear()`, which destroys the Keystore key. A
 *     verifier encrypted under that key would become unreadable at the same moment, and a record that
 *     cannot be read fails closed, so signing out of everything would leave the app permanently locked.
 *  3. `SessionTokenStore.storedCredentialCount()` counts distinct file stems in its directory to
 *     answer "how many accounts have a credential on disk". A lock file there would inflate a
 *     diagnostic the owner reads.
 *
 * So: a different key alias, a different directory, and a different interface. The cost is one more
 * small class; the alternative is three failure modes that only appear on a real device.
 *
 * ### Bytes in, bytes out
 *
 * [TokenCipher] deals in `String` because a token is text. A serialized protobuf is not, and routing
 * it through a `String` would mean a hex or Base64 round-trip whose only purpose is to satisfy a
 * signature. This interface takes and returns `ByteArray`.
 */
interface LockCipher {
    /** Encrypts [plaintext]. The result carries whatever the scheme needs to reverse itself. */
    fun wrap(plaintext: ByteArray): ByteArray

    /**
     * Reverses [wrap], or returns `null` when the value cannot be recovered.
     *
     * `null` rather than an exception for the same reason [TokenCipher.decrypt] does it: every cause —
     * a key invalidated by a lock-screen change, a truncated file, a restore onto new hardware — has
     * the same correct response. Here that response is **fail closed**: the caller treats an
     * unreadable record as a profile that is locked, never as one that has no passcode.
     */
    fun unwrap(encrypted: ByteArray): ByteArray?

    /** Discards the key, making every previously wrapped record permanently unreadable. */
    fun clear()
}
