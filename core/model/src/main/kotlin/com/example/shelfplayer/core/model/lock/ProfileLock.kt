package com.example.shelfplayer.core.model.lock

import com.example.shelfplayer.core.model.ProfileId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * PRODUCT_SPEC AUTH-005 — whether the app's own screens are behind the curtain.
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
    data class Locked(
        val profileId: ProfileId,
        val canUsePasscode: Boolean = true,
        val unreadable: Boolean = false,
    ) : ProfileLockState
}

/**
 * PRODUCT_SPEC AUTH-005 — how long after leaving the app a protected profile relocks.
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
 * PRODUCT_SPEC AUTH-005 — why the biometric option is or is not offered.
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
