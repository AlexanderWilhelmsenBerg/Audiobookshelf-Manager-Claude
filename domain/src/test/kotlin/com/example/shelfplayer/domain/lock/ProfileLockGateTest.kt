package com.example.shelfplayer.domain.lock

import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.testing.TestAppClock
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC AUTH-005 — the relock timing, which is the subtlest part of the lock.
 *
 * The interesting property is not "does a ticket expire" but **who observes the expiry**. The lock is
 * consulted from two places with completely different lifecycles: the curtain, which recomposes when a flow
 * emits, and `OutputDeviceWatcher`, which is called from the media service by an `AudioManager` callback
 * with no Activity attached and no reason to have received any lifecycle event.
 *
 * So the ticket is evaluated against the clock at read time rather than expired by a foreground event. If it
 * were the other way round, a headset connecting ninety seconds after a phone went into a pocket would ask a
 * gate that no callback had reached yet, and be told the profile was unlocked. That case is
 * [a ticket expires for a caller that received no lifecycle event] and it is the reason this file exists.
 */
class ProfileLockGateTest {

    private val clock = TestAppClock()
    private val gate = ProfileLockGate(clock)
    private val alice = ProfileId("prf_alice")
    private val bob = ProfileId("prf_bob")

    @Test
    fun `a profile with no ticket is locked`() {
        assertFalse(gate.isUnlocked(alice))
    }

    @Test
    fun `a granted ticket unlocks that profile and no other`() {
        gate.grant(alice, relockDelay = Duration.ZERO)

        assertTrue(gate.isUnlocked(alice))
        assertFalse(gate.isUnlocked(bob), "one profile's unlock must never unlock another")
    }

    @Test
    fun `revoking locks again`() {
        gate.grant(alice, relockDelay = 15.minutes)
        gate.revoke(alice)

        assertFalse(gate.isUnlocked(alice))
    }

    /**
     * The default, and the reason it is the default: the threat is somebody picking up an unlocked phone, and
     * a grace period is precisely the window that person is in.
     */
    @Test
    fun `a zero delay relocks the moment the app is backgrounded`() {
        gate.grant(alice, relockDelay = Duration.ZERO)
        gate.onBackgrounded()

        assertFalse(gate.isUnlocked(alice))
    }

    @Test
    fun `a ticket survives backgrounding for as long as its delay allows`() {
        gate.grant(alice, relockDelay = 1.minutes)
        gate.onBackgrounded()

        clock.advanceBy(30.seconds)
        assertTrue(gate.isUnlocked(alice), "inside the delay, the profile stays unlocked")
    }

    /**
     * **The case this class is shaped around.**
     *
     * Nothing is called between backgrounding and the read — no `onForegrounded`, no lifecycle event, no
     * timer. A caller in the media service simply asks, and gets the right answer because the answer is
     * computed from the clock rather than from an event it never received.
     */
    @Test
    fun `a ticket expires for a caller that received no lifecycle event`() {
        gate.grant(alice, relockDelay = 1.minutes)
        gate.onBackgrounded()

        clock.advanceBy(90.seconds)

        assertFalse(gate.isUnlocked(alice))
    }

    /**
     * Returning to the app inside the delay clears the stamp, so the next departure starts a fresh window
     * rather than continuing the old one.
     */
    @Test
    fun `returning inside the delay refreshes the window`() {
        gate.grant(alice, relockDelay = 1.minutes)
        gate.onBackgrounded()
        clock.advanceBy(30.seconds)
        gate.onForegrounded()

        gate.onBackgrounded()
        clock.advanceBy(45.seconds)

        assertTrue(gate.isUnlocked(alice), "the second window is its own minute, not the remainder of the first")
    }

    /**
     * Returning *after* the delay must not resurrect the ticket.
     *
     * This is the mirror of the case above and the one an implementation gets wrong by clearing the stamp
     * unconditionally on foreground: the profile would be locked while the app was away and unlocked the
     * instant it came back, which is exactly backwards.
     */
    @Test
    fun `returning after the delay does not resurrect the ticket`() {
        gate.grant(alice, relockDelay = 1.minutes)
        gate.onBackgrounded()
        clock.advanceBy(90.seconds)

        gate.onForegrounded()

        assertFalse(gate.isUnlocked(alice))
    }

    /** Two profiles can hold different delays, so backgrounding cannot be one global deadline. */
    @Test
    fun `each profile relocks on its own delay`() {
        gate.grant(alice, relockDelay = 15.minutes)
        gate.grant(bob, relockDelay = Duration.ZERO)
        gate.onBackgrounded()

        clock.advanceBy(1.minutes)

        assertTrue(gate.isUnlocked(alice))
        assertFalse(gate.isUnlocked(bob))
    }

    /**
     * A second grant replaces the first, stamp and all.
     *
     * Unlocking again after a relock has to produce a live ticket rather than one that inherits the old
     * departure time — otherwise entering the correct passcode would leave the profile still locked.
     */
    @Test
    fun `granting again clears a stale stamp`() {
        gate.grant(alice, relockDelay = 1.minutes)
        gate.onBackgrounded()
        clock.advanceBy(90.seconds)
        assertFalse(gate.isUnlocked(alice))

        gate.grant(alice, relockDelay = 1.minutes)

        assertTrue(gate.isUnlocked(alice))
    }
}
