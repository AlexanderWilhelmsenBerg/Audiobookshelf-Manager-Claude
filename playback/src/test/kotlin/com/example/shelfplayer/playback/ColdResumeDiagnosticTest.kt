package com.example.shelfplayer.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ColdResumeDiagnosticTest {
    @Test
    fun `release diagnostic cannot be armed or inject zero`() {
        val diagnostic = DisabledColdResumeDiagnostic()
        val selector = ColdResumeStartPosition(diagnostic)

        diagnostic.arm()

        assertEquals(ColdResumeDiagnosticState.NotArmed, diagnostic.state.value)
        assertFalse(diagnostic.consumeForColdPlaybackResumption())
        assertEquals(1_234L, selector.forPlaybackResumption(1_234L))
    }

    @Test
    fun `cold selector consumes one armed injection and otherwise preserves real start`() {
        val diagnostic = OneShotDiagnostic()
        val selector = ColdResumeStartPosition(diagnostic)

        assertEquals(0L, selector.forPlaybackResumption(1_234L))
        assertEquals(1_234L, selector.forPlaybackResumption(1_234L))
        assertEquals(1, diagnostic.consumptions)
    }

    @Test
    fun `diagnostic selector has exactly one cold playback service call site`() {
        val source = File("src/main/kotlin/com/example/shelfplayer/playback/PlaybackService.kt").readText()
        val selectorCall = "coldResumeStartPosition.forPlaybackResumption"

        assertEquals(
            1,
            source.split(selectorCall).size - 1,
            "foreground Play and metadata-only resumption must not gain another diagnostic consumption site",
        )
        val coldResumption = source
            .substringAfter("private suspend fun resumeForPlayback()")
            .substringBefore("private suspend fun describeResumable()")
        assertTrue(selectorCall in coldResumption)
        assertTrue(
            Regex(
                """if\s*\(isForPlayback\)\s*resumeForPlayback\(\)\s*else\s*describeResumable\(\)""",
            ).containsMatchIn(source),
            "metadata-only resumption must stay on describeResumable without consuming the one-shot",
        )
    }

    @Test
    fun `zero start is never eligible to become durable playback progress`() {
        assertFalse(isPersistablePlaybackPosition(positionMs = 0L, singleFileFallback = false))
        assertFalse(isPersistablePlaybackPosition(positionMs = 1_234L, singleFileFallback = true))
        assertTrue(isPersistablePlaybackPosition(positionMs = 1_234L, singleFileFallback = false))
    }

    private class OneShotDiagnostic : ColdResumeDiagnostic {
        private val mutableState = MutableStateFlow(ColdResumeDiagnosticState.Armed)
        override val state: StateFlow<ColdResumeDiagnosticState> = mutableState
        var consumptions = 0
            private set

        override fun arm() {
            mutableState.value = ColdResumeDiagnosticState.Armed
        }

        override fun consumeForColdPlaybackResumption(): Boolean {
            if (mutableState.value != ColdResumeDiagnosticState.Armed) return false
            consumptions += 1
            mutableState.value = ColdResumeDiagnosticState.Consumed
            return true
        }
    }
}
