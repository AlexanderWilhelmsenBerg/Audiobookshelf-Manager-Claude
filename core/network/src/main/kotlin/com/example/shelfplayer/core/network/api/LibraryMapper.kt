package com.example.shelfplayer.core.network.api

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.SeriesSequence
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
import com.example.shelfplayer.core.model.playback.FinishedThreshold
import java.time.Instant
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Turns the library wire types into `:core:model` types.
 *
 * PRODUCT_SPEC SYNC-001: a field the server did not send is an
 * [AppError.ApiCompatibility] naming it, never a crash and never a silently-empty value. What counts as
 * required is deliberately narrow — an id and a title — because a metadata field the server happens not
 * to have is normal, while an item with no id cannot be stored at all.
 */
internal object LibraryMapper {

    fun toLibraries(serverId: ServerId, dto: LibrariesResponseDto, fetchedAt: Instant): AppResult<List<Library>> {
        val libraries = dto.libraries.map { library ->
            val id = library.id?.takeIf(String::isNotBlank)
                ?: return AppResult.Failure(missingField("libraries[].id"))
            Library(
                serverId = serverId,
                id = LibraryId(id),
                name = library.name?.takeIf(String::isNotBlank) ?: id,
                kind = LibraryKind.fromWireValue(library.mediaType),
                displayOrder = library.displayOrder,
                // The book count comes from Room after the sync, not from here: the list endpoint does
                // not report one, and `total` on the items response is only known after fetching it.
                bookCount = 0,
                remoteUpdatedAt = library.lastUpdate?.let(::instantOrNull),
                lastFetchedAt = fetchedAt,
                // PRODUCT_SPEC PLAY-004 / ADR-0013 — the library's own finished rule, which the app
                // inherits rather than argues with. Absent settings mean no rule, not zero seconds.
                finishedWhenRemaining =
                FinishedThreshold.libraryRule(library.settings?.markAsFinishedTimeRemaining),
            )
        }
        return AppResult.Success(libraries)
    }

    /**
     * The ids in one library's catalogue, in server order.
     *
     * Used to decide *which* items the expansion pass has to fetch and which the sweep may treat as
     * removed. The rows themselves are mapped by [toCatalogueSnapshots], which is what makes the shelf
     * browsable before that pass has run.
     */
    fun toItemIds(dto: LibraryItemsResponseDto): AppResult<List<LibraryItemId>> {
        val results = dto.results
            ?: return AppResult.Failure(missingField("results"))
        val ids = results.map { item ->
            val id = item.id?.takeIf(String::isNotBlank)
                ?: return AppResult.Failure(missingField("results[].id"))
            LibraryItemId(id)
        }
        return AppResult.Success(ids)
    }

    /**
     * PRODUCT_SPEC LIB-001 — the catalogue response as browsable books, without the per-item fetch.
     *
     * The list endpoint already carries almost everything the browse surface renders: title, subtitle,
     * description, genres, tags, publisher, year, language, ISBN, ASIN, duration, size, cover path,
     * `numTracks` and `addedAt`. Discarding all of it and keeping only the ids — which is what
     * [toItemIds] alone amounted to — is what made a shelf wait for 491 round trips instead of one.
     *
     * What it does **not** carry, and why the expanded fetch still has to follow:
     *
     *  - `media.tracks` and `media.chapters`. Without track offsets a downloaded book cannot be resumed
     *    (PRODUCT_SPEC 2.3), so these are not optional — only deferrable.
     *  - structured `metadata.authors` / `metadata.series`. The list has `authorName` and `seriesName`
     *    as display strings with no ids and, decisively, **no sequence** — and a series without
     *    positions is not `LIB-003`'s series. So a catalogue book carries no author or series links at
     *    all rather than links invented from a name, and the axes that group by them fill in as the
     *    expansion pass lands. A wrong link is worse than a late one.
     *
     * An item the list cannot describe is skipped rather than failing the catalogue: the expansion pass
     * gets its own chance at it, and one malformed row must not cost the user every other book.
     */
    fun toCatalogueSnapshots(
        serverId: ServerId,
        libraryId: LibraryId,
        dto: LibraryItemsResponseDto,
        fetchedAt: Instant,
    ): List<BookSnapshot> = dto.results.orEmpty().mapNotNull { item ->
        if (catalogueRejectionFor(item) != null) return@mapNotNull null
        val media = checkNotNull(item.media)
        val metadata = checkNotNull(media.metadata)
        val bookId = LibraryItemId(checkNotNull(item.id))
        BookSnapshot(
            book = bookOf(
                serverId = serverId,
                libraryId = libraryId,
                bookId = bookId,
                media = media,
                metadata = metadata,
                title = checkNotNull(metadata.title),
                // Reported by the list rather than counted from tracks it does not send. PRODUCT_SPEC
                // PLAY-003 excludes non-playable tracks from the count, and the list cannot see which
                // those are — so this number is corrected by the expansion pass. It is the one field a
                // catalogue book can be briefly wrong about, and "12 tracks" reading as "11" for a few
                // seconds is a smaller cost than a blank shelf.
                trackCount = media.numTracks,
                dto = item,
                fetchedAt = fetchedAt,
                progress = null,
                authors = emptyList(),
                seriesMemberships = emptyList(),
            ),
            tracks = emptyList(),
            chapters = emptyList(),
        )
    }

