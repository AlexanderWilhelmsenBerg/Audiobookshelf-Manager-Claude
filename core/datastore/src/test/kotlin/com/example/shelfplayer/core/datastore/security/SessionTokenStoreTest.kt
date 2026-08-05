package com.example.shelfplayer.core.datastore.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC AUTH-003 — the storage contract, and the handling of a key that is gone.
 *
 * Robolectric because the subject is real file I/O in an app's private storage. The cipher is a fake,
 * for the reason recorded on [TokenCipher]: a real Keystore key cannot be invalidated on demand, so the
 * *handling* of an undecryptable record is only reachable through a stand-in. What that leaves
 * unverified is the Keystore configuration itself, which needs hardware.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionTokenStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cipher = FakeTokenCipher()
    private val store = SessionTokenStore(context, cipher, UnconfinedTestDispatcher())

    /** PRODUCT_SPEC AUTH-003 — the plaintext must not be findable anywhere under the app's files. */
    @Test
    fun `a stored token is not readable as plaintext on disk`() = runTest {
        store.save(PROFILE, SessionTokenKind.Access, "access-token-value")

        val onDisk = filesUnder(context.filesDir).joinToString(separator = "\n") { it.readBytes().decodeToString() }
        assertFalse(onDisk.contains("access-token-value"), "the token reached disk unencrypted")
        assertEquals("access-token-value", store.load(PROFILE, SessionTokenKind.Access))
    }

    /** PRODUCT_SPEC AUTH-002 — two profiles' tokens are namespaced, so one sign-in cannot clobber another. */
    @Test
    fun `two profiles keep separate tokens`() = runTest {
        store.save(PROFILE, SessionTokenKind.Access, "first")
        store.save(OTHER_PROFILE, SessionTokenKind.Access, "second")

        assertEquals("first", store.load(PROFILE, SessionTokenKind.Access))
        assertEquals("second", store.load(OTHER_PROFILE, SessionTokenKind.Access))
    }

    /** PRODUCT_SPEC AUTH-004 — the refresh token has its own slot; storing one must not replace the other. */
    @Test
    fun `access and refresh tokens do not overwrite each other`() = runTest {
        store.save(PROFILE, SessionTokenKind.Access, "access")
        store.save(PROFILE, SessionTokenKind.Refresh, "refresh")

        assertEquals("access", store.load(PROFILE, SessionTokenKind.Access))
        assertEquals("refresh", store.load(PROFILE, SessionTokenKind.Refresh))
    }

    @Test
    fun `clearing a profile removes every token kind and leaves other profiles alone`() = runTest {
        store.save(PROFILE, SessionTokenKind.Access, "access")
        store.save(PROFILE, SessionTokenKind.Refresh, "refresh")
        store.save(OTHER_PROFILE, SessionTokenKind.Access, "other")

        store.clear(PROFILE)

        assertNull(store.load(PROFILE, SessionTokenKind.Access))
        assertNull(store.load(PROFILE, SessionTokenKind.Refresh))
        assertEquals("other", store.load(OTHER_PROFILE, SessionTokenKind.Access))
    }

    @Test
    fun `clearing one kind keeps the other`() = runTest {
        store.save(PROFILE, SessionTokenKind.Access, "access")
        store.save(PROFILE, SessionTokenKind.Refresh, "refresh")

        store.clear(PROFILE, SessionTokenKind.Refresh)

        assertEquals("access", store.load(PROFILE, SessionTokenKind.Access))
        assertNull(store.load(PROFILE, SessionTokenKind.Refresh))
    }

    /**
     * PRODUCT_SPEC AUTH-003 — "backup/restore must not create an undecryptable state that crashes the
     * app; it should require reauthentication".
     *
     * The unreadable record is also removed, so a permanently-failing decrypt does not run on every
     * future launch.
     */
    @Test
    fun `a token that can no longer be decrypted reads as absent and is discarded`() = runTest {
        store.save(PROFILE, SessionTokenKind.Access, "access")
        cipher.loseKey()

        assertNull(store.load(PROFILE, SessionTokenKind.Access))
        assertTrue(
            filesUnder(File(context.filesDir, "sessions")).isEmpty(),
            "the undecryptable record should have been deleted",
        )
    }

    @Test
    fun `loading a profile that never signed in is absent rather than an error`() = runTest {
        assertNull(store.load(PROFILE, SessionTokenKind.Access))
    }

    /** An interrupted write must not leave a staging file behind for the next reader to trip over. */
    @Test
    fun `a completed write leaves exactly one file per token`() = runTest {
        store.save(PROFILE, SessionTokenKind.Access, "access")
        store.save(PROFILE, SessionTokenKind.Access, "replacement")

        assertEquals(1, filesUnder(File(context.filesDir, "sessions")).size)
        assertEquals("replacement", store.load(PROFILE, SessionTokenKind.Access))
    }

    @Test
    fun `clearAll drops every profile and the key itself`() = runTest {
        store.save(PROFILE, SessionTokenKind.Access, "access")
        store.save(OTHER_PROFILE, SessionTokenKind.Access, "other")

        store.clearAll()

        assertTrue(filesUnder(File(context.filesDir, "sessions")).isEmpty())
        assertTrue(cipher.wasCleared)
    }

    private fun filesUnder(root: File): List<File> = root.walkTopDown().filter(File::isFile).toList()

    /**
     * A cipher that is reversible but not readable, and that can lose its key on command.
     *
     * Deliberately not "encryption": it is a byte transform whose only job is to be distinguishable
     * from plaintext and to be able to fail. Using something that looked cryptographic would invite a
     * reader to believe this test covers the real scheme, which it does not.
     */
    private class FakeTokenCipher : TokenCipher {
        private var generation = 1
        var wasCleared: Boolean = false
            private set

        override fun encrypt(plaintext: String): ByteArray =
            byteArrayOf(generation.toByte()) + plaintext.toByteArray().map { (it + 1).toByte() }.toByteArray()

        override fun decrypt(encrypted: ByteArray): String? {
            if (encrypted.isEmpty() || encrypted.first().toInt() != generation) return null
            return encrypted.drop(1).map { (it - 1).toByte() }.toByteArray().decodeToString()
        }

        override fun clear() {
            wasCleared = true
            generation++
        }

        /** What a lock-screen change or a restore onto a new device does to the key. */
        fun loseKey() {
            generation++
        }
    }

    private companion object {
        const val PROFILE = "prf_1"
        const val OTHER_PROFILE = "prf_2"
    }
}
