package com.example.shelfplayer.domain.lock

import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.model.ProfileId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/**
 * PRODUCT_SPEC AUTH-005 — who is currently unlocked, held in memory and nowhere else.
 *
 * ### Not persisted, on purpose
 *
 * A ticket that survived process death would mean a locked profile is unlocked again after a reboot,
 * or after Android killed the app in the background — which is most of the times a phone changes
 * hands. So process start has no tickets, and a profile with a record is locked. That also closes the
 * hole a `rememberSaveable` would open: nothing that survives can say "unlocked".
 *
 * ### The ticket is evaluated against the clock, not expired by an event
 *
 * This is the difference between a lock that works and one that only works while the UI is running.
 * If backgrounding *scheduled* an expiry, then a headset connecting ninety seconds after the phone
 * went into a pocket would consult a gate that no Activity callback had reached yet, and the answer
 * would be "unlocked". Instead [isUnlocked] compares [backgroundedAt] to now every time it is asked,
 * so a caller in the media service gets the same answer the UI would.
 *
 * ### Why `:domain`
 *
 * `:playback` has `api(projects.domain)` and `:app` is invisible to both, so this is the only module
 * where one singleton can be shared by the curtain, the switcher and the media service.
 */
@Singleton
class ProfileLockGate @Inject constructor(private val clock: AppClock) {

    private val unlocked = MutableStateFlow<Map<ProfileId, Ticket>>(emptyMap())

    /** Changes whenever a ticket is granted or revoked, so the curtain can recompose. */
    val tickets: StateFlow<Map<ProfileId, Ticket>> = unlocked.asStateFlow()

    /**
     * Whether [profileId] currently holds a live unlock.
     *
     * A ticket with a `backgroundedAt` older than its relock delay is not live — and it is not removed
     * here either. Removing it would be a write from a read, which would make this function unsafe to
     * call from the several places that call it concurrently; the stale entry is harmless because it
     * can never be live again, and it is replaced on the next unlock.
     */
    fun isUnlocked(profileId: ProfileId): Boolean {
        val ticket = unlocked.value[profileId] ?: return false
        val since = ticket.backgroundedAt ?: return true
        return clock.now() < since.plusMillis(ticket.relockDelay.inWholeMilliseconds)
    }

    /** Grants an unlock. Called after a correct passcode, a biometric success, or a fresh sign-in. */
    fun grant(profileId: ProfileId, relockDelay: Duration) {
        unlocked.value = unlocked.value + (profileId to Ticket(relockDelay = relockDelay))
    }

    /** Revokes one profile's ticket — "Lock now", or the passcode being turned on. */
    fun revoke(profileId: ProfileId) {
        unlocked.value = unlocked.value - profileId
    }

    /**
     * Records that the app left the foreground.
     *
     * Stamped on every live ticket rather than held as one global value, because the delay is per
     * profile: two profiles backgrounded at the same moment can relock at different times.
     */
    fun onBackgrounded() {
        val now = clock.now()
        unlocked.value = unlocked.value.mapValues { (_, ticket) ->
            if (ticket.backgroundedAt == null) ticket.copy(backgroundedAt = now) else ticket
        }
    }

    /**
     * Records that the app came back to the foreground.
     *
     * Only clears the stamp on tickets that are *still live*. A ticket whose delay has elapsed keeps
     * its stamp and stays dead — otherwise returning to the app would resurrect it, which is precisely
     * the bug this design is arranged to avoid.
     */
    fun onForegrounded() {
        val now = clock.now()
        unlocked.value = unlocked.value.filterValues { ticket ->
            val since = ticket.backgroundedAt ?: return@filterValues true
            now < since.plusMillis(ticket.relockDelay.inWholeMilliseconds)
        }.mapValues { (_, ticket) -> ticket.copy(backgroundedAt = null) }
    }

    /** One profile's live unlock. */
    data class Ticket(val relockDelay: Duration, val backgroundedAt: Instant? = null)
}
