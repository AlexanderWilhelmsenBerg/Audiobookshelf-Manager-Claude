package com.example.shelfplayer.playback

import android.net.Uri
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.core.model.library.Book

/**
 * PD-001 / LIB-002 — turns profile-visible library artwork into a URI an external car/media host may read.
 *
 * The playback module deliberately does not know Coil, cache layout, credentials or server URL construction.
 * The installed app owns that Android boundary and returns only local content URIs. Missing/cold artwork is
 * represented by null so the host can draw its ordinary placeholder.
 */
interface AutoArtwork {
    suspend fun book(book: Book, serverBaseUrls: Map<ServerId, String>, offlineCoverUri: String? = null): Uri?

    /**
     * A confirmed author portrait is preferred; [representativeCover] is LIB-002's fallback.
     * Implementations must never put credentials or reusable secrets in the returned URI.
     */
    suspend fun author(
        author: Author, representativeCover: Book?, serverBaseUrls: Map<ServerId, String>,
        representativeOfflineCoverUri: String? = null,
    ): Uri?

    companion object {
        val None = object : AutoArtwork {
            override suspend fun book(
                book: Book,
                serverBaseUrls: Map<ServerId, String>,
                offlineCoverUri: String?,
            ): Uri? = null

            override suspend fun author(
                author: Author,
                representativeCover: Book?,
                serverBaseUrls: Map<ServerId, String>,
                representativeOfflineCoverUri: String?,
            ): Uri? = null
        }
    }
}
