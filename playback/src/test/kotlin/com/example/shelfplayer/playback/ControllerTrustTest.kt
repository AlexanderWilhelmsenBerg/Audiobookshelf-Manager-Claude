package com.example.shelfplayer.playback

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC AUTH-003 / PLAY-001 / 14.5 — who may read a private library through the exported session.
 *
 * The defect these cover: `onConnect` granted `DEFAULT_SESSION_AND_LIBRARY_COMMANDS` and four custom
 * commands to every caller, and the five browse callbacks answered every caller, so any installed
 * application could enumerate the active profile's book titles and write a bookmark into the library.
 *
 * ### What this file can and cannot prove
 *
 * It proves the **policy**. It cannot prove that `PlaybackService` calls it — `MediaSession.ControllerInfo`
 * is final with a package-private constructor, so the adapter that reads Media3's six facts is not
 * reachable from a JVM test. That is the same seam `docs/risks.md` R-43 records, and it is why
 * [ControllerTrust.accessFor] returns a branch rather than a boolean: there is exactly one shape a caller
 * can take, and every call site in the service is a `when` over it or a `mayBrowse` on it.
 *
 * The residual — that the adapter reads the right six fields — is a device-tier check and is recorded
 * rather than pretended away.
 */
class ControllerTrustTest {

    private val selfUid = 10_042

    private fun stranger(
        packageName: String = "com.example.some.other.app",
        uid: Int = 11_777,
        isTrustedForMediaControl: Boolean = false,
        isMediaNotificationController: Boolean = false,
        isAutomotive: Boolean = false,
        isAutoCompanion: Boolean = false,
    ) = ControllerIdentity(
        packageName = packageName,
        uid = uid,
        isTrustedForMediaControl = isTrustedForMediaControl,
        isMediaNotificationController = isMediaNotificationController,
        isAutomotive = isAutomotive,
        isAutoCompanion = isAutoCompanion,
    )

    // ------------------------------------------------------------------ the refusal this exists for

    /** The defect, stated as a test: an ordinary installed app gets transport and nothing else. */
    @Test
    fun `an unrelated application may not browse the library`() {
        val access = ControllerTrust.accessFor(stranger(), selfUid)

        assertEquals(ControllerAccess.PlaybackOnly, access)
        assertFalse(ControllerTrust.mayBrowse(access))
    }

    /**
     * **A package name is a claim, not evidence**, and this is the test that says so.
     *
     * An app that names itself `com.google.android.projection.gearhead` reaches `onConnect` with that
     * string in `packageName`, because the string arrives over IPC. It is refused, because the policy never
     * reads `packageName` — the car branch is Media3's own `isAutoCompanionController`, which is computed
     * rather than claimed. Revert the policy to a package comparison and this is the test that fails.
     */
    @Test
    fun `an application that claims a car's package name is still refused`() {
        val impostor = stranger(packageName = "com.google.android.projection.gearhead")

        assertEquals(ControllerAccess.PlaybackOnly, ControllerTrust.accessFor(impostor, selfUid))
    }

    /** Transport is deliberately kept: a headset, a watch and a Bluetooth remote must keep working. */
    @Test
    fun `an untrusted controller is not rejected outright`() {
        // The policy has two outcomes and neither of them is "refuse the connection". If a third is ever
        // added, this is the test that makes somebody decide what a headset button should do.
        assertEquals(
            setOf(ControllerAccess.LibraryAndPlayback, ControllerAccess.PlaybackOnly),
            ControllerAccess.entries.toSet(),
        )
    }

    // ------------------------------------------------------------------ who is admitted, and on what

    /** The app's own UI and service. Matched on the UID, which the platform assigns. */
    @Test
    fun `this application may browse`() {
        val self = stranger(packageName = "org.homebord.bookwave", uid = selfUid)

        assertEquals(ControllerAccess.LibraryAndPlayback, ControllerTrust.accessFor(self, selfUid))
    }

    /** A projected Android Auto head unit, by Media3's own predicate rather than by name. */
    @Test
    fun `a projected car may browse`() {
        val car = stranger(isAutoCompanion = true)

        assertEquals(ControllerAccess.LibraryAndPlayback, ControllerTrust.accessFor(car, selfUid))
    }

    /** Automotive OS's own media host, which is a different host from the projected one. */
    @Test
    fun `an automotive host may browse`() {
        val automotive = stranger(isAutomotive = true)

        assertEquals(ControllerAccess.LibraryAndPlayback, ControllerTrust.accessFor(automotive, selfUid))
    }

    /**
     * The platform's own answer — a holder of `MEDIA_CONTENT_CONTROL`, the system media panel, or a
     * notification listener the *user* enabled. Assistant arrives here.
     */
    @Test
    fun `a controller the platform trusts for media control may browse`() {
        val trusted = stranger(isTrustedForMediaControl = true)

        assertEquals(ControllerAccess.LibraryAndPlayback, ControllerTrust.accessFor(trusted, selfUid))
    }

    /** This session's own notification controller. */
    @Test
    fun `the media notification controller may browse`() {
        val notification = stranger(isMediaNotificationController = true)

        assertEquals(
            ControllerAccess.LibraryAndPlayback,
            ControllerTrust.accessFor(notification, selfUid),
        )
    }

    // ------------------------------------------------------------------ the edges that decide correctness

    /**
     * A different app running under a *different* UID is refused even if it shares this app's package
     * name — and, more usefully, the same UID is what admits a caller, so this pins that the comparison is
     * against the service's own UID rather than against any constant.
     */
    @Test
    fun `the self check compares the service's own uid, not a fixed one`() {
        val caller = stranger(uid = 12_345)

        assertEquals(ControllerAccess.PlaybackOnly, ControllerTrust.accessFor(caller, selfUid = 10_042))
        assertEquals(ControllerAccess.LibraryAndPlayback, ControllerTrust.accessFor(caller, selfUid = 12_345))
    }

    /** Any one of the four admitting facts is sufficient on its own; none of them is required. */
    @Test
    fun `each admitting fact is sufficient by itself`() {
        val admitting = listOf(
            "self" to stranger(uid = selfUid),
            "notification" to stranger(isMediaNotificationController = true),
            "automotive" to stranger(isAutomotive = true),
            "auto companion" to stranger(isAutoCompanion = true),
            "platform trust" to stranger(isTrustedForMediaControl = true),
        )

        for ((name, identity) in admitting) {
            assertTrue(
                ControllerTrust.mayBrowse(ControllerTrust.accessFor(identity, selfUid)),
                "$name should admit a controller on its own",
            )
        }
    }

    /** And with every fact false, nothing admits it — the default is closed. */
    @Test
    fun `with no admitting fact the default is closed`() {
        assertFalse(ControllerTrust.mayBrowse(ControllerTrust.accessFor(stranger(), selfUid)))
    }
}