    /**
     * PRODUCT_SPEC LIB-002 — server search hits as storable books.
     *
     * A hit that cannot be mapped is **dropped, not fatal**. Search is an enrichment of results the
     * cache already produced; one unusable item in a result set is not a reason to report that the
     * search failed and show the user nothing. That is the same rule LIB-001 applies to optional
     * sections of a sync, for the same reason.
     */
    fun toSearchSnapshots(
        serverId: ServerId,
        libraryId: LibraryId,
        profileId: ProfileId,
        dto: LibrarySearchResponseDto,
        fetchedAt: Instant,
    ): List<BookSnapshot> = dto.book.mapNotNull { hit ->
        val item = hit.libraryItem ?: return@mapNotNull null
        when (val mapped = toSnapshot(serverId, libraryId, profileId, item, fetchedAt)) {
            is AppResult.Success -> mapped.value
            is AppResult.Failure -> null
        }
    }

    /**
     * One expanded item as a complete [BookSnapshot].
     *
     * [profileId] scopes the progress: PRODUCT_SPEC 5.2 keeps one account's position out of another's,
     * and `userMediaProgress` is *this* profile's, because the request carried this profile's credential.
     */
    fun toSnapshot(
        serverId: ServerId,
        libraryId: LibraryId,
        profileId: ProfileId,
        dto: LibraryItemDto,
        fetchedAt: Instant,
    ): AppResult<BookSnapshot> {
        rejectionFor(dto)?.let { return AppResult.Failure(it) }

        // Every dereference below is guarded by `rejectionFor`, which has already returned for each of
        // these being absent. Collecting the checks there rather than interleaving them with the mapping
        // keeps one readable list of everything that makes an item unusable — the same shape `AuthMapper`
        // uses for the same reason.
        val media = checkNotNull(dto.media)
        val metadata = checkNotNull(media.metadata)
        val bookId = LibraryItemId(checkNotNull(dto.id))
        val title = checkNotNull(metadata.title)
        val tracks = checkNotNull(media.tracks)
        return AppResult.Success(
            BookSnapshot(
                book = bookOf(
                    serverId = serverId,
                    libraryId = libraryId,
                    bookId = bookId,
                    media = media,
                    metadata = metadata,
                    title = title,
                    // PRODUCT_SPEC PLAY-003: an excluded track is not playable, so it is not counted.
                    trackCount = tracks.count { !it.exclude },
                    dto = dto,
                    fetchedAt = fetchedAt,
                    progress = progressOf(serverId, profileId, bookId, dto),
                    authors = authorsOf(serverId, metadata),
                    seriesMemberships = seriesOf(serverId, metadata),
                ),
                tracks = tracks.map { track -> trackOf(serverId, bookId, track) },
                chapters = media.chapters.orEmpty().mapIndexed { index, chapter ->
                    chapterOf(serverId, bookId, index, chapter)
                },
            ),
        )
    }

