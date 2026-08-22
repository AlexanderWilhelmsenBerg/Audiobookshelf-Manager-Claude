package com.example.shelfplayer.core.model.lock

import com.example.shelfplayer.core.model.ProfileId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * AUTH-005 — whether the app's own screens are behind the curtain.
 *
 * **"AUTH-005" is `docs/gaps.md`'s label, not a PRODUCT_SPEC identifier.** The specification stops at
 * AUTH-004; what this feature implements is a cluster of real sections that never got a number of their
 * own — 3.2's "optional profile PIN or biometric gate", AUTH-003's "optional biometric locking protects
 * profile selection, not server authentication semantics", 3.3's Profiles group, ROUTE-002's
 * "auto-play never starts when the active profile is biometric/PIN locked", and 24.14's open decision
 * about whether any of it ships in version 1. ADR-0023 answers that last one and quotes the rest.
 *
 * ### Why [Resolving] exists
 *
 * Because the honest answer at the first frame is "not yet known", and both of the alternatives are
 * wrong in a visible way. Starting at [Unlocked] flashes the shelf — with a book title on it —
 * before the curtain arrives, which is the one thing the lock exists to prevent. Starting at [Locked]
 * shows a passcode field to the overwhelming majority of users who have no passcode at all.
 *
 * This is the same reasoning `AppUiState.isResolved` already applies to the start destination, and for
 * the same reason: a screen that corrects itself is a screen the user saw the wrong version of.
 */
sealed interface ProfileLockState {
    /** The record has not been read yet. Draw neither the app nor the curtain. */
    data object Resolving : ProfileLockState

    /** No passcode, or a live unlock. */
    data object Unlocked : ProfileLockState

    /**
     * The curtain is up for [profileId].
     *
     * @property canUsePasscode `false` once the attempt limit is exhausted, or when the record cannot be
     *   read at all. The curtain then offers only re-authentication and the destructive fallback, rather
     *   than a field that will refuse every value including the right one.
     * @property unreadable the record exists and cannot be decrypted — a key invalidated by a
     *   lock-screen change, or a restore onto new hardware. Named separately because "wrong passcode" is
     *   a lie here, and a user told that would keep trying.
     */
    data class Locked(val profileId: ProfileId, val canUsePasscode: Boolean = true, val unreadable: Boolean = false) :
        ProfileLockState
}

/**
 * AUTH-005 — how long after leaving the app a protected profile relocks.
 *
 * [Immediately] is the default, and it is the default because the threat this lock is for is somebody
 * picking up an unlocked phone — a grace period is exactly the window that person is in. The two
 * longer options exist because a lock that demands a passcode every time you glance at a notification
 * is a lock people switch off, and a lock that is switched off protects nothing.
 */
enum class RelockDelay(val delay: Duration) {
    Immediately(Duration.ZERO),
    AfterOneMinute(1.minutes),
    AfterFifteenMinutes(15.minutes),
    ;

    companion object {
        /**
         * The stored seconds, as an option. An unrecognised value reads as [Immediately] — the strictest
         * choice, which is the right direction for a value that could only be unrecognised because
         * something wrote it wrongly.
         */
        fun ofSeconds(seconds: Long): RelockDelay =
            entries.firstOrNull { it.delay.inWholeSeconds == seconds } ?: Immediately
    }
}

/**
 * AUTH-005 — why the biometric option is or is not offered.
 *
 * Every unavailable case is a distinct value rather than one `false`, because the row is *shown*
 * disabled with the reason on it. A device run in Phase 5 established why: a hidden row is
 * indistinguishable from an unbuilt feature, and was reported as one.
 */
enum class BiometricAvailability {
    Available,

    /** API 26 and 27. See `PlatformBiometricGateway` for why those two levels get nothing. */
    UnsupportedAndroidVersion,
    NoHardware,
    NoneEnrolled,
}

/** What the user is told after a wrong passcode, without ever naming the value they typed. */
sealed interface UnlockFailure {
    data class Wrong(val remainingBeforeBackoff: Int) : UnlockFailure
    data class BackingOff(val remaining: Duration) : UnlockFailure
    data object Exhausted : UnlockFailure

    /** The record cannot be decrypted. Only re-authentication or removal can clear this. */
    data object Unreadable : UnlockFailure
}

/**
 * AUTH-005 — why a proposed passcode was refused.
 *
 * In `:core:model` rather than beside the KDF that produces it, because three different layers need it:
 * the store validates, the repository reports, and the settings section has to say *which* rule was
 * broken. Collapsing them into one "invalid" was a real defect — a passcode of `111111` was refused with
 * "a passcode is between 6 and 12 digits", which is not true of it and tells the user nothing they can act
 * on. Android lint found it, by noticing that the string explaining the third case was never rendered.
 */
enum class PasscodeRejection {
    /** Shorter than six digits or longer than twelve. */
    Length,

    /** Contains something that is not a digit. */
    NotDigits,

    /** A single repeated digit, or a run straight up or down. */
    TooSimple,
}
