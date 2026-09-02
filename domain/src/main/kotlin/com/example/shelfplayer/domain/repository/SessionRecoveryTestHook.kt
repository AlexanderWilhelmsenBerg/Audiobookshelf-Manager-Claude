package com.example.shelfplayer.domain.repository

/**
 * PRODUCT_SPEC AUTH-004 — a way to put this install into the state the renewal path exists for.
 *
 * ### Why a seam rather than a line in the settings screen
 *
 * The credential lives in `:data:auth` behind the Keystore-backed store, and PRODUCT_SPEC 9.3 keeps that
 * module's internals out of the UI: a ViewModel naming `SessionTokenProvider` is the same mistake as one
 * naming `AppSettingsDataSource`. One method, declared here, is what lets the debug control exist without
 * opening that door.
 *
 * ### Why it exists at all
 *
 * `docs/testing/pr-playback-auth-recovery.md` has eleven steps and every one of them starts by asking the
 * tester to *"reproduce an expired access token"*. Until this existed that meant an intercepting proxy or a
 * shortened server-side token lifetime — neither of which is available to somebody holding a phone in a
 * car, which is exactly where the defect was found. The renewal itself runs on Media3's loader thread
 * against a real server and no JVM test can reach it, so the alternative to a device affordance is not
 * testing it.
 *
 * It only ever **downgrades** a credential: it cannot mint one, read one out, or touch a profile other than
 * the active one. The control that calls it is offered behind `BuildConfig.DEBUG`.
 */
interface SessionRecoveryTestHook {
    /**
     * Makes the active profile's access token one the server will refuse, keeping its refresh token.
     *
     * @return `false` when there is no active profile, or none with a refresh token. That refusal is the
     *   useful answer rather than a failure: a profile that cannot renew would surface a sign-in prompt
     *   instead of a recovery, which proves nothing about the code under test and costs the tester their
     *   session.
     */
    suspend fun expireAccessToken(): Boolean
}
