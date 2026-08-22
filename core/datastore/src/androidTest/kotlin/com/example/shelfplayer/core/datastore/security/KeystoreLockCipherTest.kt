package com.example.shelfplayer.core.datastore.security

import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * AUTH-005 / ADR-0023 — the half of the profile lock that no JVM test can reach.
 *
 * ### Why this is the first instrumented test in the repository
 *
 * Robolectric ships no `AndroidKeyStore` provider, so every line of [KeystoreLockCipher] — and therefore
 * every byte of every lock record — has been unexercised since the feature landed. `docs/risks.md` R-39
 * records that, and R-07 records the absence of the tier this file starts. The choice of *this* class to
 * begin with is deliberate: it needs no Hilt graph, no Compose, no UI, no biometric hardware and no
 * network, so it is deterministic on any attached device and fails for one reason only.
 *
 * ### What it can and cannot prove
 *
 * It proves the wrap round-trips, that the ciphertext is not the plaintext, that a repeated IV does not
 * occur across calls, that a modified byte is rejected rather than silently decrypted, and that clearing
 * the key makes existing records unreadable — the last being the behaviour that turns a lock-screen change
 * into ADR-0023's "an unreadable record is a locked profile".
 *
 * It cannot prove the key is non-extractable. That is a property of the hardware-backed Keystore rather
 * than of this code, and asserting it would need a rooted device and a claim this project has no way to
 * substantiate. ADR-0023 states the guarantee narrowly for the same reason.
 *
 * ### No `@RunWith`
 *
 * `AndroidJUnitRunner` runs a plain JUnit 4 class directly. Annotating it with `AndroidJUnit4` would pull
 * `androidx.test.ext:junit-ktx` onto the classpath, which is the single `androidx.test` artifact this
 * project holds no verification checksum for — so adding it for an annotation nothing here needs would
 * mean regenerating that metadata to buy nothing.
 */
class KeystoreLockCipherTest {

    private val cipher = KeystoreLockCipher()

    /**
     * The alias is process-wide and survives the test run, so both ends are cleaned.
     *
     * Cleaning *before* as well as after matters: a previous run that crashed between generating a key
     * and deleting it would otherwise leave this run testing a key it did not create, and the failure
     * would look like a cipher bug rather than a dirty device.
     */
    @Before
    fun clearBefore() = cipher.clear()

    @After
    fun clearAfter() = cipher.clear()

    @Test
    fun wrapped_bytes_round_trip() {
        val plaintext = "a lock record".toByteArray()

        val unwrapped = cipher.unwrap(cipher.wrap(plaintext))

        assertNotNull(unwrapped, "the record this device wrapped must be readable on this device")
        assertContentEquals(plaintext, unwrapped)
    }

    /** An empty record is a legitimate input, and a length check that rejected it would be a bug. */
    @Test
    fun an_empty_record_round_trips() {
        val unwrapped = cipher.unwrap(cipher.wrap(ByteArray(0)))

        assertNotNull(unwrapped)
        assertContentEquals(ByteArray(0), unwrapped)
    }

    @Test
    fun the_ciphertext_is_not_the_plaintext() {
        val plaintext = "492817".toByteArray()

        val wrapped = cipher.wrap(plaintext)

        assertFalse(
            wrapped.toList().windowed(plaintext.size).any { it.toByteArray().contentEquals(plaintext) },
            "the plaintext must not appear anywhere in the wrapped bytes",
        )
    }

    /**
     * **The property GCM is catastrophically broken without.**
     *
     * Two encryptions of the same plaintext under one key must not produce the same bytes. The IV comes
     * from the provider rather than from this code — `KeystoreLockCipher` deliberately never chooses one
     * — and this asserts that the provider is in fact varying it, which is the assumption that decision
     * rests on.
     */
    @Test
    fun two_wraps_of_one_plaintext_differ() {
        val plaintext = "a lock record".toByteArray()

        val first = cipher.wrap(plaintext)
        val second = cipher.wrap(plaintext)

        assertFalse(first.contentEquals(second), "a repeated IV under one key breaks GCM outright")
        assertFalse(
            first.take(IV_LENGTH).toByteArray().contentEquals(second.take(IV_LENGTH).toByteArray()),
            "the provider must not reuse an IV",
        )
    }

    /**
     * A tampered record fails the authentication tag and comes back `null`, not as garbage.
     *
     * This is the difference between GCM and an unauthenticated mode, and it is what lets
     * `ProfilePasscodeStore` treat "cannot be read" as a distinct state from "wrong passcode" instead of
     * comparing a verifier against noise.
     */
    @Test
    fun a_modified_byte_is_rejected() {
        val wrapped = cipher.wrap("a lock record".toByteArray())
        val tampered = wrapped.copyOf().also { it[it.lastIndex] = (it[it.lastIndex] + 1).toByte() }

        assertNull(cipher.unwrap(tampered), "a failed tag must not decrypt to anything")
    }

    /** Flipping a byte of the IV is the same class of failure and must behave the same way. */
    @Test
    fun a_modified_iv_is_rejected() {
        val wrapped = cipher.wrap("a lock record".toByteArray())
        val tampered = wrapped.copyOf().also { it[0] = (it[0] + 1).toByte() }

        assertNull(cipher.unwrap(tampered))
    }

    /** Shorter than the IV is not a record. The guard exists so the slice below it cannot throw. */
    @Test
    fun a_truncated_record_is_rejected() {
        assertNull(cipher.unwrap(ByteArray(0)))
        assertNull(cipher.unwrap(ByteArray(IV_LENGTH)))
        assertNull(cipher.unwrap(ByteArray(IV_LENGTH - 1) { 7 }))
    }

    /**
     * **The behaviour behind "an unreadable record is a locked profile".**
     *
     * Deleting the key is what a lock-screen change or a restore onto new hardware does to it. ADR-0023
     * says the consequence is a curtain that says the record cannot be read rather than one that says
     * "wrong passcode", and `ProfilePasscodeStore` depends on `unwrap` returning `null` for it. Nothing
     * on the JVM could ever have checked that this is what actually happens.
     */
    @Test
    fun clearing_the_key_makes_existing_records_unreadable() {
        val wrapped = cipher.wrap("a lock record".toByteArray())

        cipher.clear()

        assertNull(cipher.unwrap(wrapped), "a record wrapped under a deleted key must not be readable")
    }

    /**
     * And the alias regenerates rather than the cipher staying broken.
     *
     * Without this a user whose key was invalidated could clear the passcode and still not set a new one,
     * which would turn a recoverable state into a permanent one — the failure mode ADR-0023's recovery
     * route exists to prevent.
     */
    @Test
    fun a_new_key_is_generated_after_a_clear() {
        cipher.wrap("first".toByteArray())
        cipher.clear()

        val unwrapped = cipher.unwrap(cipher.wrap("second".toByteArray()))

        assertNotNull(unwrapped, "the cipher must be usable again after its key is deleted")
        assertContentEquals("second".toByteArray(), unwrapped)
    }

    /** Clearing an alias that does not exist is what a first run does, and must not throw. */
    @Test
    fun clearing_an_absent_key_is_harmless() {
        cipher.clear()
        cipher.clear()
    }

    private companion object {
        /** Mirrors `KeystoreLockCipher.IV_LENGTH`, which is private to it. */
        const val IV_LENGTH = 12
    }
}
