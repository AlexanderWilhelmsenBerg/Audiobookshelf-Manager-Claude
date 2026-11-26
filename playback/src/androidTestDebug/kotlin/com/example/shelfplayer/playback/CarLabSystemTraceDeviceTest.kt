package com.example.shelfplayer.playback

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CarLabSystemTraceDeviceTest {
    @Test
    fun testProcessCanSignalAnArmedSessionAndEmitRunMarkers() {
        assumeTrue(File("/system/bin/trigger_perfetto").canExecute())
        val journal = CarLabJournal(InstrumentationRegistry.getInstrumentation().targetContext)
        val id = journal.begin(CarLabCase.BookHold)
        try {
            val sent = CarLabSystemTrace.signal()
            journal.triggerResult(id, sent)
            assertTrue(sent)
            // START_TRACING enables the app trace tag asynchronously on the device.
            SystemClock.sleep(500)
            journal.headsetEvent("headset-armed")
            journal.heard(id, CarLabPhase.Connect, CarLabHeard.Pause)
        } finally {
            journal.finish("finished")
        }
    }
}
