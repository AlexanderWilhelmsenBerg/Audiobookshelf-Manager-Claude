package com.example.shelfplayer.core.datastore.security

/**
 * PRODUCT_SPEC AUTH-003 — the seam between "a token is stored" and "a token is encrypted".
 *
 * [KeystoreTokenCipher] is the only production implementation and the only one that may be bound. The
 * interface exists so that [SessionTokenStore]'s behaviour is testable, and that is not a testing
 * convenience — it is the only way to cover the requirement that matters most here.
 *
 * PRODUCT_SPEC AUTH-003 requires that a restore onto a new device, or a lock-screen change, "must not
 * create an undecryptable state that crashes the app; it should require reauthentication". A real
 * Android Keystore key cannot be invalidated on demand: `KeyPermanentlyInvalidatedException` is raised
 * by hardware-backed key material reacting to a device-level event, and Robolectric does not reproduce
 * it. Without this seam, the correct *handling* of that event could only be verified by hand on a
 * physical device, which means in practice it would not be verified at all.
 *
 * A test implementation therefore stands in for the cipher and returns `null` where the real one would
 * after key loss. What that leaves unverified is stated plainly: the Keystore configuration itself —
 * GCM, the non-extractable key, `setUserAuthenticationRequired(false)` — is exercised only on real
 * hardware.
 */
interface TokenCipher {
    /** Encrypts [plaintext]. The returned bytes are opaque and include whatever the scheme needs to reverse it. */
    fun encrypt(plaintext: String): ByteArray

    /**
     * Reverses [encrypt], or returns `null` when the value cannot be decrypted.
     *
     * `null` rather than an exception, because every reason this fails has the same correct response:
     * treat the stored session as gone and require signing in again (`AUTH-004`). An implementation
     * must not return partially-recovered or unauthenticated plaintext, because the caller sends the
     * result to a server as a credential.
     */
    fun decrypt(encrypted: ByteArray): String?

    /** Discards the key, making every previously encrypted value permanently unreadable. */
    fun clear()
}
