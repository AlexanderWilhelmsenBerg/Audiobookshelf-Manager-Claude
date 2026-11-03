package com.example.shelfplayer.playback

import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SonyMdrProtocolTest {
    private val tap = byteArrayOf(0xc9.toByte(), 1, 7, 0x6b, 0x65, 0x79, 0x52, 0x54, 0x61, 0x70, 0, 0)

    @Test
    fun `only exact C9 right tap record is evidence`() {
        assertTrue(decode(encode(12, 0, tap))!!.isRightTap())
        assertFalse(decode(encode(1, 0, tap))!!.isRightTap())
        assertFalse(decode(encode(12, 0, tap.copyOf(11)))!!.isRightTap())
        for (index in tap.indices) {
            val different = tap.copyOf().apply { this[index] = (this[index].toInt() xor 1).toByte() }
            assertFalse(decode(encode(12, 0, different))!!.isRightTap())
        }
        assertFalse(decode(encode(12, 0, byteArrayOf(0x19, 1, 0, 0)))!!.isRightTap())
    }

    @Test
    fun `escaping checksum and big endian payload length round trip`() {
        val payload = ByteArray(300) { (it % 256).toByte() }
        val frame = decode(encode(12, 1, payload))!!
        assertEquals(12, frame.type)
        assertEquals(1, frame.sequence)
        assertContentEquals(payload, frame.payload)
    }

    @Test
    fun `invalid checksum length escape and oversized frame are rejected before next valid frame`() {
        val good = encode(12, 0, tap)
        val checksum = good.copyOf().apply { this[lastIndex - 1] = (this[lastIndex - 1].toInt() xor 1).toByte() }
        val length = good.copyOf().apply { this[6] = 11 }
        val badEscape = byteArrayOf(0x3e, 0x3d, 0, 0x3c)
        val oversized = byteArrayOf(0x3e) + ByteArray(4200) + byteArrayOf(0x3c)
        for (invalid in listOf(checksum, length, badEscape, oversized)) {
            assertNull(decode(invalid))
            assertTrue(decode(invalid + good)!!.isRightTap())
        }
    }

    @Test
    fun `only inquiry and acknowledgements are written and ACKs are never acknowledged`() {
        val output = ByteArrayOutputStream()
        SonyMdrProtocol.inquiry(output)
        val inquiry = decode(output.toByteArray())!!
        assertEquals(12, inquiry.type)
        assertContentEquals(byteArrayOf(0, 0), inquiry.payload)
        output.reset()
        SonyMdrProtocol.acknowledge(output, SonyMdrProtocol.Frame(12, 0, tap))
        val ack = decode(output.toByteArray())!!
        assertEquals(1, ack.type)
        assertEquals(1, ack.sequence)
        assertTrue(ack.payload.isEmpty())
        output.reset()
        SonyMdrProtocol.acknowledge(output, ack)
        assertTrue(output.toByteArray().isEmpty())
    }

    private fun encode(type: Int, sequence: Int, payload: ByteArray): ByteArray =
        ByteArrayOutputStream().also { SonyMdrProtocol.write(it, type, sequence, payload) }.toByteArray()

    private fun decode(bytes: ByteArray) = SonyMdrProtocol.read(ByteArrayInputStream(bytes))
}
