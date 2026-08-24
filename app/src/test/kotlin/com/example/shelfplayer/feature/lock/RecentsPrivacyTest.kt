package com.example.shelfplayer.feature.lock

import android.os.Build
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC AUTH-005 / §15 / 14.5, ADR-0026 decision 3 — the Recents thumbnail rule.
 *
 * ### What this can and cannot prove
 *
 * It proves the version rule. It cannot prove that `MainActivity` calls `setRecentsScreenshotEnabled(false)`
 * — the platform offers no getter, so there is nothing to read back, and Robolectric's `ShadowActivity`
 * does not record it either. That residual is the same seam `docs/risks.md` R-43 records and is written
 * down rather than papered over: the boundary is one `if` in `onCreate`, kept to one line for exactly that
 * reason.
 *
 * The rule is worth pinning on its own because the wrong direction is silent. Get the comparison backwards
 * and modern phones keep the thumbnail while nothing anywhere fails.
 */
class RecentsPrivacyTest {

    /** API 33 is where `setRecentsScreenshotEnabled` arrived; the boundary is inclusive. */
    @Test
    fun `the thumbnail can be suppressed from API 33`() {
        assertTrue(RecentsPrivacy.canSuppressThumbnail(Build.VERSION_CODES.TIRAMISU))
    }

    /** And on everything after it. */
    @Test
    fun `newer versions can suppress it too`() {
        assertTrue(RecentsPrivacy.canSuppressThumbnail(Build.VERSION_CODES.TIRAMISU + 1))
        assertTrue(RecentsPrivacy.canSuppressThumbnail(sdkInt = 36))
    }

    /**
     * **The deliberate gap, asserted so it stays deliberate.**
     *
     * API 26–32 keep the thumbnail. The only lever there is `FLAG_SECURE`, which ADR-0026 declined because
     * it would block every screenshot and screen recording for every user. If somebody later decides those
     * versions should be covered, this is the test that makes them change a decision rather than a
     * comparison.
     */
    @Test
    fun `versions below API 33 are left alone`() {
        assertFalse(RecentsPrivacy.canSuppressThumbnail(Build.VERSION_CODES.TIRAMISU - 1))
        // The app's own `minSdk`, which is the oldest device this rule ever sees.
        assertFalse(RecentsPrivacy.canSuppressThumbnail(sdkInt = 26))
    }
}
