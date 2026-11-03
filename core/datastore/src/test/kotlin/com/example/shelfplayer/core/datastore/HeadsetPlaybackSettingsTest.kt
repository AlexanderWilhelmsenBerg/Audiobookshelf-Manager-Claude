package com.example.shelfplayer.core.datastore

import androidx.datastore.core.DataStore
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ROUTE-001 / SET-002 — stored defaults and opt-in survive upgrades and a fresh reader. */
class HeadsetPlaybackSettingsTest {
    @Test
    fun `older serialized preferences enable routing and leave Sony detection off`() = runTest {
        // Field 48 belonged to the retired keep_sound_in_headset flag; neither value is the new switch.
        for (retiredValue in byteArrayOf(0, 1)) {
            val older = AppSettings.parseFrom(byteArrayOf(0x80.toByte(), 0x03, retiredValue))
            val settings = source(MemoryStore(older)).playback.first()
            assertTrue(settings.headsetButtonRouting)
            assertFalse(settings.sonyTapDetection)
        }
    }

    @Test
    fun `explicit choices round trip without changing other preferences`() = runTest {
        val store = MemoryStore(AppSettings.newBuilder().setDynamicColor(true).build())
        val writer = source(store)
        writer.setHeadsetButtonRouting(false)
        writer.setSonyTapDetection(true)
        val restored = source(MemoryStore(AppSettings.parseFrom(writer.current().toByteArray())))
        assertFalse(restored.playback.first().headsetButtonRouting)
        assertTrue(restored.playback.first().sonyTapDetection)
        assertTrue(restored.current().dynamicColor)

        writer.setHeadsetButtonRouting(true)
        writer.setSonyTapDetection(false)
        assertTrue(writer.playback.first().headsetButtonRouting)
        assertFalse(writer.playback.first().sonyTapDetection)
        assertTrue(writer.current().dynamicColor)
    }

    private fun source(store: DataStore<AppSettings>) = AppSettingsDataSource(
        dataStore = store,
        logger = object : Logger {
            override fun log(event: LogEvent) = Unit
        },
    )

    private class MemoryStore(initial: AppSettings) : DataStore<AppSettings> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<AppSettings> = state

        override suspend fun updateData(transform: suspend (t: AppSettings) -> AppSettings): AppSettings =
            transform(state.value).also { state.value = it }
    }
}
