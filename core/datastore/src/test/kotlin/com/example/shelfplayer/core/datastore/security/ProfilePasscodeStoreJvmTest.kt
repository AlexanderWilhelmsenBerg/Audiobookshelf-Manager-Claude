package com.example.shelfplayer.core.datastore.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.testing.TestAppClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertIs

/** JVM portability coverage for the lock-record commit; Keystore behavior remains in androidTest. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfilePasscodeStoreJvmTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = ProfilePasscodeStore(
        context = context,
        cipher = ReversibleLockCipher,
        clock = TestAppClock(),
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    /** Replacing an existing lock record must work on every host filesystem, including Windows. */
    @Test
    fun `replacing a passcode replaces its existing record`() = runTest {
        store.setPasscode(PROFILE, "492817".toCharArray(), existing = null)
        store.setPasscode(PROFILE, "573920".toCharArray(), existing = null)

        assertIs<PasscodeVerdict.Correct>(store.verify(PROFILE, "573920".toCharArray()))
        assertIs<PasscodeVerdict.Wrong>(store.verify(PROFILE, "492817".toCharArray()))
    }

    private object ReversibleLockCipher : LockCipher {
        override fun wrap(plaintext: ByteArray): ByteArray = plaintext.map { (it + 1).toByte() }.toByteArray()

        override fun unwrap(encrypted: ByteArray): ByteArray = encrypted.map { (it - 1).toByte() }.toByteArray()

        override fun clear() = Unit
    }

    private companion object {
        const val PROFILE = "profile-key"
    }
}
