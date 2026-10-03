package com.example.shelfplayer.core.model.download

import org.junit.Test
import kotlin.test.assertEquals

/** BW-DL-08 — a running transfer must never read as 100 per cent. */
class DownloadProgressTest {

    @Test
    fun `percent floors the fraction and guards float error`() {
        val expected = mapOf(0.25f to 25, 0.42f to 42, 0.999f to 99, 1f to 100, -1f to 0, 2f to 100)

        expected.forEach { (fraction, percent) ->
            assertEquals(percent, DownloadProgress(0, null, fraction).percent, "fraction $fraction")
        }
    }

    @Test
    fun `in flight percent never reaches 100`() {
        assertEquals(99, DownloadProgress(0, null, 1f).inFlightPercent)
        assertEquals(42, DownloadProgress(0, null, 0.42f).inFlightPercent)
    }
}
