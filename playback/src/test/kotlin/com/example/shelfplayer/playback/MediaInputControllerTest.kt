package com.example.shelfplayer.playback

import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MediaInputControllerTest {
    @Test
    fun `real controller metadata categorizes software callers without exporting their package`() {
        val carPackages = setOf("com.google.android.projection.gearhead")
        val examples = mapOf(
            "com.google.android.projection.gearhead" to MediaInputController.Caller.AndroidAuto,
            "com.android.bluetooth" to MediaInputController.Caller.BluetoothService,
            "android.media.session.MediaController" to MediaInputController.Caller.Legacy,
            "com.android.systemui" to MediaInputController.Caller.System,
            "private.oem.controller" to MediaInputController.Caller.External,
        )
        examples.forEach { (name, expected) ->
            val info = controller(name)
            val facts = MediaInputController.capture(info, -99, carPackages)
            assertEquals(expected, facts.caller)
            assertEquals(1, facts.version)
            assertEquals(1, facts.interfaceVersion)
            assertContains(facts.fields(), "packageVerified=false")
            assertFalse(name in facts.fields())
        }
        val own = controller("private.app")
        assertEquals(
            MediaInputController.Caller.SameApp,
            MediaInputController.capture(own, own.uid, carPackages).caller,
        )
        assertEquals(MediaInputController(), MediaInputController.capture(null, -99, emptySet()))
    }

    private fun controller(name: String): MediaSession.ControllerInfo =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(name, 0, 0, 1, 1, false, Bundle.EMPTY, false)
}
