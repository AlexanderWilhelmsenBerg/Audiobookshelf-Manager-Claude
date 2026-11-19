package com.example.shelfplayer.design

import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.ServerStatus
import com.example.shelfplayer.core.model.SyncStatus
import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.core.model.library.MediaProgress
import com.example.shelfplayer.domain.library.homeShelvesOf
import com.example.shelfplayer.feature.home.HomeUiState
import com.example.shelfplayer.playback.PlaybackUiState
import java.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

internal object DesignPreviewFixtures {
    private val server = ServerId("design-preview")
    private val listener = ProfileId("preview-listener")
    private val currentBook = book("salt-harbour", "The Salt Harbour", "Ada North").let { book ->
        book.copy(
            coverPath = "preview-cover",
            localAvailability = LocalAvailability.Complete,
            progress = MediaProgress(
                serverId = server,
                profileId = listener,
                bookId = book.id,
                position = 184.minutes,
                duration = book.duration,
                isFinished = false,
                updatedAt = Instant.EPOCH.plusSeconds(1),
                hasUnsyncedChanges = false,
            ),
        )
    }
    private val secondBook = book("lantern", "The Long Road to Lantern Bay", "Robin Vale")
    private val books = listOf(
        currentBook,
        secondBook.copy(progress = currentBook.progress?.copy(bookId = secondBook.id, position = 44.minutes)),
        book("winter-map", "The Winter Map", "Jules Pine"),
        book("radio", "Radio at Midnight", "Ari Lake"),
    )
    private val chapters = listOf(
        Chapter(server, currentBook.id, 0, "The crossing", 0.minutes, 165.minutes),
        Chapter(server, currentBook.id, 1, "Finding the light", 165.minutes, currentBook.duration),
    )

    val home = HomeUiState(
        profile = Profile(
            id = listener,
            serverId = server,
            username = "preview",
            displayName = "Preview listener",
            role = ProfileRole.Listener,
            requiresReauthentication = false,
            lastUsedAt = Instant.EPOCH,
            isFixture = true,
        ),
        books = books,
        shelves = homeShelvesOf(books),
        syncStatus = SyncStatus.Succeeded,
        serverStatus = ServerStatus.Reachable,
        isLoaded = true,
    )

    val playback = PlaybackUiState.Idle.copy(
        bookId = currentBook.id,
        title = currentBook.title,
        author = currentBook.authors.first().name,
        position = currentBook.progress?.position ?: 0.minutes,
        duration = currentBook.duration,
        chapters = chapters,
        currentChapter = chapters.last(),
    )

    private fun book(id: String, title: String, author: String) = Book(
        serverId = server,
        id = LibraryItemId(id),
        libraryId = LibraryId("preview-library"),
        title = title,
        subtitle = null,
        authors = listOf(Author(server, AuthorId(id), author)),
        narrators = emptyList(),
        seriesMemberships = emptyList(),
        duration = 8.hours,
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
        sizeBytes = 0,
        remoteUpdatedAt = null,
        addedAt = Instant.EPOCH,
        lastFetchedAt = Instant.EPOCH,
        progress = null,
        localAvailability = LocalAvailability.NotDownloaded,
    )
}
