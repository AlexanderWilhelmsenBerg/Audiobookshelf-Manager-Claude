package com.example.shelfplayer.garmin

import android.annotation.SuppressLint
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GarminSetupPolicyTest {
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
