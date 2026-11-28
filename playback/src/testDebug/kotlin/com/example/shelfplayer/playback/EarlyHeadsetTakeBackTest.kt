package com.example.shelfplayer.playback

import android.media.AudioManager
import com.example.shelfplayer.playback.SystemRouteTakeBackFixture.Companion.HEADSET
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class EarlyHeadsetTakeBackTest {
    private val f = SystemRouteTakeBackFixture()
    private var moved = false

    private fun arm() {
        f.earlyEnabled = true
        f.verify()
        f.startLink()
        f.takeBack.armEarly(
            EarlyHeadsetArrival(HEADSET, requireNotNull(f.window.takeBackScope()), {
                f.current
            }, { moved }),
        )
    }

    @Test
    fun transferRequiresBothAnOfferedRouteAndDisplacementInEitherOrder() {
        arm()
        f.port.carTakesOver()
        f.port.changed()
        assertEquals(listOf("Armed"), f.outcomes)
        f.port.transferable = setOf(HEADSET)
        f.port.changed()
        f.verify()
        assertEquals(listOf("Armed", "Transferable"), f.outcomes)
        f.takeBack.onHeadsetStream(false)
        f.displace()
        f.port.changed()
        f.port.result(HEADSET, SystemRouteStatus.Selected)
        f.verify()
        f.takeBack.onHeadsetStream(true)
        f.takeBack.onHeadsetStream(true)
        assertEquals(listOf(HEADSET), f.port.requests)
        assertEquals(
            listOf("Armed", "Transferable", "StreamStopped", "Requested", "Selected", "StreamReturned"),
            f.outcomes,
        )
    }

    @Test
    fun trackReturnAndBluetoothStreamReturnAreSeparateEvidence() {
        arm()
        f.takeBack.earlyTrackLost()
        f.takeBack.onHeadsetStream(false)
        f.port.transferable = setOf(HEADSET)
        f.port.changed()
        assertTrue(f.port.requests.isEmpty())
        f.port.carTakesOver()
        f.port.changed()
        f.verify()
        f.port.result(HEADSET, SystemRouteStatus.Selected)
        f.takeBack.onHeadsetStream(true)
        assertEquals(
            listOf(
                "Armed",
                "StreamStopped",
                "Transferable",
                "Requested",
                "TrackReturned",
                "Selected",
                "StreamReturned",
            ),
            f.outcomes,
        )
    }

    @Test
    fun aPlayingBroadcastAfterMeasuredDisplacementCanProveReturnEvenWhenAndroidOmitsStopped() {
        arm()
        f.port.carTakesOver()
        f.port.transferable = setOf(HEADSET)
        f.port.changed()
        assertTrue(f.port.requests.isEmpty())
        moved = true
        f.takeBack.onEarlyTrackRouteChanged()
        f.takeBack.onHeadsetStream(true)
        f.takeBack.onHeadsetStream(true)
        assertEquals(listOf("Armed", "Transferable", "Requested", "StreamReturned"), f.outcomes)
    }

    @Test
    fun revokedExpiredOrCallingContextsCannotIssueAnEarlyTransfer() {
        for (reason in listOf("listener", "expired", "call", "permission", "disconnected")) {
            arm()
            when (reason) {
                "listener" -> f.takeBack.cancelPending()
                "expired" -> f.clock.advanceBy(31.seconds)
                "call" -> f.port.mode = AudioManager.MODE_IN_CALL
                "permission" -> f.port.status = SystemRouteStatus.PermissionRequired
                else -> f.window.onHandsFreeDisconnected("car-link")
            }
            f.port.carTakesOver()
            f.port.transferable = setOf(HEADSET)
            moved = true
            f.port.changed()
            assertTrue(f.port.requests.isEmpty(), reason)
            f.port.mode = AudioManager.MODE_NORMAL
            f.port.status = SystemRouteStatus.Observing
            f.window.close()
            f.port.routes = listOf(SystemRouteTakeBackFixture.route(HEADSET, selected = true))
        }
    }
}
