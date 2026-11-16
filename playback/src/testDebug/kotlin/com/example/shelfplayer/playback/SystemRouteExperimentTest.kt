package com.example.shelfplayer.playback

import android.media.AudioManager
import com.example.shelfplayer.core.testing.TestAppClock
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class SystemRouteExperimentTest {
    private val port = FakePort()
    private val clock = TestAppClock()
    private val evidence = mutableListOf<String>()
    private val experiment = SystemRouteExperiment(port, clock) { status, alias, elapsed ->
        evidence += "$status $alias $elapsed"
    }

    @Test
    fun openingOnlyObservesAndTransferRequiresFreshEligibleRouteAndNormalMode() {
        experiment.open()
        assertTrue(port.requests.isEmpty())
        experiment.transfer("missing")
        assertTrue(port.requests.isEmpty())
        port.mode = AudioManager.MODE_IN_COMMUNICATION
        experiment.transfer("r1")
        assertTrue(port.requests.isEmpty())
        port.mode = 0
        port.routes = emptyList()
        experiment.transfer("r1")
        assertTrue(port.requests.isEmpty())
    }

    @Test
    fun requestIsSingleUseAndOnlyMatchingCallbackConfirmsSystemSelection() {
        experiment.open()
        experiment.transfer("r1")
        experiment.transfer("r1")
        assertEquals(listOf("private-id"), port.requests)
        clock.advanceBy(350.milliseconds)
        port.complete("other", SystemRouteStatus.Selected)
        assertEquals(SystemRouteStatus.Requested, experiment.state.value.status)
        port.complete("private-id", SystemRouteStatus.Selected)
        assertEquals(SystemRouteStatus.Selected, experiment.state.value.status)
        assertTrue(evidence.last().endsWith("350"))
        assertFalse(evidence.toString().contains("private"))
    }

    @Test
    fun timeoutCannotBecomeSuccessAndClosedSessionCallbacksAreIgnored() {
        experiment.open()
        val old = port.complete
        experiment.transfer("r1")
        port.complete("private-id", SystemRouteStatus.TimedOut)
        port.complete("private-id", SystemRouteStatus.Selected)
        assertEquals(SystemRouteStatus.TimedOut, experiment.state.value.status)
        experiment.close()
        experiment.open()
        old("private-id", SystemRouteStatus.Selected)
        assertEquals(SystemRouteStatus.Observing, experiment.state.value.status)
        assertEquals(1, port.closes)
    }

    @Test
    fun permissionDenialAndRevocationNeverIssueTransfer() {
        port.availability = SystemRouteStatus.PermissionRequired
        experiment.open()
        assertEquals(0, port.opens)
        port.availability = SystemRouteStatus.Observing
        experiment.open()
        port.availability = SystemRouteStatus.PermissionRequired
        experiment.transfer("r1")
        assertTrue(port.requests.isEmpty())
        assertEquals(SystemRouteStatus.PermissionRequired, experiment.state.value.status)
    }

    @Test
    fun speakerRemoteAndAlreadySelectedRoutesCannotBeRequested() {
        port.routes += listOf(
            SystemRouteCandidate("speaker", "private-speaker", false, true, false),
            SystemRouteCandidate("remote", "private-remote", true, false, false),
            SystemRouteCandidate("selected", "private-selected", true, true, true),
        )
        experiment.open()
        assertEquals(2, experiment.state.value.routes.size)
        experiment.transfer("r2")
        assertTrue(port.requests.isEmpty())
    }

    private class FakePort : SystemRoutePort {
        var availability = SystemRouteStatus.Observing
        var routes = listOf(SystemRouteCandidate("private-id", "private-name", true, true, false))
        var mode = 0
        var opens = 0
        var closes = 0
        val requests = mutableListOf<String>()
        var complete: (String, SystemRouteStatus) -> Unit = { _, _ -> }
        override fun availability() = availability
        override fun audioMode() = mode
        override fun routes() = routes
        override fun open(changed: () -> Unit, result: (String, SystemRouteStatus) -> Unit) {
            opens++
            complete = result
        }
        override fun transfer(id: String) {
            requests += id
        }
        override fun close() {
            closes++
        }
    }
}
