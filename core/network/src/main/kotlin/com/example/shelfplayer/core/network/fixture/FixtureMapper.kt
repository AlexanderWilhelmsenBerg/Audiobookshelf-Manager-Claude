package com.example.shelfplayer.core.network.fixture

import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.SeriesSequence
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerCapabilities
import com.example.shelfplayer.core.model.ServerCapability
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.AudioTrack
import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.BookSnapshot
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibraryKind
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.core.model.library.MediaProgress
import com.example.shelfplayer.core.model.library.Series
import com.example.shelfplayer.core.model.library.SeriesMembership
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Translates the fixture document into `:core:model` types.
 *
 * This is the same translation step the Retrofit-backed gateway will perform in Phase 1, which is
 * the point: the repository, the database and the UI are exercised against exactly the shape the
 * real gateway will hand them, so Phase 1 replaces one class rather than reworking a layer.
 */
internal class FixtureMapper(private val document: FixtureLibraryDocument) {
    val serverId: ServerId = ServerId(document.server.id)
    val profileId: ProfileId = ProfileId(document.profile.id)

    private val authorsById: Map<String, Author> = document.authors.associate { fixture ->
        fixture.id to Author(serverId, AuthorId(fixture.id), fixture.name)
    }

    private val seriesById: Map<String, Series> = document.series.associate { fixture ->
        fixture.id to Series(serverId, SeriesId(fixture.id), fixture.name)
    }

    fun server(): Server = Server(
        id = serverId,
        displayName = document.server.displayName,
        baseUrl = document.server.baseUrl,
        detectedVersion = document.server.version,
        isFixture = true,
    )

    fun profile(lastUsedAt: Instant): Profile = Profile(
        id = profileId,
        serverId = serverId,
        username = document.profile.username,
        displayName = document.profile.displayName,
        role = parseRole(document.profile.role),
        requiresReauthentication = false,
        lastUsedAt = lastUsedAt,
        isFixture = true,
    )

    fun capabilities(): ServerCapabilities = ServerCapabilities(
        serverId = serverId,
        serverVersion = document.server.version,
        // PRODUCT_SPEC SYNC-001: an unrecognized capability name is dropped, never assumed.
        supported = document.capabilities.mapNotNull(::parseCapability).toSet(),
    )

    fun libraries(fetchedAt: Instant): List<Library> = document.libraries.map { fixture ->
        Library(
            serverId = serverId,
            id = LibraryId(fixture.id),
            name = fixture.name,
            kind = LibraryKind.fromWireValue(fixture.kind),
            displayOrder = fixture.displayOrder,
            bookCount = document.books.count { it.libraryId == fixture.id },
            remoteUpdatedAt = null,
            lastFetchedAt = fetchedAt,
        )
    }

    fun books(libraryId: LibraryId, fetchedAt: Instant): List<BookSnapshot> = document.books
        .filter { it.libraryId == libraryId.value }
        .map { fixture -> snapshot(fixture, fetchedAt) }

    private fun snapshot(fixture: FixtureBook, fetchedAt: Instant): BookSnapshot {
        val bookId = LibraryItemId(fixture.id)
        return BookSnapshot(
            book = bookOf(fixture, bookId, fetchedAt),
            tracks = tracksOf(fixture, bookId),
            chapters = chaptersOf(fixture, bookId),
        )
    }

    private fun bookOf(fixture: FixtureBook, bookId: LibraryItemId, fetchedAt: Instant) = Book(
        serverId = serverId,
        id = bookId,
        libraryId = LibraryId(fixture.libraryId),
        title = fixture.title,
        subtitle = fixture.subtitle,
        authors = fixture.authorIds.mapNotNull(authorsById::get),
        narrators = fixture.narrators,
        seriesMemberships = fixture.series.mapNotNull { membership ->
            seriesById[membership.seriesId]?.let {
                SeriesMembership(
                    series = it,
                    sequence = SeriesSequence.parse(membership.sequence),
                    isPrimary = membership.primary,
                )
            }
        },
        duration = fixture.durationSeconds.seconds,
        description = fixture.description,
        genres = fixture.genres,
        tags = fixture.tags,
        publishedYear = fixture.publishedYear,
        publisher = fixture.publisher,
        language = fixture.language,
        isExplicit = fixture.explicit,
        isAbridged = fixture.abridged,
        coverPath = fixture.coverPath,
        // PRODUCT_SPEC PLAY-003: an excluded server track is not playable, so it is not counted.
        trackCount = fixture.tracks.count { !it.excluded },
        sizeBytes = fixture.sizeBytes,
        remoteUpdatedAt = null,
        lastFetchedAt = fetchedAt,
        progress = progressOf(fixture, bookId),
        // PRODUCT_SPEC DL-001: nothing is downloaded until a real download commits.
        localAvailability = LocalAvailability.NotDownloaded,
    )

    private fun progressOf(fixture: FixtureBook, bookId: LibraryItemId): MediaProgress? =
        fixture.progress?.let { progress ->
            MediaProgress(
                serverId = serverId,
                profileId = profileId,
                bookId = bookId,
                position = progress.positionSeconds.seconds,
                duration = fixture.durationSeconds.seconds,
                isFinished = progress.finished,
                updatedAt = Instant.ofEpochSecond(progress.updatedAtEpochSeconds),
                hasUnsyncedChanges = false,
            )
        }

    private fun tracksOf(fixture: FixtureBook, bookId: LibraryItemId): List<AudioTrack> = fixture.tracks.map { track ->
        AudioTrack(
            serverId = serverId,
            bookId = bookId,
            index = track.index,
            remoteFileId = track.fileId,
            startOffset = track.startOffsetSeconds.seconds,
            duration = track.durationSeconds.seconds,
            mimeType = track.mimeType,
            sizeBytes = track.sizeBytes,
            isExcluded = track.excluded,
        )
    }

    private fun chaptersOf(fixture: FixtureBook, bookId: LibraryItemId): List<Chapter> =
        fixture.chapters.map { chapter ->
            Chapter(
                serverId = serverId,
                bookId = bookId,
                index = chapter.index,
                title = chapter.title,
                start = chapter.startSeconds.seconds,
                end = chapter.endSeconds.seconds,
            )
        }

    private fun parseRole(raw: String): ProfileRole =
        ProfileRole.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: ProfileRole.Listener

    private fun parseCapability(raw: String): ServerCapability? =
        ServerCapability.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
}
