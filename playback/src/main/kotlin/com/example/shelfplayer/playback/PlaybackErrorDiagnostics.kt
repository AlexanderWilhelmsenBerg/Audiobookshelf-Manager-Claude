package com.example.shelfplayer.playback

import android.media.MediaCodec
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import com.example.shelfplayer.core.common.log.LogField
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/** PLAY-001/002 diagnostics: never serialize exceptions, formats, names, or media metadata. */
@OptIn(UnstableApi::class)
internal object PlaybackErrorDiagnostics {
    private const val MAX_CAUSES = 8
    private val codecDiagnostic = Regex("android\\.media\\.MediaCodec\\.error_(?:neg_)?[0-9]{1,10}")
    private val audioMimeTypes = setOf(
        "audio/mp4a-latm", "audio/mpeg", "audio/opus", "audio/vorbis", "audio/flac",
        "audio/raw", "audio/ac3", "audio/eac3", "audio/eac3-joc", "audio/ac4",
        "audio/vnd.dts", "audio/vnd.dts.hd", "audio/true-hd", "audio/alac", "audio/3gpp",
        "audio/amr-wb", "audio/g711-alaw", "audio/g711-mlaw",
    )

    fun fields(error: PlaybackException): List<LogField> = buildList {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < MAX_CAUSES && seen.add(cause)) {
            val current = cause
            val prefix = "cause$depth"
            add(LogField.Public("${prefix}Type", type(current)))
            addDetails(current, prefix)
            depth++
            cause = current.cause
        }
        add(LogField.Count("causeDepth", depth))
        add(LogField.Public("causeChainTruncated", cause != null))
    }

    private fun MutableList<LogField>.addDetails(current: Throwable, prefix: String) {
        when (current) {
            is ExoPlaybackException -> {
                add(LogField.Public("${prefix}ExoType", current.type))
                add(LogField.Public("${prefix}RendererIndex", current.rendererIndex))
                add(LogField.Public("${prefix}FormatSupport", current.rendererFormatSupport))
                current.rendererFormat?.let { format ->
                    add(
                        LogField.Public(
                            "${prefix}SampleMime",
                            format.sampleMimeType.takeIf { it in audioMimeTypes } ?: "other",
                        ),
                    )
                    add(LogField.Public("${prefix}ChannelCount", format.channelCount))
                    add(LogField.Public("${prefix}SampleRate", format.sampleRate))
                    add(LogField.Public("${prefix}PcmEncoding", format.pcmEncoding))
                }
            }

            is MediaCodecDecoderException -> {
                add(LogField.Public("${prefix}CodecErrorCode", current.errorCode))
                diagnostic(current.diagnosticInfo)?.let { add(LogField.Public("${prefix}CodecDiagnostic", it)) }
                // Names are vendor-controlled strings; omit them rather than trust a character filter.
                current.codecInfo?.let { codec ->
                    add(LogField.Public("${prefix}CodecHardware", codec.hardwareAccelerated))
                    add(LogField.Public("${prefix}CodecSoftware", codec.softwareOnly))
                    add(LogField.Public("${prefix}CodecVendor", codec.vendor))
                    add(LogField.Public("${prefix}CodecSecure", codec.secure))
                }
            }

            is MediaCodec.CodecException -> {
                add(LogField.Public("${prefix}CodecErrorCode", current.errorCode))
                add(LogField.Public("${prefix}CodecTransient", current.isTransient))
                add(LogField.Public("${prefix}CodecRecoverable", current.isRecoverable))
                diagnostic(current.diagnosticInfo)?.let { add(LogField.Public("${prefix}CodecDiagnostic", it)) }
            }
        }
    }

    private fun diagnostic(value: String?): String? = value?.takeIf { codecDiagnostic.matches(it) }

    private fun type(error: Throwable): String = when (error) {
        is ExoPlaybackException -> "ExoPlaybackException"
        is MediaCodecDecoderException -> "MediaCodecDecoderException"
        is MediaCodec.CodecException -> "MediaCodec.CodecException"
        is PlaybackException -> "PlaybackException"
        is IOException -> "IOException"
        is IllegalStateException -> "IllegalStateException"
        is IllegalArgumentException -> "IllegalArgumentException"
        else -> "other"
    }
}
