package com.example.shelfplayer.domain.lock

import com.example.shelfplayer.core.model.ProfileId

/**
 * PRODUCT_SPEC AUTH-005 — the way back in after a forgotten passcode.
 *
 * The curtain tells the user, in as many words, that signing in to the account again clears its passcode.
 * Something has to make that true, or the sentence is a promise made in a *security disclosure* and not
 * kept — worse than not offering the route at all, because somebody would try it, fail, and still be shut
 * out of their own library.
 *
 * ### Why it is one method rather than two collaborators
 *
 * The operation is conditional: clear the record **only if the profile was actually locked**. An ordinary
 * AUTH-004 re-authentication after a session expires must not quietly switch somebody's lock off — that
 * user has forgotten nothing and asked for nothing. Expressing the condition here rather than at the call
 * site keeps the gate and the store on the side of the boundary that already holds them, and it keeps
 * `SignInUseCase` from needing two more dependencies.
 *
 * It is also the third time in this feature that a single-method `fun interface` was the right answer, and
 * for the same reason each time: `:core:testing` is a JVM module that must not depend on `:domain`
 * (PRODUCT_SPEC 9.3's layering runs the other way), so any fake wide enough to stand in for
 * `ProfileLockRepository` would have to exist separately in `:domain`'s tests and in `:app`'s — two
 * implementations, free to disagree, which is exactly what R-37 records as the way a passing test stops
 * meaning anything. A lambda cannot drift from itself.
 */
fun interface LockedProfileRecovery {
    /**
     * Clears [profileId]'s passcode when that profile is currently locked, and does nothing otherwise.
     *
     * Deliberately returns nothing. The caller has already succeeded at the thing that mattered — the
     * account password was accepted — and a failure to tidy up a lock record must not turn a successful
     * sign-in into a reported failure.
     */
    suspend fun clearIfLocked(profileId: ProfileId)
}
