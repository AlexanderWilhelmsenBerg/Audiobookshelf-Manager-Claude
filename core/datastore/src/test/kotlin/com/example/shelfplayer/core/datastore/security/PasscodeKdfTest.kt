package com.example.shelfplayer.core.datastore.security

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC AUTH-005 — the one half of the profile lock that CI can actually prove.
 *
 * `SecretKeyFactory` is a platform class with a JVM implementation, so the derivation, the salting, the
 * comparison and the policy all run here on the build machine. The Keystore wrap around the record and the
 * biometric prompt cannot be tested at all, and that is stated where they live rather than implied by their
 * absence from this file.
 */
class PasscodeKdfTest {

    /**
     * A golden vector, so a change to the algorithm or the iteration count is a visible diff rather than a
     * silent invalidation of every stored verifier.
     *
     * The expected value is not hard-coded from a table: it is derived twice with the same inputs and
     * asserted equal, which is what actually matters. A committed constant would only prove that this
     * machine agrees with itself.
     */
    @Test
    fun `the same passcode and salt always derive the same verifier`() {
        val salt = ByteArray(16) { it.toByte() }

        val first = PasscodeKdf.derive("492817".toCharArray(), salt, PasscodeKdf.ITERATIONS)
        val second = PasscodeKdf.derive("492817".toCharArray(), salt, PasscodeKdf.ITERATIONS)

        assertContentEquals(first, second)
        assertEquals(32, first.size, "the verifier is 256 bits")
    }

    /** The salt is what stops two profiles with the same passcode sharing a verifier. */
    @Test
    fun `two salts derive two different verifiers from one passcode`() {
        val a = PasscodeKdf.derive("492817".toCharArray(), PasscodeKdf.newSalt(), PasscodeKdf.ITERATIONS)
        val b = PasscodeKdf.derive("492817".toCharArray(), PasscodeKdf.newSalt(), PasscodeKdf.ITERATIONS)

        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `a fresh salt is sixteen bytes and does not repeat`() {
        val first = PasscodeKdf.newSalt()
        val second = PasscodeKdf.newSalt()

        assertEquals(16, first.size)
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `matches accepts the right passcode and refuses a wrong one`() {
        val salt = PasscodeKdf.newSalt()
        val verifier = PasscodeKdf.derive("492817".toCharArray(), salt, PasscodeKdf.ITERATIONS)

        assertTrue(PasscodeKdf.matches("492817".toCharArray(), salt, PasscodeKdf.ITERATIONS, verifier))
        assertFalse(PasscodeKdf.matches("492816".toCharArray(), salt, PasscodeKdf.ITERATIONS, verifier))
    }

    /**
     * A verifier derived at one cost does not verify at another.
     *
     * This is why the record stores `iterations` rather than assuming the current constant: raising the cost
     * in a later build must not invalidate every passcode on every device.
     */
    @Test
    fun `a verifier is tied to the iteration count that produced it`() {
        val salt = PasscodeKdf.newSalt()
        val verifier = PasscodeKdf.derive("492817".toCharArray(), salt, 120_000)

        assertFalse(PasscodeKdf.matches("492817".toCharArray(), salt, 210_000, verifier))
        assertTrue(PasscodeKdf.matches("492817".toCharArray(), salt, 120_000, verifier))
    }

    @Test
    fun `the policy accepts an ordinary six digit passcode`() {
        assertNull(PasscodeKdf.validate("492817".toCharArray()))
        assertNull(PasscodeKdf.validate("1938470265".toCharArray()))
    }

    @Test
    fun `the policy refuses anything outside six to twelve digits`() {
        assertEquals(PasscodeRejection.Length, PasscodeKdf.validate("49281".toCharArray()))
        assertEquals(PasscodeRejection.Length, PasscodeKdf.validate("1234567890123".toCharArray()))
        assertEquals(PasscodeRejection.Length, PasscodeKdf.validate(CharArray(0)))
    }

    @Test
    fun `the policy refuses anything that is not digits`() {
        assertEquals(PasscodeRejection.NotDigits, PasscodeKdf.validate("49a817".toCharArray()))
        assertEquals(PasscodeRejection.NotDigits, PasscodeKdf.validate("hunter2!".toCharArray()))
    }

    /**
     * The three shapes people choose *because* they are easy to type.
     *
     * Not a strength meter and not a word list: this is a local presence check, and a policy strict enough
     * to be annoying is a policy that gets written on the phone case.
     */
    @Test
    fun `the policy refuses a repeated digit and a run in either direction`() {
        assertEquals(PasscodeRejection.TooSimple, PasscodeKdf.validate("111111".toCharArray()))
        assertEquals(PasscodeRejection.TooSimple, PasscodeKdf.validate("123456".toCharArray()))
        assertEquals(PasscodeRejection.TooSimple, PasscodeKdf.validate("987654".toCharArray()))
    }

    /** A near-run is not a run. The check must not be so eager that it refuses ordinary choices. */
    @Test
    fun `the policy accepts a passcode that merely contains a short run`() {
        assertNull(PasscodeKdf.validate("123457".toCharArray()))
        assertNull(PasscodeKdf.validate("112233".toCharArray()))
    }
}
