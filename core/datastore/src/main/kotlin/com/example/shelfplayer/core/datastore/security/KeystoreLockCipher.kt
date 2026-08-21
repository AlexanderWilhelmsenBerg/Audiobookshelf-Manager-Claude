package com.example.shelfplayer.core.datastore.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject

/**
 * PRODUCT_SPEC AUTH-005 / AUTH-003 — AES-GCM under a non-extractable Keystore key, for lock records.
 *
 * The same shape as [KeystoreTokenCipher] and deliberately a separate class under a separate alias;
 * [LockCipher] documents the three bugs sharing one would have caused.
 *
 * ### What this buys, precisely
 *
 * Not secrecy of the passcode — that is already a one-way derivation (see [PasscodeKdf]). What it buys
 * is that reading the record requires *executing code on this device*, because the key cannot leave
 * the Keystore. Copying the file to another machine yields bytes nobody can use. That is the whole of
 * the claim, and ADR-0023 declines to make a larger one.
 *
 * ### `setUserAuthenticationRequired(false)`, on purpose
 *
 * Binding this key to device authentication would mean that checking the *passcode* first required
 * the device credential — which is circular, and worse, would hand the lock to exactly the person it
 * defends against: someone holding the already-unlocked phone.
 */
class KeystoreLockCipher @Inject constructor() : LockCipher {

    override fun wrap(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        // The provider's IV, never one this code chose. GCM is catastrophically broken by a repeated
        // IV under the same key, and a generator here would be one more thing to get wrong.
        return cipher.iv + cipher.doFinal(plaintext)
    }

    /*
     * The catch is narrow and the list is exhaustive on purpose (ADR-0003 forbids swallowing
     * exceptions; this is a deliberate boundary and returns a value the caller must handle).
     *
     * `GeneralSecurityException` covers an invalidated key and a failed authentication tag.
     * `IllegalArgumentException` covers a file shorter than the IV. Neither is recoverable and both
     * mean the same thing to the caller: this record is gone, so treat the profile as locked.
     */
    @Suppress("SwallowedException")
    override fun unwrap(encrypted: ByteArray): ByteArray? {
        if (encrypted.size <= IV_LENGTH) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_LENGTH_BITS, encrypted, 0, IV_LENGTH),
            )
            cipher.doFinal(encrypted, IV_LENGTH, encrypted.size - IV_LENGTH)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun clear() {
        keyStore().deleteEntry(KEY_ALIAS)
    }

    private fun key(): SecretKey =
        (keyStore().getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generateKey()

    private fun generateKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
        init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                // See the class KDoc: requiring authentication here would be circular.
                .setUserAuthenticationRequired(false)
                .build(),
        )
    }.generateKey()

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"

        /** Distinct from `shelfplayer.session.v1`, which `SessionTokenStore.clearAll()` destroys. */
        const val KEY_ALIAS = "shelfplayer.lock.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val TAG_LENGTH_BITS = 128
        const val IV_LENGTH = 12
    }
}
