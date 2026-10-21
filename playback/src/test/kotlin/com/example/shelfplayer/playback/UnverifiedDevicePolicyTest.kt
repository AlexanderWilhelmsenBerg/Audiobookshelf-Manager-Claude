package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.DeviceKind
import com.example.shelfplayer.core.model.playback.DevicePolicy
import org.junit.Test
import kotlin.test.assertEquals

/** Regression: an old Bluetooth name-based policy must not silently authorize a new physical device. */
class UnverifiedDevicePolicyTest {
    @Test
    fun nameOnlyDeviceCannotInheritAnUnrelatedDevicesAutoPlay() {
        for (kind in listOf(DeviceKind.Bluetooth, DeviceKind.HearingAid, DeviceKind.Other)) {
            assertEquals(
                AutoStartAction.Arm,
                AutoStartDecision.forObservedOutput(DevicePolicy.AutoPlay, false, kind),
            )
            assertEquals(
                AutoStartAction.Suppressed,
                AutoStartDecision.forObservedOutput(DevicePolicy.AutoPlay, true, kind),
            )
        }
    }

    @Test
    fun explicitKnownWiredAndCarCategoryPoliciesRemainIntact() {
        for (kind in listOf(DeviceKind.Wired, DeviceKind.Car)) {
            assertEquals(
                AutoStartAction.ArmAndPlay,
                AutoStartDecision.forObservedOutput(DevicePolicy.AutoPlay, false, kind),
            )
        }
    }

    @Test
    fun perDeviceNeverAndArmOnlyPoliciesRemainConservative() {
        for (kind in DeviceKind.entries) {
            assertEquals(AutoStartAction.None, AutoStartDecision.forObservedOutput(DevicePolicy.Never, false, kind))
            assertEquals(AutoStartAction.Arm, AutoStartDecision.forObservedOutput(DevicePolicy.ArmOnly, false, kind))
        }
    }
}
