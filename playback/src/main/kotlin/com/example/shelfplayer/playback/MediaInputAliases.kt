package com.example.shelfplayer.playback

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Capture-local ordinals; neither descriptor bytes, their digest nor the random key are exported. */
internal class MediaInputAliases {
    private val key = ByteArray(32).also(SecureRandom()::nextBytes)
    private val aliases = mutableMapOf<List<Byte>, String>()

    fun descriptor(value: String?): String {
        if (value.isNullOrBlank()) return "missing"
        if (value.length > MAX_DESCRIPTOR_LENGTH) return "unavailable"
        val digest = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(value.toByteArray(Charsets.UTF_8)).toList()
        }
        return aliases[digest] ?: if (aliases.size < MAX_ALIASES) {
            "s${aliases.size + 1}".also { aliases[digest] = it }
        } else {
            "overflow"
        }
    }

    private companion object {
        const val MAX_ALIASES = 128
        const val MAX_DESCRIPTOR_LENGTH = 1024
    }
}
