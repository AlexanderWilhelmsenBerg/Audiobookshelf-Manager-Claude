package com.example.shelfplayer.playback

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File

/** Predecode the mono AAC fixture to isolate the platform output from streaming and decoder work. */
internal data class LabPcm(val bytes: ByteArray, val sampleRate: Int) {
    companion object {
        suspend fun decode(file: File): LabPcm {
            val source = MediaExtractor()
            try {
                source.setDataSource(file.path)
                val format = source.getTrackFormat(0)
                source.selectTrack(0)
                val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
                try {
                    codec.configure(format, null, null, 0)
                    codec.start()
                    return drain(source, codec)
                } finally {
                    codec.release()
                }
            } finally {
                source.release()
            }
        }

        private suspend fun drain(source: MediaExtractor, codec: MediaCodec): LabPcm {
            val info = MediaCodec.BufferInfo()
            val decoded = ByteArrayOutputStream()
            var inputEnded = false
            do {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) inputEnded = feed(source, codec)
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index >= 0) {
                    val output = requireNotNull(codec.getOutputBuffer(index))
                    output.position(info.offset)
                    output.limit(info.offset + info.size)
                    decoded.write(ByteArray(info.size).also { output.get(it) })
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            } while (true)
            val format = codec.outputFormat
            check(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1)
            return LabPcm(decoded.toByteArray(), format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        }

        private fun feed(source: MediaExtractor, codec: MediaCodec): Boolean {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) return false
            val buffer = requireNotNull(codec.getInputBuffer(index))
            val size = source.readSampleData(buffer, 0)
            val end = size < 0
            codec.queueInputBuffer(
                index,
                0,
                size.coerceAtLeast(0),
                source.sampleTime.coerceAtLeast(0),
                if (end) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0,
            )
            if (!end) source.advance()
            return end
        }

        private const val TIMEOUT_US = 10_000L
    }
}

internal class LabAudioTrack(pcm: LabPcm) : CarLabEngine {
    private val rate = pcm.sampleRate
    private val track = AudioTrack.Builder().setAudioAttributes(labAudioAttributes)
        .setAudioFormat(
            AudioFormat.Builder().setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).build(),
        )
        .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.bytes.size).build()

    init {
        try {
            check(track.write(pcm.bytes, 0, pcm.bytes.size) == pcm.bytes.size)
            check(track.setLoopPoints(0, pcm.bytes.size / Short.SIZE_BYTES, -1) == AudioTrack.SUCCESS)
        } catch (error: IllegalStateException) {
            track.release()
            throw error
        }
    }

    override val playing get() = track.playState == AudioTrack.PLAYSTATE_PLAYING
    override val positionMs get() = Integer.toUnsignedLong(track.playbackHeadPosition) * 1000L / rate
    override val routed: AudioDeviceInfo? get() = track.routedDevice
    override fun play() = track.play()
    override fun pause() = track.pause()
    override fun close() = track.release()
}
