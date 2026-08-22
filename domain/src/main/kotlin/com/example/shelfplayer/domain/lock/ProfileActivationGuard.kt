package com.example.shelfplayer.domain.lock

import com.example.shelfplayer.core.model.ProfileId

/**
 * AUTH-005 — whether a profile may become the active one right now.
 *
 * ### Why one method and not two collaborators
 *
 * The first version of `SwitchProfileUseCase`'s check took a `ProfileLockGate` **and** a
 * `ProfileLockRepository`, and asked them two questions: does this profile have a passcode, and is it
 * currently unlocked. That is one question with two halves, and splitting it cost more than it looks:
 *
 *  - every test that constructed the use case had to supply both, so a `ProfileSwitcherViewModelTest` in
 *    `:app` needed a `ProfileLockRepository` fake it had no other use for;
 *  - `:core:testing` cannot host that fake, because it is a JVM module that depends on `:core:model` and
 *    `:core:common` and must not depend on `:domain` — PRODUCT_SPEC 9.3's layering runs the other way;
 *  - so the fake would have existed **twice**, in two modules, free to disagree. That is precisely the
 *    failure R-37 records: a duplicated double is two implementations, and one of them is wrong.
 *
 * A single-method `fun interface` removes all three. A test supplies a lambda, there is no fake to
 * duplicate, and the use case's constructor grows by one rather than two.
 *
 * ### It answers for *any* profile, not the active one
 *
 * That is the difference from [ProfileLockGuard], which asks only about the profile that is already
 * active. Switching is a question about a profile that is not active yet, so it needs its own verb; using
 * the active-profile guard here would have answered about the account being left rather than the one being
 * opened.
 */
fun interface ProfileActivationGuard {
    /**
     * `true` when [profileId] has no passcode, or has one and holds a live unlock.
     *
     * Implementations fail **closed**: an error resolves to `false`, refusing the switch. A refused switch
     * is recoverable — the user enters the passcode and tries again — and the alternative is opening
     * somebody else's account because a disk read failed.
     */
    suspend fun mayActivate(profileId: ProfileId): Boolean
}
