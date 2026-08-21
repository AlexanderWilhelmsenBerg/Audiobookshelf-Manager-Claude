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
    /**
     * PRODUCT_SPEC PLAY-004 / ADR-0013 — the library's own idea of when a book is finished.
     *
     * Present in `libraries.json` since the wave A capture and parsed away until the Phase 2 closeout,
     * which is the gap ADR-0013 recorded. Nullable because a library the server describes without settings
     * is a library with no opinion, and inventing one would be inventing a rule.
     */
    val settings: LibrarySettingsDto? = null,
)

/**
 * The one field of `library.settings` this app reads.
 *
 * One, out of the eleven the capture shows. Nine of the rest are the server's own scanning and matching
 * behaviour — `metadataPrecedence`, `disableWatcher`, `skipMatchingMediaWithAsin` — which a client cannot act
 * on and should not model (PRODUCT_SPEC 9.3: a DTO describes what this app uses, not what the server sends).
 *
 * The tenth is **`markAsFinishedPercentComplete`, deliberately not declared.** It is sent, and it is `null` in
 * every capture ever taken, and an earlier build of this file read it. The project owner rejected the unit
 * outright: a percentage of a long book is a long time, and 95% of a hundred-hour book leaves five hours to
 * go. A field the app parses and does not act on is worse than one it ignores — it reads as support. See
 * ADR-0013.
 *
 * @property markAsFinishedTimeRemaining **seconds**. `10` on the capture server.
 */
@Serializable
internal data class LibrarySettingsDto(val markAsFinishedTimeRemaining: Long? = null)

/**
 * `GET /api/libraries/{id}/items`, and the same envelope for `/series`.
 *
 * [total] is the server's count *before* paging, which is how a caller knows whether it has everything.
 * The capture requested no page size and the server answered `limit: 0` with every item, but relying on
 * that would be relying on a default rather than on a contract.
 */
@Serializable
internal data class LibraryItemsResponseDto(
    val results: List<LibraryItemDto>? = null,
    /** Negative means the required envelope field was absent, not that the library is empty. */
    val total: Int = -1,
    val page: Int = 0,
    val limit: Int = 0,
)

/**
 * `GET /api/libraries/{id}/search?q=`, captured as `library-search.json`.
 *
 * ### Only `book`
 *
 * The response is an object of six arrays — `authors`, `book`, `genres`, `narrators`, `series`,
 * `tags`. On the capture server only `book` came back populated; the other five were `[]`, so their
 * element shapes are **unverified** and PRODUCT_SPEC 22.4 forbids inventing them. They are not declared
 * here, and `ignoreUnknownKeys` discards them. When a capture with an author or series hit exists, they
 * can be added; until then a missing field would be a guess.
 *
 * ### `book[].libraryItem` is the expanded shape
 *
 * Unlike `…/items`, a search hit carries `media.tracks`, `media.chapters`, `metadata.authors` and
 * `metadata.series` — the fixture confirms all four. So a hit maps through the same
 * [LibraryMapper.toSnapshot] as a fetched item and needs no follow-up request.
 *
 * It does **not** carry `userMediaProgress`: the endpoint takes no `include` parameter and the capture
 * has no such key. A search result therefore maps with `progress = null`, which the writer skips rather
 * than storing — a search must never be able to erase a listening position (product priority 2).
 */
@Serializable
internal data class LibrarySearchResponseDto(val book: List<LibrarySearchBookDto> = emptyList())

@Serializable
internal data class LibrarySearchBookDto(val libraryItem: LibraryItemDto? = null)

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
 * Reached two ways, which is why it lives here rather than beside either caller: nested on an expanded
 * item requested with `include=progress`, and as an element of `user.mediaProgress` on
 * `POST /api/authorize` and `GET /api/me` (`contracts/media-progress.json`, observed 2026-08-06).
 *
 * [libraryItemId] is absent in the first case — the item is already known from the request — and
 * present in the second, where it is the only thing identifying which book the position belongs to.
 *
 * The units are the trap. [currentTime] and [duration] are **seconds**: `42.5` is forty-two and a half.
 * [lastUpdate] is milliseconds. Reading either as the other silently misplaces every position stored.
 */
@Serializable
internal data class MediaProgressDto(
    val libraryItemId: String? = null,
    val currentTime: Double = 0.0,
    val duration: Double = 0.0,
    val isFinished: Boolean = false,
    val lastUpdate: Long? = null,
)
