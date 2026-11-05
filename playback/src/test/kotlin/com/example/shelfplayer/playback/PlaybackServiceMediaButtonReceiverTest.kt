package com.example.shelfplayer.playback

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.example.shelfplayer.core.common.log.EventLog
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.DeviceKind
import com.example.shelfplayer.core.testing.TestAppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceMediaButtonReceiverTest {
    @Test
    fun `receiver start is captured before cold suppression and capture never changes the rejection`() {
        val fixture = Fixture()
        try {
            fixture.lab.start()
            assertNull(fixture.service.onGetSession(fixture.receiverInfo(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
            val key = fixture.lab.state.value.entries.single { "input-key" in it }
            assertTrue("entry=ReceiverStart" in key)
            assertTrue("keyInputId=-1" in key)
            assertTrue("association=Unproven" in key)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `cold virtual toggle rejects receiver start once and permits a toggle after service recreation`() {
        val gate = ColdStartToggleGate()
        val fixture = Fixture(gate)
        try {
            val result = fixture.service.onGetSession(fixture.receiverInfo(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))

            assertNull(result, "Media3 must take its safe stop path instead of leaving the receiver FGS pending")
        } finally {
            fixture.close()
        }

        val restartedService = Fixture(gate)
        try {
            assertSame(
                restartedService.session,
                restartedService.service.onGetSession(
                    restartedService.receiverInfo(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
                ),
                "a consumed event must not restart the three-second suppression window",
            )
        } finally {
            restartedService.close()
        }
    }

    @Test
    fun `a new Bluetooth appearance is protected after a suppressed service was recreated`() {
        val gate = ColdStartToggleGate()
        val first = Fixture(gate)
        try {
            assertNull(first.service.onGetSession(first.receiverInfo(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
        } finally {
            first.close()
        }
        val reconnected = Fixture(gate, outputId = "bluetooth:new-appearance")
        try {
            assertNull(
                reconnected.service.onGetSession(reconnected.receiverInfo(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)),
                "a later connection must retain cold-start autoplay protection in the same process",
            )
        } finally {
            reconnected.close()
        }
    }

    @Test
    fun `explicit Play from receiver keeps cold playback resumption reachable`() {
        val fixture = Fixture()
        try {
            assertSame(fixture.session, fixture.service.onGetSession(fixture.receiverInfo(KeyEvent.KEYCODE_MEDIA_PLAY)))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `car-connected cold toggle keeps receiver resumption reachable`() {
        val fixture = Fixture()
        try {
            fixture.carConnections.onConnected()

            assertSame(
                fixture.session,
                fixture.service.onGetSession(fixture.receiverInfo(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `ordinary controller session lookup is unaffected`() {
        val fixture = Fixture()
        try {
            val ordinaryController = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
                "example.controller",
                1,
                1,
                1,
                1,
                false,
                Bundle(),
                true,
            )

            assertSame(fixture.session, fixture.service.onGetSession(ordinaryController))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `Media3 safely finishes a receiver foreground start when session lookup rejects it`() {
        val fixture = Fixture()
        val controller = Robolectric.buildService(RejectingMediaButtonService::class.java).create()
        try {
            val service = controller.get().apply { sessionLookup = fixture.service::onGetSession }
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(
                Intent.EXTRA_KEY_EVENT,
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
            )

            service.onStartCommand(intent, 0, 1)

            val shadow = shadowOf(service)
            val receiverInfo = assertNotNull(service.receivedControllerInfo)
            assertEquals(
                Intent.ACTION_MEDIA_BUTTON,
                receiverInfo.connectionHints.getString(MediaLibraryService.CONNECTION_HINT_KEY_CONTROLLER_INFO_TYPE),
            )
            assertNull(service.receivedSession, "the production service gate must reject the event")
            assertTrue(shadow.isStoppedBySelf, "Media3 should stop the service after rejecting its start")
        } finally {
            controller.destroy()
            fixture.close()
        }
    }

    class RejectingMediaButtonService : MediaLibraryService() {
        var sessionLookup: ((MediaSession.ControllerInfo) -> MediaLibrarySession?)? = null
        var receivedControllerInfo: MediaSession.ControllerInfo? = null
        var receivedSession: MediaLibrarySession? = null

        override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
            receivedControllerInfo = controllerInfo
            return sessionLookup?.invoke(controllerInfo).also { receivedSession = it }
        }
    }

    private class Fixture(gate: ColdStartToggleGate = ColdStartToggleGate(), outputId: String = "bluetooth:test") :
        AutoCloseable {
        private val context = RuntimeEnvironment.getApplication()
        private val logger = object : Logger {
            override fun log(event: LogEvent) = Unit
        }
        private val clock = TestAppClock()
        private val dispatcher = StandardTestDispatcher()
        private val player = ExoPlayer.Builder(context).build()
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val router = AudioOutputRouter(
            context,
            logger,
            scope,
            dispatcher,
            volumeEffects = volumeEffectsForTest(dispatcher, clock),
        )
        val service = PlaybackService()
        val lab = AudioRouteLab(context, EventLog(clock), clock, router, scope, dispatcher)
        val carConnections = CarConnections(clock)
        val session = MediaLibraryService.MediaLibrarySession.Builder(
            context,
            player,
            object : MediaLibraryService.MediaLibrarySession.Callback {},
        ).build()

        init {
            @Suppress("UNCHECKED_CAST")
            (
                router.javaClass.getDeclaredField("_outputs").apply { isAccessible = true }
                    .get(router) as MutableStateFlow<List<AudioOutput>>
                ).value =
                listOf(AudioOutput(outputId, "test", DeviceKind.Bluetooth))
            service.audioOutputs = router
            service.audioRouteLab = lab
            service.clock = clock
            service.carConnections = carConnections
            service.coldStartToggleGate = gate
            service.logger = logger
            service.setField("session", session)
            service.setField("player", player)
        }

        fun receiverInfo(keyCode: Int): MediaSession.ControllerInfo {
            val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val eventExtras = Bundle().apply { putParcelable(Intent.EXTRA_KEY_EVENT, keyEvent) }
            val hints = Bundle().apply {
                putString(MediaLibraryService.CONNECTION_HINT_KEY_CONTROLLER_INFO_TYPE, Intent.ACTION_MEDIA_BUTTON)
                putBundle(MediaLibraryService.CONNECTION_HINT_KEY_INTENT_EXTRAS, eventExtras)
            }
            return MediaSession.ControllerInfo.createTestOnlyControllerInfo(
                "example.receiver",
                0,
                0,
                1,
                1,
                false,
                hints,
                false,
            )
        }

        override fun close() {
            lab.stop()
            session.release()
            player.release()
            scope.cancel()
        }

        private fun Any.setField(name: String, value: Any?) {
            javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
        }
    }
}
