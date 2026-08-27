package com.example.shelfplayer.playback

import androidx.media3.common.Player

/**
 * PRODUCT_SPEC AUTH-003 / PLAY-001 / 14.5 — what a controller that binds to this session is allowed to see.
 *
 * ### The problem this closes
 *
 * `MediaLibraryService` is `exported="true"` and must be: un-exporting it is how the app disappears from
 * Android Auto and from Assistant. So the question was never whether to export, but what an arbitrary
 * caller gets once it binds — and until now the answer was *everything*. `onConnect` handed
 * `DEFAULT_SESSION_AND_LIBRARY_COMMANDS` plus four custom commands to whatever asked, and `onGetChildren`
 * answered with the active profile's book titles. Any installed application could enumerate a private
 * self-hosted library, which PRODUCT_SPEC 14.5 exists to prevent, and could write a bookmark into it.
 *
 * One half was already closed, deliberately: `onAddMediaItems` gates a *pre-resolved* item on the caller's
 * UID, so no outside app can push a URI of its choosing into the authenticated streaming client. That gate
 * stays exactly as it was. This adds the other half.
 *
 * ### Why the answer is a capability split rather than a package allowlist
 *
 * The owner's decision, and it is the right one on the merits: an allowlist is a list of the cars somebody
 * thought of in 2026. Media3 already computes the fact that matters — `ControllerInfo.isTrusted()` is
 * `MediaSessionManager.isTrustedForMediaControl`, which is true for the system, for a holder of
 * `MEDIA_CONTENT_CONTROL`, and for an enabled notification listener. That set is maintained by the
 * platform and by the user, and it is the same set every other media app on the device relies on.
 *
 * The three package-shaped checks below are Media3's own — `isAutomotiveController`,
 * `isAutoCompanionController`, `isMediaNotificationController` — not this project's. They are here because
 * both car predicates additionally require a *legacy* connection (`controllerVersion == 0`), so a car that
 * connects that way may not also be reported trusted, and losing Android Auto is a worse outcome than
 * admitting one more caller the platform already ships.
 *
 * ### What an untrusted caller keeps
 *
 * Transport. Play, pause, seek, next, previous — `DEFAULT_SESSION_COMMANDS` without the library half. A
 * Bluetooth remote, a smartwatch, a headset button and a tasker-style automation all still work, which is
 * what the owner asked for and is the reason this is not simply `reject()`. What they lose is the ability
 * to *enumerate* — to ask what is in the library, to search it, or to write a bookmark into it.
 *
 * ### Why the decision is a function over a value type, and not a predicate on `ControllerInfo`
 *
 * `MediaSession.ControllerInfo` is `final` with a package-private constructor: nothing outside Media3 can
 * build one, so a predicate written against it is a predicate no JVM test can call. Lifting the six facts
 * into [ControllerIdentity] makes the policy testable, and — the part that matters more — [accessFor]
 * returns the *branch the caller then takes* rather than a boolean somebody has to remember to act on.
 *
 * That shape is deliberate and it is `docs/risks.md` R-43 and R-55 being obeyed rather than quoted:
 * `AutoStartDecision` had a complete truth table with ten passing tests while `onPostConnect` never called
 * it, and the feature was inert on a device for four phases. A test proves this policy is *right*; it
 * cannot prove anything calls it. Keeping the branch here, so every call site is one `when` over
 * [ControllerAccess], is what keeps the number of places that can forget down to one.
 */
internal enum class ControllerAccess {
    /** May browse and search the library, and use the app's custom commands. */
    LibraryAndPlayback,

    /** Transport only. May not enumerate, search, or write to the library. */
    PlaybackOnly,
}

