package com.example.shelfplayer.playback

import android.os.Handler
import android.os.Looper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioTrackTimingCaptureTest {
    @Test
    fun `recording off avoids native queries and stopping prevents further samples`() {
        val fixture = Fixture()
        fixture.enabled = false
        fixture.request()
        fixture.advance(1_000)
        assertEquals(0, fixture.reads)
        fixture.enabled = true
        fixture.request()
        fixture.advance(250)
        assertEquals(2, fixture.reads)
        fixture.enabled = false
        fixture.advance(5_000)
        assertEquals(2, fixture.reads)
    }

    @Test
    fun `callback storm cannot extend burst or restart inside cooldown`() {
        val fixture = Fixture()
        repeat(20) {
            fixture.request()
            fixture.advance(200)
        }
        assertEquals(16, fixture.reads)
        fixture.request()
        fixture.advance(900)
        assertEquals(16, fixture.reads)
        fixture.advance(100)
        fixture.request()
        fixture.advance(0)
        assertEquals(17, fixture.reads)
    }

    @Test
    fun `replacement during native query discards its sample and retired release cannot cancel successor`() {
        val fixture = Fixture()
        fixture.onRead = { fixture.epoch++ }
        fixture.request()
        fixture.advance(0)
        assertTrue(fixture.records.isEmpty())
        fixture.onRead = {}
        fixture.request()
        fixture.capture.retire(1)
        fixture.advance(250)
        assertEquals(listOf(2L, 2L), fixture.records.map { it.observerEpoch })
        fixture.capture.retire(2)
        fixture.advance(5_000)
        assertEquals(2, fixture.records.size)
    }

    @Test
    fun `unavailable timestamp remains unknown and export contains only numeric fields`() {
        val fixture = Fixture()
        fixture.request()
        fixture.advance(0)
        val evidence = fixture.records.single().evidence()
        assertTrue(evidence.contains("timestampFrames=null timestampNs=null"))
        assertTrue(
            evidence.split(' ').drop(1).all {
                it.substringAfter('=') == "null" ||
                    it.substringAfter('=').toLongOrNull() != null
            },
        )
    }

    private class Fixture {
        var enabled = true
        var epoch = 1L
        var reads = 0
        var onRead: () -> Unit = {}
        val records = mutableListOf<AudioTrackTimingSample>()
        val capture =
            AudioTrackTimingCapture(Handler(Looper.getMainLooper()), {
                enabled
            }, records::add, android.os.SystemClock::uptimeMillis)

        fun request() {
            val token = epoch
            capture.request(token, { epoch == token }) {
                reads++
                onRead()
                AudioTrackTimingSample(token, 1L, 3, 48_000, 2, 2, 4_096, 0L, null, null, 0, 1_000, 1_000)
            }
        }

        fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }
}
