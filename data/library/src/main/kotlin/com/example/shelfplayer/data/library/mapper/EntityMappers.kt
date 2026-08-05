package com.example.shelfplayer.data.library.mapper

import com.example.shelfplayer.core.database.converter.StringListConverters
import com.example.shelfplayer.core.database.entity.AudioTrackEntity
import com.example.shelfplayer.core.database.entity.AuthorEntity
import com.example.shelfplayer.core.database.entity.BookAuthorCrossRef
import com.example.shelfplayer.core.database.entity.BookEntity
import com.example.shelfplayer.core.database.entity.BookSeriesCrossRef
import com.example.shelfplayer.core.database.entity.BookWithRelations
import com.example.shelfplayer.core.database.entity.ChapterEntity
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.LibraryEntity
import com.example.shelfplayer.core.database.entity.MediaProgressEntity
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.SeriesEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import com.example.shelfplayer.core.database.entity.SyncStateEntity
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.SeriesSequence
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.SyncState
import com.example.shelfplayer.core.model.SyncStatus
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
import kotlin.time.Duration.Companion.milliseconds

/**
 * PRODUCT_SPEC 9.3 — the only place entities and domain models meet.
 *
 * The mapping is deliberately total and explicit rather than reflective: an added column is a
 * compile error here, which is how a schema change gets noticed before it ships.
 */
internal object EntityMappers {
    // --- Server / profile -------------------------------------------------------------------------

    fun toEntity(server: Server, fetchedAt: Instant) = ServerEntity(
        serverId = server.id.value,
        displayName = server.displayName,
        baseUrl = server.baseUrl,
        detectedVersion = server.detectedVersion,
        isFixture = server.isFixture,
        lastFetchedAt = fetchedAt.toEpochMilli(),
        // The fixture server reports no authentication modes and no capability handshake. Empty is
        // correct rather than a placeholder: PRODUCT_SPEC SYNC-001 requires an unprobed capability to
        // read as unsupported, and the demo library has no server to probe.
        authMethodsJson = EMPTY_JSON_ARRAY,
        capabilitiesJson = EMPTY_JSON_ARRAY,
        capabilitiesDetectedAt = null,
    )

    fun toEntity(profile: Profile) = ProfileEntity(
        profileId = profile.id.value,
        serverId = profile.serverId.value,
        // The fixture profile has no server-side account behind it, so there is no remote id to record.
        remoteUserId = null,
        username = profile.username,
        displayName = profile.displayName,
        role = profile.role.name,
        requiresReauthentication = profile.requiresReauthentication,
        lastUsedAt = profile.lastUsedAt?.toEpochMilli(),
        isFixture = profile.isFixture,
    )

    fun toDomain(entity: ProfileEntity) = Profile(
        id = ProfileId(entity.profileId),
        serverId = ServerId(entity.serverId),
        username = entity.username,
        displayName = entity.displayName,
        role = ProfileRole.entries.firstOrNull { it.name == entity.role } ?: ProfileRole.Listener,
        requiresReauthentication = entity.requiresReauthentication,
        lastUsedAt = entity.lastUsedAt?.let(Instant::ofEpochMilli),
        isFixture = entity.isFixture,
    )

    // --- Library ----------------------------------------------------------------------------------

    fun toEntity(library: Library) = LibraryEntity(
        libraryKey = EntityKey.of(library.serverId.value, library.id.value),
        serverId = library.serverId.value,
        remoteId = library.id.value,
        name = library.name,
        kind = library.kind.name,
        displayOrder = library.displayOrder,
        remoteUpdatedAt = library.remoteUpdatedAt?.toEpochMilli(),
        lastFetchedAt = library.lastFetchedAt.toEpochMilli(),
        isDeleted = false,
    )

    fun toDomain(entity: LibraryEntity, bookCount: Int) = Library(
        serverId = ServerId(entity.serverId),
        id = LibraryId(entity.remoteId),
        name = entity.name,
        kind = LibraryKind.entries.firstOrNull { it.name == entity.kind } ?: LibraryKind.Unknown,
        displayOrder = entity.displayOrder,
        bookCount = bookCount,
        remoteUpdatedAt = entity.remoteUpdatedAt?.let(Instant::ofEpochMilli),
        lastFetchedAt = Instant.ofEpochMilli(entity.lastFetchedAt),
    )

    // --- Book -------------------------------------------------------------------------------------

