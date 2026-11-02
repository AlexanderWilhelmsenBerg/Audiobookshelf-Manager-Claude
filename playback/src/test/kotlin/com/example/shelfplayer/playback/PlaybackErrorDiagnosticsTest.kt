package com.example.shelfplayer.playback

import android.media.MediaCodec
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import com.example.shelfplayer.core.common.log.LogField
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@OptIn(UnstableApi::class)
class PlaybackErrorDiagnosticsTest {
    @Test
    fun `renderer and decoder fields exclude private messages names and format metadata`() {
        val codec = MediaCodecInfo.newInstance(
            "private-host-token-title", "audio/mp4a-latm", "audio/mp4a-latm", null,
            true, false, true, false, false,
        )
        val decoder = MediaCodecDecoderException(IllegalStateException("private-host-token-title"), codec)
        val format = Format.Builder()
            .setId("private-host-token-title")
            .setLabel("private-host-token-title")
            .setLanguage("private-host-token-title")
            .setCodecs("private-host-token-title")
            .setSampleMimeType("audio/mp4a-latm")
            .setChannelCount(2)
            .setSampleRate(44100)
            .build()
        val error = ExoPlaybackException.createForRenderer(
            decoder,
            "private-host-token-title",
            1,
            format,
            C.FORMAT_HANDLED,
            null,
            false,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )

        val fields = PlaybackErrorDiagnostics.fields(error)

        assertEquals("ExoPlaybackException", fields.value("cause0Type"))
        assertEquals("MediaCodecDecoderException", fields.value("cause1Type"))
        assertEquals("audio/mp4a-latm", fields.value("cause0SampleMime"))
        assertEquals("44100", fields.value("cause0SampleRate"))
        assertEquals("true", fields.value("cause1CodecHardware"))
        assertFalse(fields.toString().contains("private-host-token-title"))
    }

    @Test
    fun `framework codec numeric errors and flags survive without message`() {
        val constructor = MediaCodec.CodecException::class.java.getDeclaredConstructor(
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
        ).apply { isAccessible = true }
        val codecError = constructor.newInstance(1100, 2, "https://private-host/token=secret")
        val error = PlaybackException("private-title", codecError, PlaybackException.ERROR_CODE_DECODING_FAILED)

        val fields = PlaybackErrorDiagnostics.fields(error)

        assertEquals("1100", fields.value("cause1CodecErrorCode"))
        assertEquals(codecError.isTransient.toString(), fields.value("cause1CodecTransient"))
        assertEquals(codecError.isRecoverable.toString(), fields.value("cause1CodecRecoverable"))
        assertFalse(fields.toString().contains("private"))
        assertTrue(fields.value("cause1CodecDiagnostic")?.startsWith("android.media.MediaCodec.error_") == true)
    }

    @Test
    fun `untrusted mime and cyclic cause are bounded`() {
        val first = IllegalStateException("private-title")
        val second = IllegalArgumentException("private-token", first)
        first.initCause(second)
        val format = Format.Builder().setSampleMimeType("audio/private-title").build()
        val error = ExoPlaybackException.createForRenderer(
            first,
            "private-renderer",
            0,
            format,
            C.FORMAT_HANDLED,
            null,
            false,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )

        val fields = PlaybackErrorDiagnostics.fields(error)

        assertEquals("other", fields.value("cause0SampleMime"))
        assertEquals("true", fields.value("causeChainTruncated"))
        assertEquals(3, (fields.single { it.key == "causeDepth" } as LogField.Count).value)
        assertFalse(fields.toString().contains("private"))
    }

    @Test
    fun `long cause chains stop at eight`() {
        var cause: Throwable = IllegalStateException("private")
        repeat(20) { cause = IllegalStateException("private", cause) }
        val fields = PlaybackErrorDiagnostics.fields(
            PlaybackException("private", cause, PlaybackException.ERROR_CODE_DECODING_FAILED),
        )
        assertEquals(8, (fields.single { it.key == "causeDepth" } as LogField.Count).value)
        assertEquals("true", fields.value("causeChainTruncated"))
    }

    @Test
    fun `decoder diagnostic accepts only platform numeric grammar`() {
        val decoder = MediaCodecDecoderException(IllegalStateException("private"), null)
        val diagnostic = MediaCodecDecoderException::class.java.getDeclaredField("diagnosticInfo")
            .apply { isAccessible = true }
        val error = PlaybackException("private", decoder, PlaybackException.ERROR_CODE_DECODING_FAILED)
        diagnostic.set(decoder, "android.media.MediaCodec.error_neg_123")
        assertEquals(
            "android.media.MediaCodec.error_neg_123",
            PlaybackErrorDiagnostics.fields(error).value("cause1CodecDiagnostic"),
        )
        for (hostile in listOf(
            "https://private/token",
            "android.media.MediaCodec.error_123_private",
            "x".repeat(500),
        )) {
            diagnostic.set(decoder, hostile)
            assertEquals(null, PlaybackErrorDiagnostics.fields(error).value("cause1CodecDiagnostic"))
        }
    }
    private fun List<LogField>.value(key: String): String? =
        filterIsInstance<LogField.Public>().singleOrNull { it.key == key }?.value
}
