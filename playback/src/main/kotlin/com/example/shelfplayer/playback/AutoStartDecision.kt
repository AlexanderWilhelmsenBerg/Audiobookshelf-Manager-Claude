package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.DeviceKind
import com.example.shelfplayer.core.model.playback.DevicePolicy
import com.example.shelfplayer.core.model.playback.KnownDevice
import com.example.shelfplayer.domain.lock.ProfileLockGuard
import com.example.shelfplayer.domain.repository.DeviceRepository
import java.time.Instant

/**
 * PRODUCT_SPEC ROUTE-002 — what a device connecting should do, given its policy and the lock.
 *
 * ### Why this is a pure function in its own file
 *
 * Because ROUTE-002's lock clause — *"Auto-play never starts when the active profile is biometric/PIN
 * locked"* — is a sentence that can be true of the code or not, and until now there was nothing to point
 * at. `OutputDeviceWatcher` had no test at all: every branch of its policy `when` was uncovered. Extracting
 * the decision turns the clause into a truth table.
 *
 * ### Arming is suppressed too, and the spec does not say so
 *
 * ROUTE-002's sentence names only auto-play. This suppresses [DevicePolicy.ArmOnly] and [DevicePolicy.Ask]
 * as well, and that is a deliberate reading rather than an oversight — recorded in ADR-0023.
 *
 * The reason is what arming does: it makes no sound, and it puts the locked account's book title, author
 * and cover on the lock screen, one press of a headset button away from audio. That press cannot be
 * intercepted — there is no `onPlayerCommandRequest` and no `ForwardingPlayer` in this app — so an armed
 * locked profile is a lock with a hole in it that the lock screen advertises. Product priority 4 decides it
 * where the specification is silent.
 *
 * [DevicePolicy.Never] stays [AutoStartAction.None] rather than becoming [AutoStartAction.Suppressed]: the
 * lock changed nothing about that device, and a log line claiming it did would be false.
 */
internal object AutoStartDecision {

    fun decide(policy: DevicePolicy, isProfileLocked: Boolean): AutoStartAction = when (policy) {
        DevicePolicy.Never -> AutoStartAction.None
        DevicePolicy.AutoPlay -> if (isProfileLocked) AutoStartAction.Suppressed else AutoStartAction.ArmAndPlay
        DevicePolicy.ArmOnly, DevicePolicy.Ask ->
            if (isProfileLocked) AutoStartAction.Suppressed else AutoStartAction.Arm
    }
}

/**
 * What the watcher should do.
 *
 * [Suppressed] is distinct from [None] so the two can be logged differently. "Nothing happened because the
 * account is locked" and "nothing happened because you asked for nothing to happen" are different answers
 * to somebody reading diagnostics after a headset failed to start their book.
 */
internal enum class AutoStartAction { ArmAndPlay, Arm, Suppressed, None }

/**
 * PRODUCT_SPEC ROUTE-002 — what a **car** connecting should do, resolved against its stored policy.
 *
 * ### Why this is separate from [AutoStartDecision]
 *
 * `AutoStartDecision` is arithmetic: given a policy and a lock, what happens. This is the *wiring*: which
 * policy, from where, and recorded how. R-43's lesson in this codebase is that the arithmetic is never what
 * ships broken — the wiring is, and a pure decision function with ten passing tests can sit behind a caller
 * that never reaches it.
 *
 * That is exactly what happened here. `AutoStartDecision` shipped in Phase 4 with a full truth table while
 * `PlaybackService.onPostConnect` went on reading a global boolean, so a car obeyed a switch on another
 * screen instead of the policy the listener had set. Extracting these three lines is what lets a test say
 * "a car connecting consults the car's policy" rather than only "the policy table is correct".
 *
 * ### `remember` before `policyFor`
 *
 * The same order [OutputDeviceWatcher] uses, for the same reason: a first-ever connection has to be stored
 * before it can be asked about, or it falls through the gap between the two and is treated as having no
 * policy rather than the default one.
 */
internal object CarConnection {

    suspend fun decide(devices: DeviceRepository, lock: ProfileLockGuard, now: Instant): AutoStartAction {
        devices.remember(
            KnownDevice(
                id = KnownDevice.CAR_ID,
                kind = DeviceKind.Car,
                displayName = KnownDevice.CAR_DISPLAY_NAME,
                policy = DevicePolicy.Default,
                lastSeenAt = now,
            ),
        )
        val policy = devices.policyFor(KnownDevice.CAR_ID)
        return AutoStartDecision.decide(policy, isProfileLocked = lock.isActiveProfileLocked())
    }
}
