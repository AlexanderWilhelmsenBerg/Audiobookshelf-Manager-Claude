package com.example.shelfplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
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

/**
 * Whether this failure came from reading a **local file** rather than from the network.
 *
 * `FileDataSource` throws its own exception type, so its presence anywhere in the chain is *proof* of a
 * local read rather than an inference from an error code. That distinction is why this exists: Media3's
 * `ERROR_CODE_IO_FILE_NOT_FOUND` and `ERROR_CODE_IO_NO_PERMISSION` cover both a server's 404 and a missing
 * download, and a review found the guess between them telling a listener the server was unreachable when
 * playback had never contacted it — for an offline book, which is product priority 3.
 */
@OptIn(UnstableApi::class)
internal fun Throwable.isLocalFileFailure(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is FileDataSource.FileDataSourceException) return true
        current = current.cause
    }
    return false
}
