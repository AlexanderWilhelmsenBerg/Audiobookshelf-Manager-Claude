package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.asFailure
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.core.model.flatMap
import com.example.shelfplayer.domain.lock.ProfileActivationGuard
import com.example.shelfplayer.domain.playback.PlaybackHandover
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.sync.BackgroundSync
import javax.inject.Inject

/**
 * PRODUCT_SPEC 6.5 / AUTH-002 — changing which profile the app is acting as.
 *
 * ### Order matters, and this is the order
 *
 * The selection is written **first**, then the credential is loaded. That looks backwards and is not: every
 * screen reads its content from Room keyed by the active profile, so writing the selection is what makes
 * the UI show the new account. Loading the credential first would leave a window in which requests were
 * signed as B while the screens still showed A's library — the cross-profile confusion product priority 4
 * exists to prevent.
 *
 * A profile whose credential will not load still switches. PRODUCT_SPEC 6.3 keeps its cached library
 * browsable offline and AUTH-004 keeps its downloads playable; the session state tells the UI to ask for a
 * password when the network is next needed. Refusing the switch would leave the user unable to reach an
 * account they can see in the switcher.
 *
 * ### The one case that does not switch
 *
 * AUTH-005 — a target profile with a passcode and no live unlock is **refused**, before the
 * selection is written. That is the single exception to the paragraph above, and the difference is what the
 * refusal protects: a profile whose *credential* will not load is still that user's own account, and
 * stranding them on it would be unhelpful; a profile whose *passcode* has not been entered is somebody
 * else's, and switching to it is the boundary product priority 4 exists to hold.
 *
 * It is enforced here rather than in the switcher because a UI check is a suggestion. Every path that
 * changes the active profile goes through this function, and this is where a second one would be noticed.
 *
 * ### The player is stopped before the context changes, and that is awaited
 *
 * 6.5 steps 2 and 3 come before step 4, and until [handover] existed they did not happen at all: the
 * selection was written while the previous account's book kept playing and kept journaling a position every
 * five seconds. The flush is therefore awaited rather than launched — see [PlaybackHandover] — because
 * "flush, then switch" expressed as two concurrent launches is not an order.
 *
 * Awaiting it is also why the flush must not be able to fail the switch. A player that cannot be reached is
 * not a reason to strand somebody on the account they are leaving, which is the same judgement the
 * paragraph above makes about a credential that will not load.
 */
class SwitchProfileUseCase @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository,
    private val syncAccount: SyncAccountUseCase,
    private val backgroundSync: BackgroundSync,
    /** AUTH-005 — whether the incoming profile may be opened at all. */
    private val activation: ProfileActivationGuard,
    /** PRODUCT_SPEC 6.5 steps 2–3 — the outgoing account's player, stopped and flushed before step 4. */
    private val handover: PlaybackHandover,
) {
    suspend operator fun invoke(profileId: ProfileId): AppResult<SessionStatus> {
        // Asked before anything is written. A refusal that had already changed the active profile would
        // leave the app showing an account it then declined to open.
        if (!activation.mayActivate(profileId)) {
            return AppError.Security(
                summary = "That account is locked. Enter its passcode to switch to it.",
            ).asFailure()
        }
        // PRODUCT_SPEC 6.5 steps 2–3, and they happen *here* — while `activeProfileId` still names the
        // account being left, so every write the flush produces is unambiguously theirs.
        //
        // Skipped when the target is already active. Re-selecting the current profile is a no-op the
        // switcher can produce with a stray tap, and tearing down a playing book for one would stop
        // playback for no reason at all (product priority 1).
        val outgoing = profileRepository.activeProfileId()
        if (outgoing != null && outgoing != profileId) {
            handover.handOver(outgoing)
        }
        // Step 4. Everything above this line belongs to the outgoing account; everything below it to the
        // incoming one.
        return profileRepository.setActiveProfile(profileId).flatMap {
            authRepository.restoreSession(profileId).also { status ->
                if (status.isActive()) {
                    refreshPermissions(profileId)
                    // PRODUCT_SPEC SYNC-003 — an account the user actually uses earns a background
                    // schedule. Idempotent by unique name, so calling it on every switch is the point
                    // rather than a cost: a profile added before this existed gets one the first time
                    // it is used.
                    backgroundSync.schedule(profileId)
                }
            }
        }
    }

    /**
     * PRODUCT_SPEC 5.2 — the incoming account's grant, re-read before its content fills the screen.
     *
     * The result is deliberately discarded. The switch has already happened by this point and must not be
     * undone by a server that could not be reached: PRODUCT_SPEC 6.3 keeps a profile's cached library
     * browsable offline, and failing the switch would strand the user on an account they were leaving.
     * What the call is *for* is the success case — a grant changed on the server since this profile last
     * signed in, which is otherwise never noticed — and the marking that `refreshPermissions` performs
     * when the server actively rejects the session.
     *
     * Only attempted for a session that restored. A profile already needing reauthentication has no token
     * to ask with, and asking anyway would spend a network round trip to be told what is already known.
     *
     * This is cheap on purpose. AUTH-002 allows 500 ms for a switch, so the library sync that has to
     * follow it belongs to the screen that shows the result, not to this call.
     */
    private suspend fun refreshPermissions(profileId: ProfileId) {
        syncAccount(profileId)
    }

    private fun AppResult<SessionStatus>.isActive(): Boolean =
        this is AppResult.Success && value == SessionStatus.Active
}
