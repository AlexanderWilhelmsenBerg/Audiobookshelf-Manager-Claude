package com.example.shelfplayer.playback

import java.io.InputStream
import java.io.OutputStream

/** Read-only MDR v2 framing. Unknown records are acknowledged, never interpreted as transport. */
internal object SonyMdrProtocol {
    private const val MAX_PAYLOAD = 4096
    private const val HEADER_SIZE = 6
    private const val OVERHEAD = HEADER_SIZE + 1
    private const val START = 0x3e
    private const val END = 0x3c
    private const val ESCAPE = 0x3d
    private const val ESCAPE_BIT = 0x10
    private const val BYTE_MASK = 0xff
    private const val BITS_PER_BYTE = 8
    private const val LENGTH_BYTES = 4
    private const val DATA_TYPE = 0x0c
    private const val ACK_TYPE = 1
    private val tap = byteArrayOf(0xc9.toByte(), 1, 7, 0x6b, 0x65, 0x79, 0x52, 0x54, 0x61, 0x70, 0, 0)

    data class Frame(val type: Int, val sequence: Int, val payload: ByteArray) {
        fun isRightTap(): Boolean = type == DATA_TYPE && payload.contentEquals(tap)
    }

    fun read(input: InputStream): Frame? {
        val decoder = Decoder()
        var frame: Frame? = null
        while (frame == null) {
            val value = input.read()
            if (value < 0) return null
            frame = decoder.accept(value)
        }
        return frame
    }

    private class Decoder {
        private val body = ByteArray(MAX_PAYLOAD + OVERHEAD)
        private var size = 0
        private var inside = false
        private var escaped = false

        fun accept(value: Int): Frame? = when {
            escaped -> appendEscaped(value)

            value == START -> {
                size = 0
                inside = true
                null
            }

            !inside -> null

            value == END -> {
                inside = false
                decode()
            }

            value == ESCAPE -> {
                escaped = true
                null
            }

            else -> append(value)
        }

        private fun appendEscaped(value: Int): Frame? {
            escaped = false
            if (value !in (END xor ESCAPE_BIT)..(START xor ESCAPE_BIT)) {
                inside = false
                return null
            }
            return append(value or ESCAPE_BIT)
        }

        private fun append(value: Int): Frame? {
            if (size < body.size) {
                body[size++] = value.toByte()
            } else {
                inside = false
            }
            return null
        }

        private fun decode(): Frame? {
            if (size < OVERHEAD) return null
            var length = 0L
            for (index in 2 until HEADER_SIZE) {
                length = (length shl BITS_PER_BYTE) or (body[index].toLong() and BYTE_MASK.toLong())
            }
            if (length != (size - OVERHEAD).toLong()) return null
            val checksum = (0 until size - 1).sumOf { body[it].toInt() and BYTE_MASK } and BYTE_MASK
            if (checksum != (body[size - 1].toInt() and BYTE_MASK)) return null
            return Frame(
                body[0].toInt() and BYTE_MASK,
                body[1].toInt() and BYTE_MASK,
                body.copyOfRange(HEADER_SIZE, size - 1),
            )
        }
    }

    fun inquiry(output: OutputStream) = write(output, DATA_TYPE, 0, byteArrayOf(0, 0))

    fun acknowledge(output: OutputStream, frame: Frame) {
        if (frame.type != ACK_TYPE) write(output, ACK_TYPE, frame.sequence xor 1, byteArrayOf())
    }

    internal fun write(output: OutputStream, type: Int, sequence: Int, payload: ByteArray) {
        require(payload.size <= MAX_PAYLOAD)
        val body = ByteArray(payload.size + OVERHEAD)
        body[0] = type.toByte()
        body[1] = sequence.toByte()
        for (index in 0 until LENGTH_BYTES) {
            body[2 + index] = (payload.size ushr ((LENGTH_BYTES - 1 - index) * BITS_PER_BYTE)).toByte()
        }
        payload.copyInto(body, HEADER_SIZE)
        body[body.lastIndex] = body.dropLast(1).sumOf { it.toInt() and BYTE_MASK }.toByte()
        output.write(START)
        for (byte in body) {
            val value = byte.toInt() and BYTE_MASK
            if (value in END..START) {
                output.write(ESCAPE)
                output.write(value xor ESCAPE_BIT)
            } else {
                output.write(value)
            }
        }
        output.write(END)
        output.flush()
    }
}
