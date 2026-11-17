package com.example.shelfplayer.playback

import android.media.AudioManager
import com.example.shelfplayer.playback.SystemRouteTakeBackFixture.Companion.HEADSET
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class SystemRouteTakeBackCancellationTest {
    private val f = SystemRouteTakeBackFixture()

    @Test
    fun waitingCannotTransferAfterListenerCancellationOrTeardown() {
        for (close in listOf(false, true)) {
            val fixture = SystemRouteTakeBackFixture()
            fixture.verify()
            fixture.displace()
            if (close) fixture.takeBack.close() else fixture.takeBack.cancelPending()

            fixture.port.carTakesOver()
            fixture.port.changed()

            assertTrue(fixture.port.requests.isEmpty())
        }
    }

    @Test
    fun waitingCannotTransferWithNewerPlaybackContext() {
        f.verify()
        f.displace()
        f.current = false
        f.port.carTakesOver()

        f.port.changed()

        assertTrue(f.port.requests.isEmpty())
        assertEquals("Cancelled", f.outcomes.last())
    }

    @Test
    fun waitingCannotOutliveTheOriginalCarDeadlineOrFollowAReplacementLink() {
        for (replace in listOf(false, true)) {
            val fixture = SystemRouteTakeBackFixture()
            fixture.verify()
            fixture.displace()
            if (replace) fixture.startLink() else fixture.clock.advanceBy(CarBluetoothArrivalWindow.LIFETIME)
            fixture.port.carTakesOver()

            fixture.port.changed()

            assertTrue(fixture.port.requests.isEmpty())
            assertEquals("Cancelled", fixture.outcomes.last())
        }
    }

    @Test
    fun lateCallOrPermissionRevocationBlocksAWaitingTransfer() {
        for (call in listOf(false, true)) {
            val fixture = SystemRouteTakeBackFixture()
            fixture.verify()
            fixture.displace()
            fixture.port.carTakesOver()
            if (call) {
                fixture.port.mode = AudioManager.MODE_RINGTONE
            } else {
                fixture.port.status =
                    SystemRouteStatus.PermissionRequired
            }

            fixture.port.changed()

            assertTrue(fixture.port.requests.isEmpty())
            assertEquals(if (call) "CallBlocked" else "PermissionRequired", fixture.outcomes.last())
        }
    }

    @Test
    fun duplicateCallbacksAndFailureCannotMintAnotherTransfer() {
        f.verify()
        f.displace()
        f.port.carTakesOver()
        f.port.changed()
        f.port.changed()
        f.displace()
        f.port.result(HEADSET, SystemRouteStatus.Failed)
        f.port.changed()
        f.displace()

        assertEquals(listOf(HEADSET), f.port.requests)
        assertEquals(listOf("AlreadySelected", "Requested", "Failed"), f.outcomes)
    }

    @Test
    fun actualHeadsetReturnEndsWaitingWithoutSpendingTheTransferAllowance() {
        f.verify()
        f.displace()
        f.verify()
        f.port.carTakesOver()
        f.port.changed()
        assertTrue(f.port.requests.isEmpty())

        f.displace()

        assertEquals(listOf(HEADSET), f.port.requests)
    }

    @Test
    fun arrivalBeforeDelayedDiscoveryCannotTeachTheCarAsANewHeadset() {
        f.port.routes = emptyList()
        f.verify()
        f.startLink()
        f.port.carTakesOver()
        f.port.changed()

        f.displace()

        assertTrue(f.port.requests.isEmpty())
        assertEquals(listOf("HeadsetRouteUnknown"), f.outcomes)
    }

    @Test
    fun anotherHeadsetCannotBorrowThePreviousEndpointsAssociation() {
        f.verify()
        f.startLink()
        f.port.carTakesOver()
        f.verify("another-headset")

        f.displace("another-headset")

        assertTrue(f.port.requests.isEmpty())
        assertEquals(listOf("HeadsetRouteUnknown"), f.outcomes)
    }

    @Test
    fun diagnosticAliasesDistinguishStaleSelectionWithoutLeakingIdentifiers() {
        f.verify()
        f.displace()
        assertTrue(f.rendered.last().contains("state=Waiting"))
        assertTrue(f.rendered.last().contains("targetRouteAlias=1"))
        assertTrue(f.rendered.last().contains("selectedRouteAliases=1"))
        f.clock.advanceBy(400.milliseconds)
        f.port.carTakesOver()
        f.port.changed()

        assertTrue(f.rendered.last().contains("selectedRouteAliases=2"))
        assertFalse(f.rendered.joinToString().contains("private"))
    }
}
