package com.example.shelfplayer.garmin

import java.net.URI

/** AUTH-001/003: a bounded HTTPS Sidecar destination, without credentials/query/fragment. */
internal object GarminSetupPolicy {
    fun url(raw: String): String? {
        val value = raw.trim().trimEnd('/')
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
    fun credentials(user: String, password: String): Boolean =
        user.isNotBlank() && user.length <= MAX_USER && password.isNotEmpty() && password.length <= MAX_PASSWORD
    private const val MAX_URL = 512
    private const val MAX_USER = 128
    private const val MAX_PASSWORD = 256
}
