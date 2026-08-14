package com.example.shelfplayer.core.model.download

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC DL-004 / ADR-0018 decision 5 — the defaults, and the asymmetry that makes them right.
 *
 * The type is three booleans, so what is worth testing is not the arithmetic but the *product decision*
 * encoded in the defaults. A change that flipped one of them would compile, pass every other test in the
 * repository, and quietly spend somebody's data plan on a book.
 */
class NetworkPolicyTest {

    /**
     * The owner's rule, in one assertion: *"you can't turn off wifi."*
     *
     * Every category runs on an unmetered network whatever the switches say, because there is no switch —
     * Wi-Fi is not on the settings screen at all.
     */
    @Test
    fun `an unmetered network is always allowed, whatever is switched off`() {
        val nothingAllowed = NetworkPolicy(
            streamingOnCellular = false,
            downloadsOnCellular = false,
            smartDownloadsOnCellular = false,
        )

        TrafficCategory.entries.forEach { category ->
            assertTrue(nothingAllowed.allows(category, isUnmetered = true), category.name)
        }
    }

    /**
     * The defaults, and the reason they differ.
     *
     * Streaming a chapter costs a few megabytes and somebody pressing play on a train wants it to work.
     * Downloading a book costs hundreds and is nearly always something they meant to do at home. Smart
     * download is the app deciding for them, which is a different thing again.
     */
    @Test
    fun `by default only streaming may use cellular`() {
        val defaults = NetworkPolicy.Default

        assertTrue(defaults.allows(TrafficCategory.Streaming, isUnmetered = false))
        assertFalse(defaults.allows(TrafficCategory.ManualDownload, isUnmetered = false))
        assertFalse(defaults.allows(TrafficCategory.SmartDownload, isUnmetered = false))
    }

    /**
     * Turning cellular on for downloads does **not** turn it on for smart downloads.
     *
     * They are separate switches because they are separate decisions: a manual download is one the user
     * just made, and a smart one is the app spending their data while their phone is in a pocket. A single
     * switch would make the second a side effect of the first.
     */
    @Test
    fun `manual and smart downloads are separate permissions`() {
        val manualOnly = NetworkPolicy.Default.copy(downloadsOnCellular = true)

        assertTrue(manualOnly.allows(TrafficCategory.ManualDownload, isUnmetered = false))
        assertFalse(manualOnly.allows(TrafficCategory.SmartDownload, isUnmetered = false))
    }

    /** And streaming can be turned off without touching either download switch. */
    @Test
    fun `streaming can be refused cellular on its own`() {
        val frugal = NetworkPolicy.Default.copy(streamingOnCellular = false, downloadsOnCellular = true)

        assertFalse(frugal.allows(TrafficCategory.Streaming, isUnmetered = false))
        assertTrue(frugal.allows(TrafficCategory.ManualDownload, isUnmetered = false))
    }
}
