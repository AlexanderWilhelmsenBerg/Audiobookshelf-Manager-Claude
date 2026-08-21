package com.example.shelfplayer.core.datastore.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.testing.TestAppClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * AUTH-005 / ADR-0023 — the lock's storage, end to end, on real hardware.
 *
 * ### What this reaches that the JVM suite cannot
 *
 * `PasscodeKdfTest` covers the derivation because `SecretKeyFactory` has a JVM implementation. Everything
 * *around* it — the Keystore wrap, the staged write and rename, the rate limit that lives inside the
 * encrypted record, and the fail-closed read — has never run anywhere, because Robolectric ships no
 * `AndroidKeyStore` provider. This is the class where a real passcode is set, checked, mistyped ten times
 * and recovered from, against a real file on a real filesystem.
 *
 * The rate limit is the part most worth having here. It is stored *inside* the ciphertext specifically so
 * that force-stopping the app cannot reset it, and that claim is about the interaction of the record, the
 * cipher and the disk — the three things a unit test replaces with doubles.
 *
 * ### Time is controlled, disk is not
 *
 * [TestAppClock] drives the backoff, because a test that waited thirty real seconds to check a thirty
 * second delay would be a test nobody runs. Files, the Keystore and the coroutine dispatcher are all
 * real: `Dispatchers.IO` rather than a test dispatcher, since the store's own `withContext` is part of
 * what is under test.
 */
class ProfilePasscodeStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cipher = KeystoreLockCipher()
    private val clock = TestAppClock()
    private val store = ProfilePasscodeStore(context, cipher, clock, Dispatchers.IO)

    private val profile = "0123456789abcdef"
    private val other = "fedcba9876543210"

    /**
     * Both ends, and the directory as well as the key.
     *
     * A record left behind by a failed run would make the next run's `setPasscode` a *replace* rather
     * than a create, which is a different code path — so a stale file would silently move the test onto
     * a branch it does not mean to cover.
     */
    @Before
    fun clean() {
        locksDirectory().deleteRecursively()
        cipher.clear()
    }

    @After
    fun cleanUp() {
        locksDirectory().deleteRecursively()
        cipher.clear()
    }

    @Test
    fun a_passcode_is_written_and_verifies() = runTest {
        store.setPasscode(profile, "492817".toCharArray(), existing = null)

        assertTrue(store.hasPasscode(profile))
        assertIs<PasscodeVerdict.Correct>(store.verify(profile, "492817".toCharArray()))
    }

    /** The record is a file, and it is encrypted. Both halves matter and neither is visible from a unit test. */
    @Test
    fun the_record_is_a_file_and_holds_no_readable_passcode() = runTest {
        store.setPasscode(profile, "492817".toCharArray(), existing = null)

        val files = locksDirectory().listFiles().orEmpty()
        assertEquals(1, files.size, "one profile, one record")
        val bytes = files.single().readBytes()

        assertFalse(
            String(bytes, Charsets.ISO_8859_1).contains("492817"),
            "the passcode must not be recoverable from the file",
        )
        assertNotNull(cipher.unwrap(bytes), "and the file must be the wrapped record, not something else")
    }

    /**
     * **The file name is a hash, so a server-derived identifier never reaches an `ls`.**
     *
     * PRODUCT_SPEC 14.5. The caller hands in an already-opaque key; this asserts the store does not then
     * decorate it with anything, and that no other profile's key appears.
     */
    @Test
    fun the_file_name_carries_only_the_opaque_key() = runTest {
        store.setPasscode(profile, "492817".toCharArray(), existing = null)

        val name = locksDirectory().listFiles().orEmpty().single().name

        assertTrue(name.startsWith(profile), "the name is the key the caller supplied")
        assertFalse(name.contains(other))
    }

    @Test
    fun a_wrong_passcode_is_refused_and_the_right_one_still_works() = runTest {
        store.setPasscode(profile, "492817".toCharArray(), existing = null)

        assertIs<PasscodeVerdict.Wrong>(store.verify(profile, "492816".toCharArray()))
        assertIs<PasscodeVerdict.Correct>(store.verify(profile, "492817".toCharArray()))
    }

    /** Two profiles are two records; unlocking one says nothing about the other. */
    @Test
    fun profiles_do_not_share_a_record() = runTest {
        store.setPasscode(profile, "492817".toCharArray(), existing = null)
        store.setPasscode(other, "573920".toCharArray(), existing = null)

        assertIs<PasscodeVerdict.Correct>(store.verify(profile, "492817".toCharArray()))
        assertIs<PasscodeVerdict.Wrong>(store.verify(other, "492817".toCharArray()))
        assertTrue(store.hasPasscode(profile))
        assertTrue(store.hasPasscode(other))
    }

    @Test
    fun a_profile_with_no_record_has_no_passcode() = runTest {
        assertFalse(store.hasPasscode(profile))
        assertNull(store.preferences(profile))
        assertIs<PasscodeVerdict.Unreadable>(
            store.verify(profile, "492817".toCharArray()),
            "a missing record fails closed rather than admitting anybody",
        )
    }

    /**
     * **Four free attempts, then a delay — held inside the ciphertext.**
     *
     * The count survives because it is part of the record rather than of this object, which is the
     * property that makes force-stopping useless. A fresh store instance reads the same file.
     */
    @Test
    fun the_failure_count_survives_a_new_store_instance() = runTest {
        store.setPasscode(profile, "492817".toCharArray(), existing = null)
        repeat(4) { store.verify(profile, "000000".toCharArray()) }

        // A second instance is what a force-stop and relaunch produces.
        val relaunched = ProfilePasscodeStore(context, KeystoreLockCipher(), clock, Dispatchers.IO)

        val verdict = relaunched.verify(profile, "000000".toCharArray())
        assertIs<PasscodeVerdict.Wrong>(verdict)
        assertTrue(verdict.backoff > kotlin.time.Duration.ZERO, "the fifth failure is delayed, not free")
    }

    @Test
    fun the_first_four_failures_carry_no_delay() = runTest {
        store.setPasscode(profile, "492817".toCharArray(), existing = null)

        repeat(4) { attempt ->
            val verdict = store.verify(profile, "000000".toCharArray())
            assertIs<PasscodeVerdict.Wrong>(verdict)
            assertEquals(kotlin.time.Duration.ZERO, verdict.backoff, "attempt ${attempt + 1} must be free")
        }
    }

    /**
     * A backoff refuses even the correct passcode until it expires, and then honours it.
     *
     * The second half is the one worth asserting: a delay that never lifted would be indistinguishable
     * from exhaustion, and the user would have no way to tell that waiting was the answer.
     */
    @Test
    fun a_backoff_expires_against_the_clock() = runTest {
        store.setPasscode(profile, "492817".toCharArray(), existing = null)
        repeat(5) { store.verify(profile, "000000".toCharArray()) }

        assertIs<PasscodeVerdict.BackingOff>(
            store.verify(profile, "492817".toCharArray()),
            "the correct passcode is refused while the delay stands",
        )

        clock.advanceBy(31.seconds)

        assertIs<PasscodeVerdict.Correct>(store.verify(profile, "492817".toCharArray()))
    }

    /** A correct passcode clears the count, so a bad day does not accumulate towards a lockout. */
    @Test
    fun a_correct_passcode_resets_the_failure_count() = runTest {
        store.setPasscode(profile, "492817".toCharArray(), existing = null)
        repeat(3) { store.verify(profile, "000000".toCharArray()) }

        store.verify(profile, "492817".toCharArray())

        val verdict = store.verify(profile, "000000".toCharArray())
        assertIs<PasscodeVerdict.Wrong>(verdict)
        assertEquals(3, verdict.remainingBeforeBackoff, "the count went back to zero")
    }

    /**
     * Ten consecutive failures exhaust the record, and no passcode works afterwards.
     *
     * The clock is advanced past each backoff so the test reaches exhaustion rather than stopping at the
     * first delay — which is what a real attacker with time would do, and the state the curtain's
     * re-authentication field exists for.
     */
    @Test
    fun ten_failures_exhaust_the_record() = runTest {
        store.setPasscode(profile, "492817".toCharArray(), existing = null)

        repeat(10) {
            clock.advanceBy(20.minutes)
            store.verify(profile, "000000".toCharArray())
        }

        clock.advanceBy(20.minutes)
        assertIs<PasscodeVerdict.Exhausted>(
            store.verify(profile, "492817".toCharArray()),
            "past the limit the correct passcode must not work either",
        )
    }

    /**
     * **Removing the Keystore key makes the record unreadable rather than wrong.**
     *
     * This is a lock-screen change, or a restore onto new hardware, and it is the state ADR-0023 says the
     * curtain must describe honestly. It is also the exact interaction — cipher, record, disk — that no
     * JVM test could ever have produced.
     */
    @Test
    fun a_record_whose_key_is_gone_reads_as_unreadable() = runTest {
        store.setPasscode(profile, "492817".toCharArray(), existing = null)

        cipher.clear()

        assertIs<PasscodeVerdict.Unreadable>(store.verify(profile, "492817".toCharArray()))
        assertNull(store.preferences(profile), "and its preferences cannot be read either")
        assertTrue(store.hasPasscode(profile), "while the file still exists, so the profile stays locked")
    }

    /** A corrupted file is the same class of failure and must not throw. */
    @Test
    fun a_corrupted_record_reads_as_unreadable() = runTest {
        store.setPasscode(profile, "492817".toCharArray(), existing = null)
        locksDirectory().listFiles().orEmpty().single().writeBytes(ByteArray(64) { 0x5A })

        assertIs<PasscodeVerdict.Unreadable>(store.verify(profile, "492817".toCharArray()))
    }

    @Test
    fun removing_a_passcode_deletes_its_record() = runTest {
        store.setPasscode(profile, "492817".toCharArray(), existing = null)

        store.removePasscode(profile)

        assertFalse(store.hasPasscode(profile))
        assertTrue(locksDirectory().listFiles().orEmpty().isEmpty())
    }

    /**
     * Replacing a passcode keeps the preferences beside it.
     *
     * `setPasscode` takes the existing preferences precisely so that changing a passcode does not
     * silently turn biometrics off or reset the relock delay to its default.
     */
    @Test
    fun replacing_a_passcode_preserves_its_preferences() = runTest {
        store.setPasscode(profile, "492817".toCharArray(), existing = null)
        store.updatePreferences(profile, LockPreferences(biometricUnlock = true, relockDelay = 15.minutes))

        val existing = store.preferences(profile)
        store.setPasscode(profile, "573920".toCharArray(), existing = existing)

        val after = store.preferences(profile)
        assertNotNull(after)
        assertTrue(after.biometricUnlock, "biometrics must not be turned off by a passcode change")
        assertEquals(15.minutes, after.relockDelay)
        assertIs<PasscodeVerdict.Correct>(store.verify(profile, "573920".toCharArray()))
        assertIs<PasscodeVerdict.Wrong>(store.verify(profile, "492817".toCharArray()))
    }

    @Test
    fun preferences_round_trip_through_the_encrypted_record() = runTest {
        store.setPasscode(profile, "492817".toCharArray(), existing = null)

        store.updatePreferences(profile, LockPreferences(biometricUnlock = true, relockDelay = 60.seconds))

        val reread = ProfilePasscodeStore(context, KeystoreLockCipher(), clock, Dispatchers.IO)
            .preferences(profile)
        assertNotNull(reread)
        assertTrue(reread.biometricUnlock)
        assertEquals(60.seconds, reread.relockDelay)
    }

    /** The set the switcher and the settings screen read comes off the directory, not off a cache. */
    @Test
    fun refresh_reports_every_protected_profile() = runTest {
        store.setPasscode(profile, "492817".toCharArray(), existing = null)
        store.setPasscode(other, "573920".toCharArray(), existing = null)

        val fresh = ProfilePasscodeStore(context, KeystoreLockCipher(), clock, Dispatchers.IO)
        fresh.refresh()

        assertEquals(setOf(profile, other), fresh.observeProtectedProfiles().first())
    }

    private fun locksDirectory(): File = File(context.filesDir, ProfilePasscodeStore.DIRECTORY)
}
