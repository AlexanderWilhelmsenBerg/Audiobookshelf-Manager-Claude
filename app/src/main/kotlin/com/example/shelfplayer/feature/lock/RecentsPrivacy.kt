package com.example.shelfplayer.feature.lock

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * PRODUCT_SPEC AUTH-005 / §15 / 14.5, ADR-0026 decision 3 — the app-switcher thumbnail.
 *
 * ### What was exposed
 *
 * Android screenshots an activity when it goes to the background and shows that image in Recents. For this
 * app the image is a list of book titles — private self-hosted data under 14.5 — visible to anyone who
 * picks the phone up, with no passcode prompt in the way. `docs/gaps.md` recorded it as checked rather than
 * assumed: nothing in the tree suppressed it on any API level.
 *
 * ### Why not `FLAG_SECURE`
 *
 * It is the obvious answer and the owner declined it, correctly. `FLAG_SECURE` blocks the Recents thumbnail
 * *and* every screenshot and screen recording, for everybody, permanently. That costs the user the ability
 * to send a screenshot of a bug to this project, and it interferes with casting and with some
 * accessibility tooling — a large tax on all users to solve a narrow problem for some.
 *
 * `setRecentsScreenshotEnabled(false)` is the narrow control: it suppresses exactly the Recents image and
 * leaves deliberate screenshots working. It arrived in API 33, which is why this is a decision function
 * rather than an unconditional call.
 *
 * ### What happens below API 33
 *
 * Nothing, and that is the decision rather than an oversight. `minSdk` is 26, so API 26–32 keep the
 * thumbnail; the only lever there is `FLAG_SECURE`, which ADR-0026 declined. ADR-0026 also records where
 * the stronger protection belongs if it is ever wanted — tied to a locked profile, or offered as a privacy
 * setting — so an older device gets the choice rather than a blanket policy chosen for it.
 *
 * The split is stated here, in one place, so nobody later "finishes the job" by adding `FLAG_SECURE`
 * unconditionally and quietly reversing a decision that was taken on purpose.
 */
internal object RecentsPrivacy {

    /**
     * Whether *this* device can suppress the Recents thumbnail — the gate `MainActivity` calls.
     *
     * `@ChecksSdkIntAtLeast` is what makes the call site legal. Android Lint's `NewApi` check understands a
     * literal `Build.VERSION.SDK_INT >= …` comparison and nothing else, so a guard behind a helper reads to
     * it as an unguarded call to an API-33 method — which it flagged, correctly, on the first attempt at
     * this. The annotation tells Lint that a `true` return means the platform is at least API 33, and it is
     * truthful precisely because this function reads `SDK_INT` itself rather than taking it as an argument.
     *
     * The rule it delegates to is [canSuppressThumbnail], which is where the comparison actually lives so
     * that a JVM test can reach it. Two functions rather than one because the tested rule has to be the
     * rule that runs (`docs/risks.md` R-43): this is not a second implementation, it is the same one with
     * the platform read attached.
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    fun isSupported(): Boolean = canSuppressThumbnail(Build.VERSION.SDK_INT)

    /**
     * Whether an Android version of [sdkInt] can suppress the Recents thumbnail on its own.
     *
     * Takes the level as a parameter so the rule is reachable from a JVM test — the same shape as
     * `ControllerTrust.accessFor`, and for the same reason.
     */
    fun canSuppressThumbnail(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU
}
