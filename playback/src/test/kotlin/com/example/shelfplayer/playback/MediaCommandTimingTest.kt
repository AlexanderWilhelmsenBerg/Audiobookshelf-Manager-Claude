package com.example.shelfplayer.playback

import android.view.KeyEvent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MediaCommandTimingTest {
    @Test
    fun `key delivery and subsequent transport share diagnostic sequence only once`() {
        var uptime = 200L
        val timing = MediaCommandTiming { uptime }
        val key = timing.key(KeyEvent(100, 150, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
        assertTrue("inputSequence=1" in key && "deliveryAgeMs=50" in key)
        uptime = 500
        val transport = timing.transport()
        assertTrue("precedingKey=1" in transport && "sinceKeyReceiptMs=300" in transport)
        assertTrue("correlation=temporal-only" in transport)
        assertTrue("precedingKey=none" in timing.transport())
    }

    @Test
    fun `old missing future and negative time cannot create a recent key correlation`() {
        var uptime = 200L
        val timing = MediaCommandTiming { uptime }
        assertTrue("deliveryAgeMs=unknown" in timing.key(KeyEvent(300, 300, 0, 126, 0)))
        assertTrue("precedingKey=none" in timing.transport())
        timing.key(KeyEvent(100, 100, 0, 126, 0))
        uptime = 1500
        assertTrue("precedingKey=none" in timing.transport())
        timing.key(null)
        assertTrue("precedingKey=none" in timing.transport())
        assertTrue("deliveryAgeMs=unknown" in timing.key(KeyEvent(-1, -1, 0, 126, 0)))
    }
}