    /**
     * Every reason an item cannot be stored, in one place.
     *
     * `null` means the item is usable. The list is short on purpose: a metadata field the server happens
     * not to have is normal and maps to `null`, while these five are what a stored, browsable, playable
     * book cannot exist without.
     */
    /**
     * The parts of a [Book] both responses describe the same way.
     *
     * Extracted so the catalogue pass and the expanded pass cannot drift: every field here is one the
     * list endpoint carries, and the four that differ — track count, progress, authors, series — are
     * parameters precisely because they are the ones only an expanded item can answer.
     */
    @Suppress("LongParameterList")
    private fun bookOf(
        serverId: ServerId,
        libraryId: LibraryId,
        bookId: LibraryItemId,
        media: MediaDto,
        metadata: MediaMetadataDto,
        title: String,
        trackCount: Int,
        dto: LibraryItemDto,
        fetchedAt: Instant,
        progress: MediaProgress?,
        authors: List<Author>,
        seriesMemberships: List<SeriesMembership>,
    ) = Book(
        serverId = serverId,
        id = bookId,
        libraryId = libraryId,
        title = title,
        subtitle = metadata.subtitle?.takeIf(String::isNotBlank),
        authors = authors,
        narrators = narratorsOf(metadata),
        seriesMemberships = seriesMemberships,
        duration = seconds(media.duration),
        // `descriptionPlain` first: PRODUCT_SPEC LIB-004 requires HTML descriptions to be sanitized
        // before rendering, and the server has already done that work. The HTML form is the fallback,
        // and rendering it still has to sanitize.
        description = metadata.descriptionPlain?.takeIf(String::isNotBlank)
            ?: metadata.description?.takeIf(String::isNotBlank),
        genres = metadata.genres.filter(String::isNotBlank),
        tags = media.tags.filter(String::isNotBlank),
        // A string on the wire, because the server keeps whatever the tag held. A value that is not a
        // year is dropped rather than coerced to 0, which would render as "0".
        publishedYear = metadata.publishedYear?.trim()?.toIntOrNull(),
        publisher = metadata.publisher?.takeIf(String::isNotBlank),
        language = metadata.language?.takeIf(String::isNotBlank),
        // PRODUCT_SPEC LIB-002. Both keys are present in the captured fixtures and both are null there
        // — the seeded book has neither — so the *presence* is observed and the string type is taken
        // from Audiobookshelf's documented metadata schema. See `docs/api-compatibility.md`. Trimmed,
        // because a tag editor leaves trailing spaces and " 9780000000000" would never match what the
        // user typed.
        isbn = metadata.isbn?.trim()?.takeIf(String::isNotEmpty),
        asin = metadata.asin?.trim()?.takeIf(String::isNotEmpty),
        isExplicit = metadata.explicit,
        isAbridged = metadata.abridged,
        coverPath = media.coverPath?.takeIf(String::isNotBlank),
        trackCount = trackCount,
        sizeBytes = media.size,
        remoteUpdatedAt = dto.updatedAt?.let(::instantOrNull),
        addedAt = dto.addedAt?.let(::instantOrNull),
        lastFetchedAt = fetchedAt,
        progress = progress,
        // PRODUCT_SPEC DL-001: nothing is downloaded until a download commits. The repository preserves
        // an existing local state rather than taking this value.
        localAvailability = LocalAvailability.NotDownloaded,
    )

    /**
     * What a *catalogue* row cannot be stored without: an id, a media object, metadata, a title.
     *
     * Deliberately shorter than [rejectionFor]. That one also demands `media.tracks`, because an item
     * fetched with `expanded=1` and no tracks means the caller asked wrongly — but the list endpoint
     * never sends tracks, so applying the same rule here would reject every row.
     */
    private fun catalogueRejectionFor(dto: LibraryItemDto): AppError? = when {
        dto.id.isNullOrBlank() -> missingField("id")
        dto.media == null -> missingField("media")
        dto.media.metadata == null -> missingField("media.metadata")
        dto.media.metadata.title.isNullOrBlank() -> missingField("media.metadata.title")
        else -> null
    }

    private fun rejectionFor(dto: LibraryItemDto): AppError? = when {
        dto.id.isNullOrBlank() -> missingField("id")
        dto.media == null -> missingField("media")
        dto.media.metadata == null -> missingField("media.metadata")
        dto.media.metadata.title.isNullOrBlank() -> missingField("media.metadata.title")
        // `null` — not empty — means the response was minified, which is a caller error rather than a
        // server problem: an item fetched without `expanded=1` cannot be stored, and storing it with an
        // empty track list would produce a book that browses but cannot play.
        dto.media.tracks == null -> AppError.ApiCompatibility(
            summary = "This server's item response is missing information this app needs.",
            missingField = "media.tracks",
        )

        else -> null
    }

