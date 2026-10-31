package com.example.shelfplayer.playback

import android.view.KeyEvent
import kotlinx.coroutines.CancellationException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MediaInputDiagnosticsTest {
    @Test
    fun `recording off never queries inputs and stop rejects even delayed callbacks or media keys`() {
        val fixture = Fixture()
        fixture.trace.mediaKey(toggle(), MediaInputController(), MediaInputEntry.SessionCallback)
        fixture.trace.transport(true, MediaInputController())
        assertEquals(0, fixture.source.reads)
        assertTrue(fixture.rows.isEmpty())

        fixture.trace.start()
        fixture.trace.start()
        assertEquals(1, fixture.source.callbacks.size)
        val first = fixture.source.callbacks.single()
        fixture.trace.stop()
        val stopped = fixture.rows.toList()
        first(MediaInputChange.Added)
        fixture.trace.mediaKey(toggle(), MediaInputController(), MediaInputEntry.SessionCallback)
        assertEquals(stopped, fixture.rows)
        assertEquals(1, fixture.source.unsubscribed)

        fixture.trace.start()
        val restarted = fixture.rows.toList()
        first(MediaInputChange.Removed)
        assertEquals(restarted, fixture.rows)
        fixture.source.callbacks.last()(MediaInputChange.Changed)
        assertContains(fixture.rows.last(), "reason=Changed")
        assertContains(fixture.rows.last(), "inputCapture=m2")
        fixture.trace.stop()
        assertTrue(fixture.current.all { "inputCapture=m2" in it })
    }

    @Test
    fun `virtual keys and controller identity stay distinct from physical output attribution`() {
        val fixture = Fixture()
        fixture.trace.start()
        fixture.trace.mediaKey(
            toggle(),
            MediaInputController(MediaInputController.Caller.BluetoothService, 0, 0, true),
            MediaInputEntry.SessionCallback,
        )
        val buttonRecord = fixture.rows.single { "input-key" in it }
        assertContains(buttonRecord, "keyInputId=-1")
        assertContains(buttonRecord, "keyInputSource=0")
        assertContains(buttonRecord, "deviceQuery=Missing")
        assertContains(buttonRecord, "caller=BluetoothService")
        assertContains(buttonRecord, "association=Unproven")
        assertFalse("Headset" in buttonRecord)

        fixture.trace.transport(true, MediaInputController(MediaInputController.Caller.AndroidAuto))
        assertContains(fixture.rows.last(), "caller=AndroidAuto")
        assertContains(fixture.rows.last(), "association=Unproven")
        fixture.trace.mediaKey(null, MediaInputController(), MediaInputEntry.ReceiverStart)
        assertContains(fixture.rows.last(), "keyPresent=false")
    }

    @Test
    fun `resolved inputs retain flags and capture-local descriptor correlation without raw metadata`() {
        val fixture = Fixture()
        fixture.source.resolved = fixture.source.devices.single()
        fixture.trace.start()
        val event = KeyEvent(
            100,
            150,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            2,
            0,
            4,
            0,
            KeyEvent.FLAG_LONG_PRESS,
            257,
        )
        fixture.trace.mediaKey(event, MediaInputController(), MediaInputEntry.ReceiverStart)
        val buttonRecord = fixture.rows.single { "input-key" in it }
        assertContains(buttonRecord, "deviceQuery=Resolved")
        assertContains(buttonRecord, "inputAlias=i1")
        assertContains(buttonRecord, "descriptorAlias=s1")
        assertContains(buttonRecord, "repeat=2")
        assertContains(buttonRecord, "longPress=true")
        assertContains(buttonRecord, "heldMillis=50")
        assertFalse("private-descriptor" in fixture.rows.joinToString())
        assertFalse("private-descriptor" in fixture.current.joinToString())
    }

    @Test
    fun `unavailable APIs report capability loss without leaking exception data or requesting permissions`() {
        val fixture = Fixture()
        fixture.source.failure = SecurityException("private-address secret-token")
        fixture.trace.start()
        fixture.trace.mediaKey(toggle(), MediaInputController(), MediaInputEntry.SessionCallback)
        assertContains(fixture.status, "unavailable")
        assertContains(fixture.rows.joinToString(), "deviceQuery=Unavailable")
        assertFalse("secret-token" in fixture.rows.joinToString())
        fixture.trace.stop()
    }

    @Test
    fun `cancellation is propagated instead of converted to an unavailable device`() {
        val fixture = Fixture()
        fixture.trace.start()
        fixture.source.failure = CancellationException("cancel")
        assertFailsWith<CancellationException> {
            fixture.trace.mediaKey(toggle(), MediaInputController(), MediaInputEntry.SessionCallback)
        }
        fixture.source.failure = null
        fixture.trace.stop()
    }

    @Test
    fun `cancelled startup unregisters its observer and does not poison the next capture`() {
        val fixture = Fixture()
        fixture.source.snapshotFailure = CancellationException("cancel")
        assertFailsWith<CancellationException> { fixture.trace.start() }
        assertEquals(1, fixture.source.unsubscribed)
        fixture.source.snapshotFailure = null
        fixture.trace.start()
        assertContains(fixture.rows.last(), "inputCapture=m2")
        fixture.trace.stop()
    }

    private fun toggle() = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)

    private class Fixture {
        val source = Source()
        val rows = mutableListOf<String>()
        var status = ""
        var current = emptyList<String>()
        private var capture = 0
        val trace =
            MediaInputDiagnostics(source, rows::add, { state, devices ->
                status = state
                current = devices
            }, { "m${++capture}" })
    }

    private class Source : MediaInputSource {
        val callbacks = mutableListOf<(MediaInputChange) -> Unit>()
        val devices = listOf(MediaInputDevice(4, 257, 1, false, true, "private-descriptor"))
        var resolved: MediaInputDevice? = null
        var failure: Exception? = null
        var snapshotFailure: Exception? = null
        var reads = 0
        var unsubscribed = 0

        override fun devices(): List<MediaInputDevice> {
            reads++
            snapshotFailure?.let { throw it }
            failure?.let { throw it }
            return devices
        }
        override fun device(event: KeyEvent): MediaInputDevice? {
            reads++
            failure?.let { throw it }
            return resolved
        }
        override fun subscribe(changed: (MediaInputChange) -> Unit): () -> Unit {
            failure?.let { throw it }
            callbacks += changed
            return { unsubscribed++ }
        }
    }
}
