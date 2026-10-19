package com.example.shelfplayer.playback

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ROUTE-002: a normalized name is never proof of one physical Bluetooth output. */
class LiveOutputIdentityTest {
    private fun handle(platformId: Int, logicalId: String) = LiveOutputIdentity.Handle(platformId, logicalId)

    @Test
    fun `distinct headphones remain independently selectable`() {
        val snapshot = LiveOutputIdentity.snapshot(
            listOf(handle(12, "bluetooth:alpha"), handle(14, "bluetooth:beta")),
        )
        assertEquals(12, snapshot.uniqueHandleByLogicalId["bluetooth:alpha"])
        assertEquals(14, snapshot.uniqueHandleByLogicalId["bluetooth:beta"])
        assertTrue(snapshot.ambiguousLogicalIds.isEmpty())
        assertEquals(setOf("bluetooth:alpha"), snapshot.safePredictedRoutes(setOf("bluetooth:alpha")))
    }

    @Test
    fun `two live handles sharing a name cannot be silently collapsed or routed`() {
        val snapshot = LiveOutputIdentity.snapshot(
            listOf(handle(12, "bluetooth:buds"), handle(19, "bluetooth:buds")),
        )
        assertTrue(snapshot.isAmbiguous("bluetooth:buds"))
        assertNull(snapshot.uniqueHandleByLogicalId["bluetooth:buds"])
        assertNull(snapshot.safePredictedRoutes(setOf("bluetooth:buds")))
        assertEquals(setOf("bluetooth:buds"), snapshot.presentLogicalIds)
    }

    @Test
    fun `duplicate callback for one handle is not another physical output`() {
        val snapshot = LiveOutputIdentity.snapshot(
            listOf(handle(12, "bluetooth:buds"), handle(12, "bluetooth:buds")),
        )
        assertFalse(snapshot.isAmbiguous("bluetooth:buds"))
        assertEquals(12, snapshot.uniqueHandleByLogicalId["bluetooth:buds"])
    }

    @Test
    fun `disappearance of one colliding handle restores unique preference only`() {
        val collision = LiveOutputIdentity.snapshot(
            listOf(handle(12, "bluetooth:buds"), handle(19, "bluetooth:buds")),
        )
        val returned = LiveOutputIdentity.snapshot(listOf(handle(19, "bluetooth:buds")))
        assertTrue(collision.isAmbiguous("bluetooth:buds"))
        assertFalse(returned.isAmbiguous("bluetooth:buds"))
        assertEquals(19, returned.uniqueHandleByLogicalId["bluetooth:buds"])
        assertTrue(returned.presentLogicalIds.contains("bluetooth:buds"))
    }

    @Test
    fun `route for another unique output remains reportable despite collision`() {
        val snapshot = LiveOutputIdentity.snapshot(
            listOf(
                handle(12, "bluetooth:buds"),
                handle(19, "bluetooth:buds"),
                handle(20, "wired"),
            ),
        )
        assertEquals(setOf("wired"), snapshot.safePredictedRoutes(setOf("wired")))
        assertNull(snapshot.safePredictedRoutes(setOf("wired", "bluetooth:buds")))
        assertNull(snapshot.safePredictedRoutes(null))
    }
}