    /**
     * Expanded metadata first, the minified string second.
     *
     * A minified item names its author only as `authorName`, with no id. Synthesising an id from the name
     * would create an author row that a later expanded sync duplicates under the real id, so the
     * fallback carries the name with a name-derived id and the two are reconciled by the expanded fetch
     * that always follows.
     */
    private fun authorsOf(serverId: ServerId, metadata: MediaMetadataDto): List<Author> {
        val expanded = metadata.authors
        if (expanded != null) {
            return expanded.mapNotNull { author ->
                val id = author.id?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val name = author.name?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                Author(serverId, AuthorId(id), name)
            }
        }
        return metadata.authorName?.takeIf(String::isNotBlank)
            ?.let { name -> listOf(Author(serverId, AuthorId("name:$name"), name)) }
            .orEmpty()
    }

    /**
     * `narrators` is an array on an expanded item and `narratorName` a comma-separated string otherwise.
     *
     * The split is on the minified form only, and it is lossy for a narrator credited as "Smith, John" —
     * which is exactly why the expanded array is preferred and why nothing is stored from the minified
     * path.
     */
    private fun narratorsOf(metadata: MediaMetadataDto): List<String> {
        metadata.narrators?.let { return it.filter(String::isNotBlank) }
        return metadata.narratorName?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()
    }

    private fun seriesOf(serverId: ServerId, metadata: MediaMetadataDto): List<SeriesMembership> {
        val expanded = metadata.series ?: return emptyList()
        return expanded.mapIndexedNotNull { index, membership ->
            val id = membership.id?.takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
            val name = membership.name?.takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
            SeriesMembership(
                series = Series(serverId, SeriesId(id), name),
                sequence = SeriesSequence.parse(membership.sequence),
                // PRODUCT_SPEC LIB-003: "when none is selected, it uses the first server-provided ordered
                // series and records that choice". First is primary until the user picks one.
                isPrimary = index == 0,
            )
        }
    }

    private fun trackOf(serverId: ServerId, bookId: LibraryItemId, dto: AudioTrackDto) = AudioTrack(
        serverId = serverId,
        bookId = bookId,
        index = dto.index,
        // PRODUCT_SPEC 13.1 wants the remote file id in download identity. It is the last segment of
        // `contentUrl`; the filename is the fallback, because a track with neither cannot be addressed.
        remoteFileId = dto.contentUrl?.substringAfterLast('/')?.takeIf(String::isNotBlank)
            ?: dto.metadata?.filename.orEmpty(),
        startOffset = seconds(dto.startOffset),
        duration = seconds(dto.duration),
        mimeType = dto.mimeType?.takeIf(String::isNotBlank),
        sizeBytes = dto.metadata?.size ?: 0,
        isExcluded = dto.exclude,
    )

    private fun chapterOf(serverId: ServerId, bookId: LibraryItemId, index: Int, dto: ChapterDto) = Chapter(
        serverId = serverId,
        bookId = bookId,
        // `dto.id` is an index within the item and the fixture shows it repeating as 0 for every
        // chapter, so the position in the array is what identifies a chapter, not the field named `id`.
        index = index,
        title = dto.title.orEmpty(),
        start = seconds(dto.start),
        end = seconds(dto.end),
    )

    private fun progressOf(
        serverId: ServerId,
        profileId: ProfileId,
        bookId: LibraryItemId,
        dto: LibraryItemDto,
    ): MediaProgress? = dto.userMediaProgress?.let { progress ->
        MediaProgress(
            serverId = serverId,
            profileId = profileId,
            bookId = bookId,
            position = seconds(progress.currentTime),
            duration = seconds(progress.duration),
            isFinished = progress.isFinished,
            updatedAt = progress.lastUpdate?.let(::instantOrNull) ?: Instant.EPOCH,
            // Server-sourced progress is by definition already synced.
            hasUnsyncedChanges = false,
        )
    }

    /**
     * Seconds to a [Duration], rounded to the nearest millisecond.
     *
     * The server sends fractional seconds and the entities store integer milliseconds. Rounding rather
     * than truncating keeps a saved position from creeping backwards by up to a millisecond on every
     * round trip (product priority 2).
     */
    private fun seconds(value: Double): Duration =
        if (value.isFinite() && value > 0) (value * MILLIS_PER_SECOND).roundToLong().milliseconds else Duration.ZERO

    /** The server sends `0` for "never", which as an instant would read as 1970. */
    private fun instantOrNull(epochMillis: Long): Instant? = epochMillis.takeIf { it > 0 }?.let(Instant::ofEpochMilli)

    private fun missingField(field: String): AppError = AppError.ApiCompatibility(
        summary = "This server's library response is missing information this app needs.",
        missingField = field,
    )

    private const val MILLIS_PER_SECOND = 1_000
}
