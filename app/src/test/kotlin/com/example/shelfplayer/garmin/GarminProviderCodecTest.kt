package com.example.shelfplayer.garmin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GarminProviderCodecTest {
    private fun reply() = mutableMapOf<String, Any>(
        "v" to 1,
        "t" to "inventory",
        "r" to "request",
        "p" to "profile",
        "n" to "nonce",
    )

    @Test fun `scope nonce request and major must match`() {
        assertNotNull(GarminProviderCodec.decode(reply(), "profile", "nonce", "request"))
        for (key in listOf("p", "n", "r")) {
            val value = reply()
            value[key] = "foreign"
            assertNull(GarminProviderCodec.decode(value, "profile", "nonce", "request"))
        }
        val future = reply()
        future["v"] = 2
        assertNull(GarminProviderCodec.decode(future, "profile", "nonce", "request"))
    }

    @Test fun `required progress fields reject missing negative and nonintegral values`() {
        val event = mapOf(
            "id" to "1",
            "p" to "profile",
            "b" to "book",
            "pos" to 12,
            "dur" to 120,
            "at" to 1_800_000_000L,
            "k" to "pause",
            "finished" to false,
        )
        assertNotNull(GarminProviderCodec.event(event))
        assertNull(GarminProviderCodec.event(event - "at"))
        assertNull(GarminProviderCodec.event(event + ("pos" to -1)))
        assertNull(GarminProviderCodec.event(event + ("pos" to 1.5)))
        assertNull(GarminProviderCodec.event(event + ("k" to "phone_snapshot")))
        assertNotNull(GarminProviderCodec.event(event + ("future" to true)))
    }

    @Test fun `download inventory never invents complete from a sent command`() {
        val row = mapOf("b" to "book", "title" to "Example", "state" to "queued", "done" to 0, "total" to 20)
        assertEquals("queued", GarminProviderCodec.inventory(row)?.state)
        assertNull(GarminProviderCodec.inventory(row - "title"))
        assertNull(GarminProviderCodec.inventory(row + ("state" to "sent")))
        assertNull(GarminProviderCodec.inventory(row + ("done" to -1)))
    }
}
