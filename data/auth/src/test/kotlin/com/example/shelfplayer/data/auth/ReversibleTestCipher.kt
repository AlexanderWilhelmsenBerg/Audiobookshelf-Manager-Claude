package com.example.shelfplayer.data.auth

import com.example.shelfplayer.core.datastore.security.TokenCipher

/**
 * A stand-in for the Keystore cipher whose key the test can destroy on demand.
 *
 * PRODUCT_SPEC AUTH-003 requires that a lost key produces a reauthentication prompt rather than a
 * crash. A real `AndroidKeyStore` key is invalidated by a device-level event — a lock-screen change, a
 * restore onto new hardware — which Robolectric does not reproduce, so [loseKey] is the only way to
 * reach that code path in an automated test. See `TokenCipher` for what this consequently does *not*
 * cover: the Keystore configuration itself.
 *
 * The transform is deliberately trivial rather than cryptographic-looking. It has two jobs: be
 * distinguishable from plaintext so a "nothing reached disk in the clear" assertion means something, and
 * be able to fail.
 */
internal class ReversibleTestCipher : TokenCipher {
    private var generation = 1

    override fun encrypt(plaintext: String): ByteArray =
        byteArrayOf(generation.toByte()) + plaintext.toByteArray().map { (it + 1).toByte() }.toByteArray()

    override fun decrypt(encrypted: ByteArray): String? {
        if (encrypted.isEmpty() || encrypted.first().toInt() != generation) return null
        return encrypted.drop(1).map { (it - 1).toByte() }.toByteArray().decodeToString()
    }

    override fun clear() {
        generation++
    }

    /** What a lock-screen change or a restore onto a new device does to the key. */
    fun loseKey() {
        generation++
    }
}
