package com.example.shelfplayer.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NativeRouteDeviceTest {
    @Test
    fun realTrackExportsPreferenceReadbackAndIndependentPlayingRoute() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val journal = CarLabJournal(context)
        val id = journal.begin()
        val samples = NativeRouteSamples()
        val silence = ShortArray(8_000)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(8_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(silence.size * 2).build()
        fun sample() = samples.observe("device-test", 1, { NativeRoutePair.read(track) }, { true })
        try {
            assertEquals(silence.size, track.write(silence, 0, silence.size))
            sample()
            assertEquals(AudioTrack.SUCCESS, track.setLoopPoints(0, silence.size, -1))
            track.play()
            val deadline = SystemClock.elapsedRealtime() + 3_000
            while (track.routedDevice == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            val output = requireNotNull(track.routedDevice)
            sample()
            assertTrue(track.setPreferredDevice(output))
            sample()
            journal.routes(id, samples.drain(), Instant.now())
            val suite = JSONObject(journal.attach("{}")).getJSONObject("playbackTestSuite")
            val runs = suite.getJSONArray("runs")
            val rows = runs.getJSONObject(runs.length() - 1).getJSONArray("nativeRouteSamples")
            assertEquals("default", rows.getJSONObject(0).getJSONObject("preferred").getString("status"))
            assertEquals("not-playing", rows.getJSONObject(0).getJSONObject("actual").getString("status"))
            assertEquals("actual", rows.getJSONObject(1).getString("change"))
            val last = rows.getJSONObject(2)
            assertEquals("preferred", last.getString("change"))
            val preferred = last.getJSONObject("preferred").getJSONArray("outputs").getJSONObject(0)
            assertEquals(output.type, preferred.getInt("androidType"))
            assertEquals(
                preferred.getString("alias"),
                last.getJSONObject("actual").getJSONArray("outputs")
                    .getJSONObject(0).getString("alias"),
            )
        } finally {
            track.release()
            journal.finish("finished")
        }
    }
}
