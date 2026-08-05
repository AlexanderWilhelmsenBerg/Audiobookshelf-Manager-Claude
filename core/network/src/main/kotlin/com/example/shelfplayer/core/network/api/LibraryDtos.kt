package com.example.shelfplayer.core.network.api

import kotlinx.serialization.Serializable

/**
 * Wire types for the library read endpoints, verified against Audiobookshelf 2.36.0.
 *
 * PRODUCT_SPEC 9.3: these never leave `:core:network`. Every field is nullable or defaulted, because
 * `ignoreUnknownKeys` protects against *added* fields but not against removed ones, and a missing field
 * must surface as [com.example.shelfplayer.core.model.AppError.ApiCompatibility] rather than as a
 * deserialization crash (PRODUCT_SPEC SYNC-001).
 *
 * ### The distinction that shapes this file
 *
 * `GET /api/libraries/{id}/items` returns **minified** items: `media` carries `numTracks`,
 * `numChapters` and `numAudioFiles` as counts, and `media.metadata` carries `authorName` and
 * `seriesName` as *strings*. There is no `tracks` array, no `chapters` array, no `authors` array and no
 * `series` array. Only `GET /api/items/{id}?expanded=1` has those.
 *
 * One DTO set covers both, with the expanded-only fields nullable. Two parallel DTO sets would be the
 * alternative and would drift: the shared fields are the same objects on the server, and a change to one
 * would silently not be applied to the other.
 *
 * Shapes are recorded in `docs/api-compatibility.md` and covered by the committed fixtures
 * `libraries.json`, `library-items.json` and `library-item.json`.
 */
@Serializable
internal data class LibrariesResponseDto(val libraries: List<LibraryDto> = emptyList())

@Serializable
internal data class LibraryDto(
    val id: String? = null,
    val name: String? = null,
    /** `book` or `podcast`; anything else degrades to `LibraryKind.Unknown`. */
    val mediaType: String? = null,
    val displayOrder: Int = 0,
    /** Epoch millis of the last server-side scan, or `null` on a library never scanned. */
    val lastScan: Long? = null,
    val lastUpdate: Long? = null,
)

/**
 * `GET /api/libraries/{id}/items`, and the same envelope for `/series`.
 *
 * [total] is the server's count *before* paging, which is how a caller knows whether it has everything.
 * The capture requested no page size and the server answered `limit: 0` with every item, but relying on
 * that would be relying on a default rather than on a contract.
 */
@Serializable
internal data class LibraryItemsResponseDto(
    val results: List<LibraryItemDto> = emptyList(),
    val total: Int = 0,
    val page: Int = 0,
    val limit: Int = 0,
)

@Serializable
internal data class LibraryItemDto(
    val id: String? = null,
    val libraryId: String? = null,
    val mediaType: String? = null,
    val media: MediaDto? = null,
    /** Epoch millis. Used for PRODUCT_SPEC 13.2's `remoteUpdatedAt`. */
    val updatedAt: Long? = null,
    val addedAt: Long? = null,
    val size: Long = 0,
    val numFiles: Int = 0,
    /**
     * The server's own "this item's files are gone" and "this item could not be parsed" flags.
     *
     * Both are read rather than ignored: PRODUCT_SPEC 13.2 has an `isDeleted` column, and an item the
     * server itself calls missing is exactly what it is for. Storing such an item as browsable would
     * offer the user a book that cannot be played.
     */
    val isMissing: Boolean = false,
    val isInvalid: Boolean = false,
    /** Expanded responses only, and only when `include=progress` was requested. */
    val userMediaProgress: MediaProgressDto? = null,
)

@Serializable
internal data class MediaDto(
    val id: String? = null,
    val metadata: MediaMetadataDto? = null,
    val coverPath: String? = null,
    /** Seconds, fractional. The entities store milliseconds. */
    val duration: Double = 0.0,
    val size: Long = 0,
    val tags: List<String> = emptyList(),
    /** Minified responses carry the counts; expanded ones carry [tracks] and [chapters] as well. */
    val numTracks: Int = 0,
    val numChapters: Int = 0,
    val numAudioFiles: Int = 0,
    /** `null` on a minified response — *not* "this book has no tracks". See [LibraryMapper]. */
    val tracks: List<AudioTrackDto>? = null,
    val chapters: List<ChapterDto>? = null,
)

/**
 * PRODUCT_SPEC LIB-004 — the metadata a details screen shows.
 *
 * [authorName] and [seriesName] are the minified forms and are deliberately kept: a caller that only has
 * a minified item still needs something to display, and dropping them would make the list screen
 * authorless. [authors], [series] and [narrators] arrive only on an expanded item.
 */
@Serializable
internal data class MediaMetadataDto(
    val title: String? = null,
    val subtitle: String? = null,
    val authorName: String? = null,
    val seriesName: String? = null,
    val narratorName: String? = null,
    val description: String? = null,
    val descriptionPlain: String? = null,
    val genres: List<String> = emptyList(),
    /** A string on the wire, not a number: the server stores whatever the tag contained. */
    val publishedYear: String? = null,
    val publisher: String? = null,
    val language: String? = null,
    val explicit: Boolean = false,
    val abridged: Boolean = false,
    val isbn: String? = null,
    val asin: String? = null,
    val authors: List<AuthorRefDto>? = null,
    val series: List<SeriesRefDto>? = null,
    val narrators: List<String>? = null,
)

@Serializable
internal data class AuthorRefDto(val id: String? = null, val name: String? = null)

/**
 * A series membership.
 *
 * [sequence] is a free-form string on the wire — `"1"`, `"2.5"`, `"Book Two"` — which is why
 * `SeriesSequence.parse` exists and why PRODUCT_SPEC LIB-003 has a rule for non-numeric values.
 */
@Serializable
internal data class SeriesRefDto(val id: String? = null, val name: String? = null, val sequence: String? = null)

/**
 * PRODUCT_SPEC 11.3 — [startOffset] is the track's position on the global book timeline, in seconds.
 *
 * The server provides it, so the app does not derive it by summing durations. That matters: a derived
 * offset drifts from the server's by rounding on every track, and a drifting offset corrupts a saved
 * position (product priority 2).
 */
@Serializable
internal data class AudioTrackDto(
    val index: Int = 0,
    val startOffset: Double = 0.0,
    val duration: Double = 0.0,
    val mimeType: String? = null,
    /** `/api/items/{itemId}/file/{fileId}`; the file id is the server's inode for the file. */
    val contentUrl: String? = null,
    val exclude: Boolean = false,
    val metadata: AudioFileMetadataDto? = null,
)

@Serializable
internal data class AudioFileMetadataDto(val filename: String? = null, val size: Long = 0)

/** Seconds, fractional. `id` is an index within the item, not a server-wide identifier. */
@Serializable
internal data class ChapterDto(
    val id: Int = 0,
    val title: String? = null,
    val start: Double = 0.0,
    val end: Double = 0.0,
)

/**
 * PRODUCT_SPEC PLAY-004 — one profile's position in one book, as the server has it.
 *
 * Only present on an expanded item requested with `include=progress`.
 */
@Serializable
internal data class MediaProgressDto(
    val currentTime: Double = 0.0,
    val duration: Double = 0.0,
    val isFinished: Boolean = false,
    val lastUpdate: Long? = null,
)