    fun toEntities(snapshot: BookSnapshot): BookRows {
        val book = snapshot.book
        val bookKey = EntityKey.of(book.serverId.value, book.id.value)
        return BookRows(
            book = bookEntity(book, bookKey),
            authors = book.authors.map(::authorEntity),
            authorLinks = book.authors.mapIndexed { index, author ->
                BookAuthorCrossRef(
                    bookKey = bookKey,
                    authorKey = EntityKey.of(author.serverId.value, author.id.value),
                    position = index,
                )
            },
            series = book.seriesMemberships.map { seriesEntity(it.series) },
            seriesLinks = book.seriesMemberships.map { membership ->
                BookSeriesCrossRef(
                    bookKey = bookKey,
                    seriesKey = EntityKey.of(
                        membership.series.serverId.value,
                        membership.series.id.value,
                    ),
                    sequenceRaw = membership.sequence.raw.takeIf(String::isNotEmpty),
                    isPrimary = membership.isPrimary,
                )
            },
            tracks = snapshot.tracks.map { track -> trackEntity(track, bookKey) },
            chapters = snapshot.chapters.map { chapter -> chapterEntity(chapter, bookKey) },
            progress = book.progress?.let(::toEntity),
        )
    }

    private fun bookEntity(book: Book, bookKey: String) = BookEntity(
        bookKey = bookKey,
        serverId = book.serverId.value,
        remoteId = book.id.value,
        libraryKey = EntityKey.of(book.serverId.value, book.libraryId.value),
        title = book.title,
        subtitle = book.subtitle,
        narratorsJson = encode(book.narrators),
        genresJson = encode(book.genres),
        tagsJson = encode(book.tags),
        durationMillis = book.duration.inWholeMilliseconds,
        description = book.description,
        publishedYear = book.publishedYear,
        publisher = book.publisher,
        language = book.language,
        isExplicit = book.isExplicit,
        isAbridged = book.isAbridged,
        coverPath = book.coverPath,
        trackCount = book.trackCount,
        sizeBytes = book.sizeBytes,
        remoteUpdatedAt = book.remoteUpdatedAt?.toEpochMilli(),
        lastFetchedAt = book.lastFetchedAt.toEpochMilli(),
        isDeleted = false,
        localAvailability = book.localAvailability.name,
    )

    private fun authorEntity(author: Author) = AuthorEntity(
        authorKey = EntityKey.of(author.serverId.value, author.id.value),
        serverId = author.serverId.value,
        remoteId = author.id.value,
        name = author.name,
    )

    private fun seriesEntity(series: Series) = SeriesEntity(
        seriesKey = EntityKey.of(series.serverId.value, series.id.value),
        serverId = series.serverId.value,
        remoteId = series.id.value,
        name = series.name,
    )

    private fun trackEntity(track: AudioTrack, bookKey: String) = AudioTrackEntity(
        trackKey = EntityKey.indexed(bookKey, track.index),
        bookKey = bookKey,
        serverId = track.serverId.value,
        trackIndex = track.index,
        remoteFileId = track.remoteFileId,
        startOffsetMillis = track.startOffset.inWholeMilliseconds,
        durationMillis = track.duration.inWholeMilliseconds,
        mimeType = track.mimeType,
        sizeBytes = track.sizeBytes,
        isExcluded = track.isExcluded,
    )

    private fun chapterEntity(chapter: Chapter, bookKey: String) = ChapterEntity(
        chapterKey = EntityKey.indexed(bookKey, chapter.index),
        bookKey = bookKey,
        serverId = chapter.serverId.value,
        chapterIndex = chapter.index,
        title = chapter.title,
        startMillis = chapter.start.inWholeMilliseconds,
        endMillis = chapter.end.inWholeMilliseconds,
    )

    fun toEntity(progress: MediaProgress): MediaProgressEntity {
        val bookKey = EntityKey.of(progress.serverId.value, progress.bookId.value)
        return MediaProgressEntity(
            progressKey = EntityKey.scoped(progress.profileId.value, bookKey),
            profileId = progress.profileId.value,
            bookKey = bookKey,
            serverId = progress.serverId.value,
            positionMillis = progress.position.inWholeMilliseconds,
            durationMillis = progress.duration.inWholeMilliseconds,
            isFinished = progress.isFinished,
            updatedAt = progress.updatedAt.toEpochMilli(),
            hasUnsyncedChanges = progress.hasUnsyncedChanges,
        )
    }

