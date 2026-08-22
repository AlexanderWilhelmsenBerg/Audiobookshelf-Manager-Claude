package com.example.shelfplayer.lock

import android.app.Activity
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.testing.TestAppClock
import com.example.shelfplayer.domain.lock.ProfileLockGate
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * AUTH-005 — the caller that `ProfileLockGateTest` could not notice was missing.
 *
 * ### Why this file exists
 *
 * `ProfileLockGateTest` covers the relock arithmetic in ten tests and every one of them passed while the
 * feature was **completely inert on a device**: nothing in production ever called `onBackgrounded`, so
 * `backgroundedAt` was never stamped, `isUnlocked` returned `true` for the life of the process, and all
 * three relock options behaved identically — the lock engaged on a cold start and never again.
 *
 * A unit test of a class cannot observe that nothing constructs it. That is the same shape as R-37, where a
 * fake that ignored an argument hid a defect that emptied libraries, and it is recorded as R-43. The
 * general lesson is that testing a component's *arithmetic* and testing that the arithmetic is *reached*
 * are two different jobs, and only the second one would have caught this.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProcessLockWatcherTest {

    private val clock = TestAppClock()
    private val gate = ProfileLockGate(clock)
    private val watcher = ProcessLockWatcher(gate)
    private val profile = ProfileId("prf_test")

    /** The case the missing caller broke: leaving the app has to stamp the ticket. */
    @Test
    fun `stopping the last activity backgrounds the gate`() {
        gate.grant(profile, relockDelay = Duration.ZERO)
        val activity = Activity()

        watcher.onActivityStarted(activity)
        watcher.onActivityStopped(activity)

        assertFalse(gate.isUnlocked(profile), "leaving the app with a zero delay must relock")
    }

    /**
     * **A rotation must not lock the app.**
     *
     * The old activity stops before the new one starts, so a naive counter passes through zero and reports
     * the app as backgrounded. With the default relock delay that would demand a passcode every time the
     * phone was turned sideways.
     */
    @Test
    fun `a configuration change does not background the gate`() {
        gate.grant(profile, relockDelay = Duration.ZERO)
        val rotating = RecreatingActivity()

        watcher.onActivityStarted(rotating)
        watcher.onActivityStopped(rotating)

        assertTrue(gate.isUnlocked(profile), "a rotation is not leaving the app")
    }

    /** A second screen opening and closing is not leaving the app either. */
    @Test
    fun `the gate is only backgrounded when the last activity stops`() {
        gate.grant(profile, relockDelay = Duration.ZERO)
        val first = Activity()
        val second = Activity()

        watcher.onActivityStarted(first)
        watcher.onActivityStarted(second)
        watcher.onActivityStopped(first)

        assertTrue(gate.isUnlocked(profile), "one of two screens closing is not backgrounding")

        watcher.onActivityStopped(second)

        assertFalse(gate.isUnlocked(profile))
    }

    /** Returning inside the delay keeps the unlock, and starts the next window fresh. */
    @Test
    fun `returning inside the delay keeps the profile unlocked`() {
        gate.grant(profile, relockDelay = 1.minutes)
        val activity = Activity()
        watcher.onActivityStarted(activity)

        watcher.onActivityStopped(activity)
        clock.advanceBy(30.seconds)
        watcher.onActivityStarted(activity)

        assertTrue(gate.isUnlocked(profile))
    }

    /** Returning after it does not. */
    @Test
    fun `returning after the delay finds the profile locked`() {
        gate.grant(profile, relockDelay = 1.minutes)
        val activity = Activity()
        watcher.onActivityStarted(activity)

        watcher.onActivityStopped(activity)
        clock.advanceBy(90.seconds)
        watcher.onActivityStarted(activity)

        assertFalse(gate.isUnlocked(profile))
    }

    /** An activity that reports itself as being recreated for a configuration change. */
    private class RecreatingActivity : Activity() {
        override fun isChangingConfigurations(): Boolean = true
    }
}
