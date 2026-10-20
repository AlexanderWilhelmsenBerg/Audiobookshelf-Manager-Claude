package com.example.shelfplayer.playback

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Type metadata is diagnostic evidence, not authorization to change the listening output.
 * These tests must remain independent of connected hardware.
 */
@SuppressLint("InlinedApi")
class OutputClassifierFactsTest {
    @Test
    fun `classic Bluetooth A2DP is ambiguous transport evidence`() {
        val type = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        assertEquals("BLUETOOTH_A2DP", OutputClassifierFacts.typeName(type))
        assertEquals("Ambiguous", OutputClassifierFacts.role(type))
        assertEquals("transport-only", OutputClassifierFacts.evidence(type))
    }

    @Test
    fun `generic audio bus does not prove a car`() {
        val type = AudioDeviceInfo.TYPE_BUS
        assertEquals("BUS", OutputClassifierFacts.typeName(type))
        assertEquals("Unknown(bus-not-proof-of-car)", OutputClassifierFacts.role(type))
        assertEquals("generic-external-bus", OutputClassifierFacts.evidence(type))
    }

    @Test
    fun `remote submix is not a verified physical headset or car`() {
        val type = AudioDeviceInfo.TYPE_REMOTE_SUBMIX
        assertEquals("REMOTE_SUBMIX", OutputClassifierFacts.typeName(type))
        assertEquals("Unknown(remote-mix)", OutputClassifierFacts.role(type))
        assertEquals("remote-mix-not-endpoint", OutputClassifierFacts.evidence(type))
    }

    @Test
    fun `unrecognized platform output remains visible and unclassified`() {
        val type = 7777
        assertNull(OutputDevices.outputOf(type, null))
        assertEquals("UNRECOGNIZED", OutputClassifierFacts.typeName(type))
        assertEquals("Unclassified", OutputClassifierFacts.role(type))
    }

    @Test
    fun `logical identity collisions are counted without exporting names`() {
        assertEquals(2, OutputClassifierFacts.collisions(listOf("same", "same", null, "same")))
        assertEquals(0, OutputClassifierFacts.collisions(listOf("A", "B", null)))
    }
}