    fun toDomain(relations: BookWithRelations, progress: MediaProgressEntity?): Book {
        val entity = relations.book
        val serverId = ServerId(entity.serverId)
        return Book(
            serverId = serverId,
            id = LibraryItemId(entity.remoteId),
            libraryId = LibraryId(EntityKey.remoteIdOf(entity.libraryKey)),
            title = entity.title,
            subtitle = entity.subtitle,
            authors = relations.authors.map { author ->
                Author(ServerId(author.serverId), AuthorId(author.remoteId), author.name)
            },
            narrators = decode(entity.narratorsJson),
            seriesMemberships = relations.seriesMemberships.map { membership ->
                SeriesMembership(
                    series = Series(
                        serverId = ServerId(membership.series.serverId),
                        id = SeriesId(membership.series.remoteId),
                        name = membership.series.name,
                    ),
                    sequence = SeriesSequence.parse(membership.membership.sequenceRaw),
                    isPrimary = membership.membership.isPrimary,
                )
            },
            duration = entity.durationMillis.milliseconds,
            description = entity.description,
            genres = decode(entity.genresJson),
            tags = decode(entity.tagsJson),
            publishedYear = entity.publishedYear,
            publisher = entity.publisher,
            language = entity.language,
            isExplicit = entity.isExplicit,
            isAbridged = entity.isAbridged,
            coverPath = entity.coverPath,
            trackCount = entity.trackCount,
            sizeBytes = entity.sizeBytes,
            remoteUpdatedAt = entity.remoteUpdatedAt?.let(Instant::ofEpochMilli),
            lastFetchedAt = Instant.ofEpochMilli(entity.lastFetchedAt),
            progress = progress?.let { stored ->
                MediaProgress(
                    serverId = serverId,
                    profileId = ProfileId(stored.profileId),
                    bookId = LibraryItemId(entity.remoteId),
                    position = stored.positionMillis.milliseconds,
                    duration = stored.durationMillis.milliseconds,
                    isFinished = stored.isFinished,
                    updatedAt = Instant.ofEpochMilli(stored.updatedAt),
                    hasUnsyncedChanges = stored.hasUnsyncedChanges,
                )
            },
            localAvailability = LocalAvailability.entries
                .firstOrNull { it.name == entity.localAvailability }
                ?: LocalAvailability.NotDownloaded,
        )
    }

    // --- Sync state -------------------------------------------------------------------------------

    fun toEntity(state: SyncState) = SyncStateEntity(
        profileId = state.profileId.value,
        serverId = state.serverId.value,
        status = state.status.name,
        lastSuccessfulSyncAt = state.lastSuccessfulSyncAt?.toEpochMilli(),
        lastAttemptedAt = state.lastAttemptedAt?.toEpochMilli(),
        lastErrorCode = state.lastError?.code,
        lastErrorSummary = state.lastError?.summary,
    )

    fun toDomain(entity: SyncStateEntity) = SyncState(
        serverId = ServerId(entity.serverId),
        profileId = ProfileId(entity.profileId),
        status = SyncStatus.entries.firstOrNull { it.name == entity.status }
            ?: SyncStatus.NeverSynced,
        lastSuccessfulSyncAt = entity.lastSuccessfulSyncAt?.let(Instant::ofEpochMilli),
        lastAttemptedAt = entity.lastAttemptedAt?.let(Instant::ofEpochMilli),
        // The stored summary is re-presented as a generic server error; reconstructing the original
        // typed error from two columns would be a guess, and a wrong `isRetryable` is worse than a
        // conservative one.
        lastError = entity.lastErrorSummary?.let { summary ->
            AppError.Server(summary = summary)
        },
    )

    private fun encode(values: List<String>): String = StringListConverters.fromStringList(values)

    private fun decode(encoded: String): List<String> = StringListConverters.toStringList(encoded)

    private const val EMPTY_JSON_ARRAY = "[]"
}

/** The rows one [BookSnapshot] expands into. */
internal data class BookRows(
    val book: BookEntity,
    val authors: List<AuthorEntity>,
    val authorLinks: List<BookAuthorCrossRef>,
    val series: List<SeriesEntity>,
    val seriesLinks: List<BookSeriesCrossRef>,
    val tracks: List<AudioTrackEntity>,
    val chapters: List<ChapterEntity>,
    val progress: MediaProgressEntity?,
)
