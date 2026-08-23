package com.example.shelfplayer.download

import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import com.example.shelfplayer.core.model.download.NetworkPolicy
import com.example.shelfplayer.core.model.download.TrafficCategory
import org.junit.Test
import kotlin.test.assertEquals

/**
 * PRODUCT_SPEC DL-004 — which network a queued download may use, and when a tap may overrule a sweep.
 *
 * ### The defect these cover
 *
 * `WorkManagerDownloadScheduler.enqueue` asked `allowsCellular(TrafficCategory.ManualDownload)` whoever had
 * called it. `NetworkPolicy` keeps `smartDownloadsOnCellular` separate from `downloadsOnCellular` and says
 * why at length — *"a manual download is a decision the user just made and a smart one is the app deciding
 * for them"* — so the one setting that protects a listener from the app spending their data unasked was
 * stored, shown in Settings, and read by nothing.
 *
 * It went unnoticed because both defaults are `false`: the two settings agreed until somebody changed one.
 */
class DownloadWorkPolicyTest {

    // --- the constraint ------------------------------------------------------------------------------

    /** The defaults: streaming may use cellular, neither kind of download may. */
    @Test
    fun `by default every download waits for an unmetered network`() {
        val policy = NetworkPolicy.Default

        assertEquals(NetworkType.UNMETERED, networkFor(policy, TrafficCategory.ManualDownload))
        assertEquals(NetworkType.UNMETERED, networkFor(policy, TrafficCategory.SmartDownload))
    }

    /**
     * **The defect, stated as a test.** Allowing cellular for manual downloads must not allow it for smart
     * ones.
     *
     * This is the configuration the old code got wrong, and it is the one a listener would actually choose:
     * "download what I ask for wherever I am, but do not go fetching things on your own".
     */
    @Test
    fun `allowing cellular for manual downloads leaves smart downloads on Wi-Fi`() {
        val policy = NetworkPolicy(downloadsOnCellular = true, smartDownloadsOnCellular = false)

        assertEquals(NetworkType.CONNECTED, networkFor(policy, TrafficCategory.ManualDownload))
        assertEquals(NetworkType.UNMETERED, networkFor(policy, TrafficCategory.SmartDownload))
    }

    /** And the reverse, which nothing stops a user configuring even if few would. */
    @Test
    fun `allowing cellular for smart downloads leaves manual ones on Wi-Fi`() {
        val policy = NetworkPolicy(downloadsOnCellular = false, smartDownloadsOnCellular = true)

        assertEquals(NetworkType.UNMETERED, networkFor(policy, TrafficCategory.ManualDownload))
        assertEquals(NetworkType.CONNECTED, networkFor(policy, TrafficCategory.SmartDownload))
    }

    /** Streaming is a category too, and it must not be confused with a download. */
    @Test
    fun `streaming follows its own setting`() {
        val policy = NetworkPolicy(streamingOnCellular = true, downloadsOnCellular = false)

        assertEquals(NetworkType.CONNECTED, networkFor(policy, TrafficCategory.Streaming))
        assertEquals(NetworkType.UNMETERED, networkFor(policy, TrafficCategory.ManualDownload))
    }

    // --- the existing-work policy --------------------------------------------------------------------

    /**
     * **A tap overrules a sweep, but only where it has something to overrule.**
     *
     * Smart download queues a book as `UNMETERED`; the listener then taps *Download* on a train with manual
     * cellular allowed. `KEEP` would hold the stricter job and the tap would do nothing — a button silently
     * obeying a setting the user had already overridden.
     *
     * The narrowness is the design. This is the only configuration in which an existing job can hold a
     * constraint the caller is entitled to relax, so it is the only one that replaces.
     */
    @Test
    fun `a manual tap replaces work a sweep may have queued more strictly`() {
        val policy = NetworkPolicy(downloadsOnCellular = true, smartDownloadsOnCellular = false)

        assertEquals(
            ExistingWorkPolicy.REPLACE,
            existingWorkPolicyFor(policy, TrafficCategory.ManualDownload),
        )
    }

    /**
     * With both settings agreeing there is nothing to relax, so a second tap stays a no-op.
     *
     * This is the default configuration and the common one, which is what keeps the class comment's
     * promise — "a second tap on a book already downloading must not restart it" — true for almost every
     * user almost all of the time.
     */
    @Test
    fun `a manual tap keeps existing work when both settings agree`() {
        for (policy in listOf(
            NetworkPolicy.Default,
            NetworkPolicy(downloadsOnCellular = true, smartDownloadsOnCellular = true),
        )) {
            assertEquals(
                ExistingWorkPolicy.KEEP,
                existingWorkPolicyFor(policy, TrafficCategory.ManualDownload),
                "with matching settings a tap has nothing to relax: $policy",
            )
        }
    }

    /**
     * **A sweep never replaces a tap.** Not the mirror image, deliberately.
     *
     * The listener asked for that book, so their permission is the more permissive of the two; a smart pass
     * narrowing it would stop a download somebody is waiting for. Asserted across every policy so the
     * asymmetry cannot be lost to a later simplification.
     */
    @Test
    fun `an automatic request never replaces existing work`() {
        for (manual in listOf(true, false)) {
            for (smart in listOf(true, false)) {
                val policy = NetworkPolicy(downloadsOnCellular = manual, smartDownloadsOnCellular = smart)
                assertEquals(
                    ExistingWorkPolicy.KEEP,
                    existingWorkPolicyFor(policy, TrafficCategory.SmartDownload),
                    "a sweep must never replace a listener's own download: $policy",
                )
            }
        }
    }
}
