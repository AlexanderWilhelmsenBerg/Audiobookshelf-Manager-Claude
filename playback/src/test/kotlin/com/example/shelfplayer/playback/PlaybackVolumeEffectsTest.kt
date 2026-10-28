package com.example.shelfplayer.playback

import androidx.media3.common.Player
import com.example.shelfplayer.core.testing.TestAppClock
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackVolumeEffectsTest {

    @Test
    fun `retired probe cleanup restores its own latest gain and cannot touch successor`() {
        val owner = PlaybackVolumeEffects(TestAppClock())
        val old = MutablePlayer()
        val successor = MutablePlayer()
        val events = mutableListOf<Boolean>()
        owner.addMuteListener { events += it.muted }
        owner.attach(old.player)
        owner.setSleepGain(old.player, 0.4f)
        val claim = requireNotNull(owner.acquireRouteMute(old.player))
        owner.attach(successor.player)
        owner.setSleepGain(successor.player, 0.7f)

        assertTrue(owner.releaseRouteMute(claim))

        assertEquals(0.4f, old.volume)
        assertEquals(0.7f, successor.volume)
        assertEquals(listOf(true), events, "retiring a muted player is not an audible resume")
    }

    @Test
    fun `late predecessor detach retires its claim without unbinding successor`() {
        val owner = PlaybackVolumeEffects(TestAppClock())
        val old = MutablePlayer()
        val successor = MutablePlayer()
        owner.attach(old.player)
        val claim = requireNotNull(owner.acquireRouteMute(old.player))
        owner.attach(successor.player)

        owner.detach(old.player)
        owner.setSleepGain(successor.player, 0.6f)

        assertFalse(owner.releaseRouteMute(claim), "explicitly retired claims cannot affect disposed players")
        assertEquals(0f, old.volume)
        assertEquals(0.6f, successor.volume)
        assertFalse(owner.isRouteMuted(successor.player))
        assertFalse(owner.releaseRouteMute(null))
    }

    private class MutablePlayer {
        var volume = 1f
        val player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getVolume" -> volume

                "setVolume" -> {
                    volume = args!![0] as Float
                    null
                }

                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    java.lang.Double.TYPE -> 0.0
                    else -> null
                }
            }
        } as Player
    }
}
