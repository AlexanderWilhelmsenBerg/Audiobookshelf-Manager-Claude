package com.example.shelfplayer.playback

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class CarLabEnginesDeviceTest {
    @Test
    fun allFourEnginesAdvanceOnThePackagedFixture() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val file = File.createTempFile("lab-test", ".m4a", context.cacheDir)
        try {
            context.resources.openRawResource(R.raw.car_lab_count).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val pcm = runBlocking { LabPcm.decode(file) }
            val failed = AtomicBoolean(false)
            val factories = listOf<() -> CarLabEngine>(
                { LabExo(context, file) { failed.set(true) } },
                { LabMediaPlayer(file, null) { failed.set(true) } },
                { LabVlc(context, file) { failed.set(true) } },
                { LabAudioTrack(pcm) },
            )
            factories.forEach { factory ->
                lateinit var player: CarLabEngine
                instrument.runOnMainSync { player = factory() }
                try {
                    instrument.runOnMainSync { player.play() }
                    val deadline = SystemClock.elapsedRealtime() + 10_000
                    var progressed = false
                    while (!progressed && !failed.get() && SystemClock.elapsedRealtime() < deadline) {
                        SystemClock.sleep(100)
                        instrument.runOnMainSync { progressed = player.playing && player.positionMs >= 300 }
                    }
                    assertTrue(player.javaClass.simpleName + " should advance the sample", progressed && !failed.get())
                    instrument.runOnMainSync { player.pause() }
                } finally {
                    instrument.runOnMainSync { player.close() }
                }
            }
        } finally {
            file.delete()
        }
    }
}
