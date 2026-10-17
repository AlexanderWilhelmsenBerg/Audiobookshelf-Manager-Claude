package com.example.shelfplayer.playback

import org.junit.Test
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertNull

class AudioRouteTestCorrelatorTest {
    private val start = Instant.parse("2026-10-17T14:55:00Z")

    @Test
    fun `armed button then actual media key and transport are correlated without identity claims`() {
        val observer = AudioRouteTestCorrelator()
        observer.arm("ButtonA", start)
        val matched = requireNotNull(observer.observe("hardware-button keyCode=85 keyAction=0 keyInputId=-1", start.plusMillis(300)))
        assertContains(matched, "step=ButtonA")
        assertContains(matched, "attribution=temporal-only")
        val command = requireNotNull(observer.observe("transport-intent requested=Play controllerKind=external", start.plusMillis(550)))
        assertContains(command, "command=Play")
        assertContains(command, "attribution=temporal-only")
    }

    @Test
    fun `unmarked and late button markers cannot claim previously received media key`() {
        val observer = AudioRouteTestCorrelator()
        assertNull(observer.observe("hardware-button keyAction=0", start))
        observer.arm("ButtonB", start.plusSeconds(1))
        assertNull(observer.observe("transport-intent requested=Play", start.plusSeconds(2)))
        assertNull(observer.observe("hardware-button keyAction=0", start.plusSeconds(14)))
    }
}
