package com.example.shelfplayer.playback

import android.view.KeyEvent
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ButtonPlayRequestsTest {
    private val requests = ButtonPlayRequests()
    private val scope = ButtonPlayScope(MutedPositionEffects.Context(1, 2, 3), 4, 5)
    private val bluetooth = ButtonController("com.android.bluetooth", 1002)
    private val google = ButtonController("com.google.android.googlequicksearchbox", 11001)
    private val auto = ButtonController("auto", 11002)
    private val phone = ButtonController("phone", 11003)
    private val a = setOf("a")
    private val both = setOf("a", "b")

    @Test
    fun `Bluetooth Play can hand off to the only headset even through the synchronous Media3 relay`() {
        key(bluetooth)
        assertEquals(ButtonDestination.Headset("a", false), play(phone, relay = true))
        assertNull(play(phone, relay = true))
    }

    @Test
    fun `notification or unrelated phone play cannot borrow old Bluetooth evidence`() {
        val claim = key(bluetooth)
        requests.endDispatch(claim)
        assertNull(play(phone, relay = true))
        key(bluetooth)
        assertNull(play(phone))
    }

    @Test
    fun `a matching Sony tap selects its exact endpoint among two headsets`() {
        requests.sonyTap("b", scope, 500)
        key(google, both, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        assertEquals(ButtonDestination.Headset("b", true), play(google, ids = both))
        key(google, both, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        assertNull(play(google, ids = both))
    }

    @Test
    fun `Auto wins over ambiguous input and unknown apps cannot claim a headset`() {
        key(bluetooth)
        assertNull(play(auto))
        requests.down(ButtonDown(KeyEvent.KEYCODE_MEDIA_PLAY, auto, true, scope, both, 1000, null))
        assertEquals(ButtonDestination.Car, play(phone, relay = true))
        key(phone)
        assertNull(play(phone))
        key(bluetooth, both)
        assertNull(play(bluetooth, ids = both))
    }

    @Test
    fun `pending evidence cannot survive pause new scope or endpoint replacement`() {
        key(bluetooth)
        assertNull(requests.consume(ButtonCommand(false, bluetooth, false), scope, a, 1010))
        assertNull(play(bluetooth))
        key(bluetooth)
        assertNull(requests.consume(ButtonCommand(true, bluetooth, false), scope.copy(selection = 9), a, 1010))
        key(bluetooth)
        assertEquals(ButtonDestination.Refused, play(bluetooth, ids = setOf("replacement")))
    }

    @Test
    fun `expiry and extra candidates prevent an earlier single-output guess`() {
        key(google)
        assertNull(requests.consume(ButtonCommand(true, google, false), scope, a, 3000))
        key(bluetooth)
        assertEquals(ButtonDestination.Refused, play(bluetooth, ids = both))
    }

    private fun key(caller: ButtonController, ids: Set<String> = a, code: Int = KeyEvent.KEYCODE_MEDIA_PLAY) =
        requests.down(ButtonDown(code, caller, false, scope, ids, 1000, ids.singleOrNull()))

    private fun play(caller: ButtonController, relay: Boolean = false, ids: Set<String> = a) =
        requests.consume(ButtonCommand(true, caller, relay), scope, ids, 1010)
}
