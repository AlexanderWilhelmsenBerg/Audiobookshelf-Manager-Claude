package com.example.shelfplayer.playback

import android.media.AudioManager
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.testing.TestAppClock
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** PD-009 experiment: only the route selected while the headset was measured may be taken back, once asked. */
class SystemRouteHeadsetTakeBackTest {
    private val port = Port()
    private val clock = TestAppClock()
    private val outcomes = mutableListOf<String>()
    private val rendered = mutableListOf<String>()
    private val takeBack = SystemRouteHeadsetTakeBack(port, clock) { _, fields ->
        outcomes += fields.filterIsInstance<LogField.Public>().single { it.key == "outcome" }.value
        rendered += fields.joinToString(" ")
    }

    @Test
    fun displacedHeadsetRouteIsRequestedAndMatchingSelectionReportsDuration() {
        takeBack.onHeadsetVerified()
        port.carTakesOver()

        takeBack.onHeadsetDisplaced()
        assertEquals(listOf(HEADSET), port.requests)
        clock.advanceBy(600.milliseconds)
        port.result(CAR, SystemRouteStatus.Selected)
        assertEquals(listOf("Requested"), outcomes, "another route's selection is not this request's outcome")
        port.result(HEADSET, SystemRouteStatus.Selected)

        assertEquals(listOf("Requested", "Selected"), outcomes)
        assertTrue(rendered.last().contains("600"))
        assertFalse(rendered.joinToString().contains("private"), "route ids never reach the log")
    }

    @Test
    fun lateTimeoutAfterSelectionIsIgnored() {
        takeBack.onHeadsetVerified()
        port.carTakesOver()
        takeBack.onHeadsetDisplaced()
        port.result(HEADSET, SystemRouteStatus.Selected)

        port.result(HEADSET, SystemRouteStatus.TimedOut)

        assertEquals(listOf("Requested", "Selected"), outcomes)
    }

    @Test
    fun nothingIsRequestedWithoutRememberedHeadsetRoute() {
        port.carTakesOver()

        takeBack.onHeadsetDisplaced()

        assertTrue(port.requests.isEmpty())
        assertEquals(listOf("HeadsetRouteUnknown"), outcomes)
    }

    @Test
    fun selectedOrUnlistedHeadsetIsLeftAlone() {
        takeBack.onHeadsetVerified()
        takeBack.onHeadsetDisplaced()
        port.routes = listOf(route(CAR, selected = true))
        takeBack.onHeadsetDisplaced()

        assertTrue(port.requests.isEmpty())
        assertEquals(listOf("AlreadySelected", "HeadsetRouteAbsent"), outcomes)
    }

    @Test
    fun callOrMissingPermissionBlocksRequest() {
        takeBack.onHeadsetVerified()
        port.carTakesOver()
        port.mode = AudioManager.MODE_IN_CALL
        takeBack.onHeadsetDisplaced()
        port.mode = AudioManager.MODE_NORMAL
        takeBack.close()
        port.status = SystemRouteStatus.PermissionRequired
        takeBack.onHeadsetDisplaced()

        assertTrue(port.requests.isEmpty())
        assertEquals(listOf("CallBlocked", "PermissionRequired"), outcomes)
    }

    @Test
    fun routesDiscoveredAfterVerificationAreRememberedUntilDisplacement() {
        port.routes = emptyList()
        takeBack.onHeadsetVerified()
        port.routes = listOf(route(HEADSET, selected = true))
        port.changed()
        port.carTakesOver()
        port.changed()

        takeBack.onHeadsetDisplaced()

        assertEquals(listOf(HEADSET), port.requests)
    }

    @Test
    fun carSelectedDuringDiscoveryCannotBecomeHeadsetAfterDisplacement() {
        port.routes = emptyList()
        takeBack.onHeadsetVerified()
        takeBack.onHeadsetDisplaced()
        port.carTakesOver()
        port.changed()
        takeBack.onHeadsetDisplaced()

        assertTrue(port.requests.isEmpty())
        assertEquals(listOf("HeadsetRouteUnknown", "HeadsetRouteUnknown"), outcomes)
    }

    @Test
    fun twoSelectedBluetoothRoutesAreTooAmbiguousToRemember() {
        port.routes = listOf(route(HEADSET, selected = true), route(CAR, selected = true))
        takeBack.onHeadsetVerified()
        port.carTakesOver()

        takeBack.onHeadsetDisplaced()

        assertTrue(port.requests.isEmpty())
    }

    @Test
    fun routerFailureIsReportedAndNothingStaysPending() {
        takeBack.onHeadsetVerified()
        port.carTakesOver()
        port.failTransfer = true

        takeBack.onHeadsetDisplaced()
        port.result(HEADSET, SystemRouteStatus.Selected)

        assertEquals(listOf("Unavailable"), outcomes)
    }

    private class Port : SystemRoutePort {
        var status = SystemRouteStatus.Observing
        var mode = AudioManager.MODE_NORMAL
        var routes = listOf(route(HEADSET, selected = true))
        var failTransfer = false
        val requests = mutableListOf<String>()
        var changed: () -> Unit = {}
        var result: (String, SystemRouteStatus) -> Unit = { _, _ -> }

        fun carTakesOver() {
            routes = listOf(route(HEADSET, selected = false), route(CAR, selected = true))
        }

        override fun availability() = status
        override fun audioMode() = mode
        override fun routes() = routes
        override fun open(changed: () -> Unit, result: (String, SystemRouteStatus) -> Unit) {
            this.changed = changed
            this.result = result
        }

        override fun transfer(id: String) {
            check(!failTransfer)
            requests += id
        }

        override fun close() = Unit
    }

    private companion object {
        const val HEADSET = "private-headset"
        const val CAR = "private-car"
        fun route(id: String, selected: Boolean) =
            SystemRouteCandidate(id, "private-name", bluetooth = true, system = true, selected = selected)
    }
}
