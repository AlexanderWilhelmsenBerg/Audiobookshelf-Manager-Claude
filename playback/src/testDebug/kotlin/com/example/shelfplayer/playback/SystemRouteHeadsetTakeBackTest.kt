package com.example.shelfplayer.playback

import android.media.AudioManager
import com.example.shelfplayer.playback.SystemRouteTakeBackFixture.Companion.CAR
import com.example.shelfplayer.playback.SystemRouteTakeBackFixture.Companion.HEADSET
import com.example.shelfplayer.playback.SystemRouteTakeBackFixture.Companion.route
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** PD-009 experiment: only the route associated with measured headset playback may be taken back. */
class SystemRouteHeadsetTakeBackTest {
    private val f = SystemRouteTakeBackFixture()

    @Test
    fun displacedHeadsetRouteIsRequestedAndMatchingSelectionReportsDuration() {
        f.verify()
        f.port.carTakesOver()

        f.displace()
        assertEquals(listOf(HEADSET), f.port.requests)
        f.clock.advanceBy(600.milliseconds)
        f.port.result(CAR, SystemRouteStatus.Selected)
        assertEquals(listOf("Requested"), f.outcomes, "another route's selection is not this request's outcome")
        f.port.result(HEADSET, SystemRouteStatus.Selected)

        assertEquals(listOf("Requested", "Selected"), f.outcomes)
        assertTrue(f.rendered.last().contains("600"))
        assertFalse(f.rendered.joinToString().contains("private"), "route ids never reach the log")
    }

    @Test
    fun lateTimeoutAfterSelectionIsIgnored() {
        f.verify()
        f.port.carTakesOver()
        f.displace()
        f.port.result(HEADSET, SystemRouteStatus.Selected)

        f.port.result(HEADSET, SystemRouteStatus.TimedOut)

        assertEquals(listOf("Requested", "Selected"), f.outcomes)
    }

    @Test
    fun nothingIsRequestedWithoutRememberedHeadsetRoute() {
        f.port.carTakesOver()

        f.displace()

        assertTrue(f.port.requests.isEmpty())
        assertEquals(listOf("HeadsetRouteUnknown"), f.outcomes)
    }

    @Test
    fun selectedOrUnlistedHeadsetIsLeftAlone() {
        f.verify()
        f.displace()
        f.port.routes = listOf(route(CAR, selected = true))
        f.displace()

        assertTrue(f.port.requests.isEmpty())
        assertEquals(listOf("AlreadySelected", "HeadsetRouteAbsent"), f.outcomes)
    }

    @Test
    fun callOrMissingPermissionBlocksRequest() {
        f.verify()
        f.port.carTakesOver()
        f.port.mode = AudioManager.MODE_IN_CALL
        f.displace()
        f.port.mode = AudioManager.MODE_NORMAL
        f.takeBack.close()
        f.port.status = SystemRouteStatus.PermissionRequired
        f.displace()

        assertTrue(f.port.requests.isEmpty())
        assertEquals(listOf("CallBlocked", "PermissionRequired"), f.outcomes)
    }

    @Test
    fun routesDiscoveredAfterVerificationAreRememberedUntilDisplacement() {
        f.port.routes = emptyList()
        f.verify()
        f.port.routes = listOf(route(HEADSET, selected = true))
        f.port.changed()
        f.port.carTakesOver()
        f.port.changed()

        f.displace()

        assertEquals(listOf(HEADSET), f.port.requests)
    }

    @Test
    fun carSelectedDuringDiscoveryCannotBecomeHeadsetAfterDisplacement() {
        f.port.routes = emptyList()
        f.verify()
        f.displace()
        f.port.carTakesOver()
        f.port.changed()
        f.displace()

        assertTrue(f.port.requests.isEmpty())
        assertEquals(listOf("HeadsetRouteUnknown", "HeadsetRouteUnknown"), f.outcomes)
    }

    @Test
    fun twoSelectedBluetoothRoutesAreTooAmbiguousToRemember() {
        f.port.routes = listOf(route(HEADSET, selected = true), route(CAR, selected = true))
        f.verify()
        f.port.carTakesOver()

        f.displace()

        assertTrue(f.port.requests.isEmpty())
    }

    @Test
    fun routerFailureIsReportedAndNothingStaysPending() {
        f.verify()
        f.port.carTakesOver()
        f.port.failTransfer = true

        f.displace()
        f.port.result(HEADSET, SystemRouteStatus.Selected)

        assertEquals(listOf("Requested", "Unavailable"), f.outcomes)
    }

    @Test
    fun staleSelectedRouteWaitsForTheSystemSnapshotToCatchUp() {
        f.verify()
        f.displace()
        assertTrue(f.port.requests.isEmpty())

        f.port.carTakesOver()
        f.port.changed()
        f.port.changed()

        assertEquals(listOf(HEADSET), f.port.requests)
    }

    @Test
    fun absentRememberedRouteWaitsForDiscoveryInsteadOfGivingUp() {
        f.verify()
        f.port.routes = listOf(route(CAR, selected = true))
        f.displace()
        f.port.carTakesOver()

        f.port.changed()

        assertEquals(listOf(HEADSET), f.port.requests)
    }

    @Test
    fun repeatedHeadsetProofCannotReplaceTheRememberedSystemRouteWithTheCar() {
        f.verify()
        f.port.carTakesOver()
        f.verify()

        f.displace()

        assertEquals(listOf(HEADSET), f.port.requests)
    }
}
