package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.LocalAvailability
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/** Metadata-only record used by the accepted MediaSession onPostConnect fixture. */
internal fun postConnectFixtureBook(): Book {
    val server = ServerId("post-connect-server")
    return Book(
        serverId = server,
        id = LibraryItemId("post-connect-book"),
        libraryId = LibraryId("post-connect-library"),
        title = "Fixture book",
        subtitle = null,
        authors = listOf(Author(server, AuthorId("author"), "Fixture author")),
        narrators = emptyList(),
        seriesMemberships = emptyList(),
        duration = 1.seconds,
        description = null,
        genres = emptyList(),
        tags = emptyList(),
        publishedYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        isExplicit = false,
        isAbridged = false,
        coverPath = null,
        trackCount = 1,
        sizeBytes = 1L,
        remoteUpdatedAt = null,
        addedAt = Instant.EPOCH,
        lastFetchedAt = Instant.EPOCH,
        progress = null,
        localAvailability = LocalAvailability.NotDownloaded,
    )
}