/**
 * The facts about a connected controller that decide [ControllerAccess].
 *
 * Every field is read from Media3 at the boundary — see `PlaybackService`'s `identityOf` — rather than
 * derived here, so this file has no Android dependency and the policy can be exercised on the JVM.
 *
 * @property packageName the caller's claimed package. **Never used to decide access** — it arrives over
 *   IPC and is a claim, not evidence. It is carried so a refusal can be logged with something a person can
 *   act on, which is the difference between a car that mysteriously shows nothing and one whose refusal is
 *   in the debug console.
 * @property uid assigned by the platform; a caller cannot choose it. The only self-identifying evidence.
 * @property isTrustedForMediaControl Media3's `ControllerInfo.isTrusted()`.
 * @property isMediaNotificationController Media3's own notification controller for this session.
 * @property isAutomotive Media3's `isAutomotiveController` — Automotive OS's own media host.
 * @property isAutoCompanion Media3's `isAutoCompanionController` — a projected Android Auto head unit.
 */
internal data class ControllerIdentity(
    val packageName: String,
    val uid: Int,
    val isTrustedForMediaControl: Boolean,
    val isMediaNotificationController: Boolean,
    val isAutomotive: Boolean,
    val isAutoCompanion: Boolean,
)

internal object ControllerTrust {

    /**
     * The access [identity] is granted, given the service's own [selfUid].
     *
     * `selfUid` is a parameter rather than a `Process.myUid()` call inside this function, for the reason
     * the whole file exists: a function that reads the platform is a function a JVM test cannot pin.
     */
    fun accessFor(identity: ControllerIdentity, selfUid: Int): ControllerAccess = when {
        // This application: its own UI, its own notification controller, its own service. Matched on UID
        // because that is the one identifier the platform assigns and a caller cannot forge.
        identity.uid == selfUid -> ControllerAccess.LibraryAndPlayback

        // Media3's notification controller for this session. Already same-package by Media3's own
        // definition, so this is belt and braces rather than a second rule.
        identity.isMediaNotificationController -> ControllerAccess.LibraryAndPlayback

        // A car. Both of Media3's predicates, because Automotive OS and a projected head unit are
        // different hosts and only one of them is `gearhead`.
        identity.isAutomotive || identity.isAutoCompanion -> ControllerAccess.LibraryAndPlayback

        // The platform's own answer to "may this caller control media": the system, a holder of
        // MEDIA_CONTENT_CONTROL, or a notification listener the *user* enabled. Assistant and the system
        // media panel arrive here.
        identity.isTrustedForMediaControl -> ControllerAccess.LibraryAndPlayback

        // Everything else. Not refused — it keeps transport — but it does not get to read the library.
        else -> ControllerAccess.PlaybackOnly
    }

    /** Whether [access] may read, search or write library content. */
    fun mayBrowse(access: ControllerAccess): Boolean = access == ControllerAccess.LibraryAndPlayback

    /**
     * The `Player.Commands` that must be withheld from [access].
     *
     * **This is the half the first version of this policy missed**, and a device run found it. Narrowing
     * `SessionCommands` in `onConnect` narrows the *session* surface — the custom actions and the library
     * calls — and leaves `Player.Commands` at Media3's defaults. Those defaults include
     * `COMMAND_SET_MEDIA_ITEM`, `COMMAND_CHANGE_MEDIA_ITEMS`, `COMMAND_STOP` and `COMMAND_RELEASE`, so a
     * controller that had been refused the library could still clear the queue and stop the book. Confirmed
     * from a second UID on an SM-S928B: the attacker's URI never loaded and no bearer was sent, and
     * playback stopped anyway.
     *
     * That is product priority 1 — *do not interrupt playback* — reachable by any installed app, which
     * makes it worse than the browse leak this policy was written for.
     *
     * ### What is withheld, and what deliberately is not
     *
     * Withheld: the four that mutate what is loaded or end the session. Kept: play, pause, every seek, and
     * the getters. A headset button, a watch and a Bluetooth remote still do everything they legitimately
     * do — the point of `PlaybackOnly` is that transport works.
     *
     * `COMMAND_RELEASE` is in the list because releasing the session from outside ends playback as surely
     * as stopping it, and no third party has a reason to.
     */
    fun withheldPlayerCommands(access: ControllerAccess): List<Int> = when (access) {
        ControllerAccess.LibraryAndPlayback -> emptyList()
        ControllerAccess.PlaybackOnly -> listOf(
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_STOP,
            Player.COMMAND_RELEASE,
        )
    }
}
