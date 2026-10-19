package com.example.shelfplayer.diagnostics

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticRestartPolicyTest {
    @Test
    fun `older supported Android versions use manual diagnostic restart`() {
        assertFalse(DiagnosticRestartPolicy.canUseTrampoline(26))
        assertFalse(DiagnosticRestartPolicy.canUseTrampoline(27))
        assertTrue(DiagnosticRestartPolicy.canUseTrampoline(28))
        assertTrue(DiagnosticRestartPolicy.canUseTrampoline(36))
    }

    @Test
    fun `secondary diagnostic process never runs primary collectors`() {
        assertTrue(
            DiagnosticRestartPolicy.shouldInitializeApp(
                "org.homebord.bookwave.debug",
                "org.homebord.bookwave.debug",
            ),
        )
        assertFalse(
            DiagnosticRestartPolicy.shouldInitializeApp(
                "org.homebord.bookwave.debug:diagnostics_restart",
                "org.homebord.bookwave.debug",
            ),
        )
    }

    @Test
    fun `unavailable process metadata must not prevent regular startup`() {
        assertTrue(DiagnosticRestartPolicy.shouldInitializeApp(null, "org.homebord.bookwave.debug"))
    }
}
