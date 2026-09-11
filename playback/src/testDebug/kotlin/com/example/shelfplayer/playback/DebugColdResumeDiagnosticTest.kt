package com.example.shelfplayer.playback

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DebugColdResumeDiagnosticTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun clearDiagnostic() {
        context.getSharedPreferences(COLD_RESUME_DIAGNOSTIC_PREFERENCES, Context.MODE_PRIVATE)
            .edit { clear() }
    }

    @Test
    fun `armed diagnostic survives recreation and is consumed exactly once`() {
        val firstProcess = diagnostic()
        assertEquals(ColdResumeDiagnosticState.NotArmed, firstProcess.state.value)

        firstProcess.arm()
        assertEquals(ColdResumeDiagnosticState.Armed, firstProcess.state.value)

        val afterProcessDeath = diagnostic()
        assertEquals(ColdResumeDiagnosticState.Armed, afterProcessDeath.state.value)
        assertTrue(afterProcessDeath.consumeForColdPlaybackResumption())
        assertEquals(ColdResumeDiagnosticState.Consumed, afterProcessDeath.state.value)

        val afterAnotherProcessDeath = diagnostic()
        assertEquals(ColdResumeDiagnosticState.Consumed, afterAnotherProcessDeath.state.value)
        assertFalse(afterAnotherProcessDeath.consumeForColdPlaybackResumption())
    }

    @Test
    fun `rearming a consumed diagnostic creates one new injection`() {
        val diagnostic = diagnostic()
        diagnostic.arm()
        assertTrue(diagnostic.consumeForColdPlaybackResumption())

        diagnostic.arm()

        assertEquals(ColdResumeDiagnosticState.Armed, diagnostic.state.value)
        assertTrue(diagnostic.consumeForColdPlaybackResumption())
        assertFalse(diagnostic.consumeForColdPlaybackResumption())
    }

    private fun diagnostic() = DebugColdResumeDiagnostic(context, NO_OP_LOGGER)

    private companion object {
        val NO_OP_LOGGER = object : Logger {
            override fun log(event: LogEvent) = Unit
        }
    }
}
