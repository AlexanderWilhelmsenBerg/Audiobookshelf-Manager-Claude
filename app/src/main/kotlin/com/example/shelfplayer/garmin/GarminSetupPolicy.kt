package com.example.shelfplayer.garmin

import java.net.URI

/** AUTH-001/003: a bounded HTTPS Sidecar destination, without credentials/query/fragment. */
internal object GarminSetupPolicy {
    fun url(raw: String): String? {
        val trimmed = raw.trim()
        val value = if (trimmed.isBlank() || trimmed.startsWith("//") ||
            trimmed.contains("://")
        ) {
            trimmed.trimEnd('/')
        } else {
            "https://${trimmed.trimEnd('/')}"
        }
        if (unsafe(value)) {
            return null
        }
        return try {
            val uri = URI(value)
            value.takeIf {
                uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null &&
                    uri.normalize() == uri
            }
        } catch (_: java.net.URISyntaxException) {
            null
        }
    }
    private fun unsafe(value: String): Boolean {
        val malformedPath = value.contains("/../") || value.contains("/./")
        return value.length > MAX_URL || malformedPath || value.any { it.isWhitespace() || it == '\\' }
    }
    private const val MAX_URL = 512
}
