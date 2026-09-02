package com.example.shelfplayer.playback

import androidx.media3.datasource.HttpDataSource

/** Returns the HTTP response code carried anywhere in a Media3 data-source failure chain. */
internal fun Throwable.httpResponseCode(): Int? {
    var current: Throwable? = this
    while (current != null) {
        if (current is HttpDataSource.InvalidResponseCodeException) return current.responseCode
        current = current.cause
    }
    return null
}
