package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.DevicePolicy
import org.junit.Test
import kotlin.test.assertEquals

/**
 * PRODUCT_SPEC ROUTE-002 — "Auto-play never starts when the active profile is biometric/PIN locked."
 *
 * This is the first coverage `OutputDeviceWatcher`'s policy branch has ever had. Before the decision was
 * extracted there was nothing to point at: the whole `when` lived inside a class that needs an
 * `AudioManager`, a `Context` and a registered callback to reach, so every branch of it was untested and
 * the lock clause was a claim rather than a fact.
 */
class AutoStartDecisionTest {

    /** The clause itself, for the policy it names. */
    @Test
    fun `auto-play is suppressed while the profile is locked`() {
        assertEquals(
            AutoStartAction.Suppressed,
            AutoStartDecision.decide(DevicePolicy.AutoPlay, isProfileLocked = true),
        )
    }

    /**
     * Arming is suppressed too, and the specification does not say so.
     *
     * A deliberate stricter reading, recorded in ADR-0023. Arming makes no sound, and it puts the locked
     * account's title, author and cover on the lock screen — one headset press from audio, and that press
     * cannot be intercepted anywhere in this app. Product priority 4 decides it where the spec is silent.
     */
    @Test
    fun `arming is suppressed while the profile is locked`() {
        assertEquals(
            AutoStartAction.Suppressed,
            AutoStartDecision.decide(DevicePolicy.ArmOnly, isProfileLocked = true),
        )
        assertEquals(
            AutoStartAction.Suppressed,
            AutoStartDecision.decide(DevicePolicy.Ask, isProfileLocked = true),
        )
    }

    /**
     * `Never` stays `None`, not `Suppressed`.
     *
     * The lock changed nothing about a device the user asked to be left alone, and logging "suppressed
     * because the account is locked" would tell a diagnostics reader something false about why their book
     * did not start.
     */
    @Test
    fun `a device set to never react is unaffected by the lock`() {
        assertEquals(AutoStartAction.None, AutoStartDecision.decide(DevicePolicy.Never, isProfileLocked = true))
        assertEquals(AutoStartAction.None, AutoStartDecision.decide(DevicePolicy.Never, isProfileLocked = false))
    }

    /** Unlocked, every policy behaves exactly as it did before the lock existed. */
    @Test
    fun `an unlocked profile keeps every policy's original behaviour`() {
        assertEquals(
            AutoStartAction.ArmAndPlay,
            AutoStartDecision.decide(DevicePolicy.AutoPlay, isProfileLocked = false),
        )
        assertEquals(AutoStartAction.Arm, AutoStartDecision.decide(DevicePolicy.ArmOnly, isProfileLocked = false))
        assertEquals(AutoStartAction.Arm, AutoStartDecision.decide(DevicePolicy.Ask, isProfileLocked = false))
    }

    /**
     * Every policy is covered, so a new one cannot be added without deciding what the lock does to it.
     *
     * The `when` in the decision is exhaustive, so a new constant is a compile error there — but a new
     * constant that someone maps to `ArmAndPlay` without thinking would compile fine. This asserts the
     * count so the omission shows up here too.
     */
    @Test
    fun `every device policy has a locked and an unlocked answer`() {
        DevicePolicy.entries.forEach { policy ->
            AutoStartDecision.decide(policy, isProfileLocked = true)
            AutoStartDecision.decide(policy, isProfileLocked = false)
        }
        assertEquals(4, DevicePolicy.entries.size, "a new policy needs a locked answer chosen deliberately")
    }
}
