package com.example.shelfplayer.domain.lock

/**
 * PRODUCT_SPEC ROUTE-002 — "Auto-play never starts when the active profile is biometric/PIN locked."
 *
 * ### Why this interface is one method wide
 *
 * Because `:playback` needs exactly one fact and must not be able to ask for more. It cannot show a
 * prompt — the three call sites that consult this run with no window attached: a headset connecting, a
 * car connecting, the app being opened — so an interface offering `submitPasscode` would offer a
 * capability with nowhere to put a UI.
 *
 * It also deliberately does not go on `ProfileRepository`. That interface's `null` profile is read as
 * "signed out" by dozens of call sites, and it has ten test fakes; widening it would mean editing all
 * of them to say nothing about a lock. A one-method interface has one fake.
 *
 * ### It fails closed, and silence is the safe direction
 *
 * An implementation must return `true` when it cannot tell. The consequence of a wrong `true` is that
 * a book does not start playing by itself, which ROUTE-002 already licenses — it calls auto-play
 * "best-effort". The consequence of a wrong `false` is a locked account's book playing out loud to
 * whoever is holding the phone.
 */
fun interface ProfileLockGuard {
    suspend fun isActiveProfileLocked(): Boolean
}
