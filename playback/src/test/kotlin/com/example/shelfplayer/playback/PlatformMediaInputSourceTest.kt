package com.example.shelfplayer.playback

import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.shadows.InputDeviceBuilder
import org.robolectric.shadows.ShadowInputManager
import kotlin.test.assertContains
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [PlatformMediaInputSourceTest.InputInventory::class])
class PlatformMediaInputSourceTest {
    @Test
    fun `physical key exposes public device facts without asserting an audio association`() {
        val report = capture(4, external = true)
        assertContains(report, "inputKind=External")
        assertContains(report, "vendorId=123 productId=456")
        assertContains(report, "keyScanCode=164")
        assertContains(report, "inputAlias=i1")
        assertContains(report, "descriptorAlias=s1")
        assertContains(report, "association=Unproven")
        assertFalse("private-device" in report)
    }

    @Test
    fun `virtual input remains virtual even with an external flag and model identifiers`() {
        val report = capture(-1, external = true)
        assertContains(report, "inputKind=Virtual")
        assertContains(report, "association=Unproven")
        assertFalse("inputKind=External" in report)
    }

    @Test
    fun `built in input is distinct from an external accessory`() {
        assertContains(capture(2, external = false), "inputKind=BuiltIn")
    }

    private fun capture(id: Int, external: Boolean): String {
        val device = InputDeviceBuilder.newBuilder().setId(id)
            .setName("private-device-name").setDescriptor("private-device-descriptor")
            .setExternal(external).setVendorId(123).setProductId(456)
            .setSources(InputDevice.SOURCE_KEYBOARD).build()
        InputInventory.devices = mapOf(id to device)
        val rows = mutableListOf<String>()
        val trace = MediaInputDiagnostics(
            PlatformMediaInputSource(RuntimeEnvironment.getApplication()),
            rows::add,
            { _, devices -> rows.addAll(devices) },
        )
        trace.start()
        try {
            trace.mediaKey(
                KeyEvent(100, 150, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0, 0, id, 164),
                MediaInputController(),
                MediaInputEntry.SessionCallback,
            )
        } finally {
            trace.stop()
        }
        return rows.joinToString("\n")
    }

    /** Robolectric's default input inventory is always empty; supply framework devices, not app facts. */
    @Implements(InputManager::class)
    class InputInventory : ShadowInputManager() {
        @Implementation
        public override fun getInputDeviceIds(): IntArray = devices.keys.toIntArray()

        @Implementation
        fun getInputDevice(id: Int): InputDevice? = devices[id]

        companion object {
            var devices: Map<Int, InputDevice> = emptyMap()

            @JvmStatic
            @Resetter
            fun resetInventory() {
                devices = emptyMap()
            }
        }
    }
}
