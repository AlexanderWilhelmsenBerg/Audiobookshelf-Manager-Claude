package com.example.shelfplayer.garmin

import android.annotation.SuppressLint
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GarminSetupPolicyTest {
    @Test fun suppliesHttpsForBareHostsWithoutAllowingExplicitHttp() {
        assertEquals("https://example.invalid/sidecar", GarminSetupPolicy.url(" example.invalid/sidecar/ "))
        assertNull(GarminSetupPolicy.url("ftp://example.invalid"))
        assertNull(GarminSetupPolicy.url("//example.invalid"))
    }

    // Deliberately fake .invalid credentials exercise rejection; test-only, never packaged.
    @SuppressLint("AuthLeak")
    @Test
    fun normalizesHttpsSubpathsAndRejectsAmbiguousDestinations() {
        assertEquals(
            "https://example.invalid/watchshelf",
            GarminSetupPolicy.url("  https://example.invalid/watchshelf///  "),
        )
        listOf(
            "http://example.invalid",
            "https://",
            "https://user:pass@example.invalid",
            "https://example.invalid?token=x",
            "https://example.invalid#x",
            "https://example.invalid/../login",
            "https://example.invalid/\\login",
            "https://example.invalid/ space",
        ).forEach {
            assertNull(GarminSetupPolicy.url(it))
        }
    }
}
