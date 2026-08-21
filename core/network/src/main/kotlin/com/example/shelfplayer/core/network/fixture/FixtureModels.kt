package com.example.shelfplayer.core.network.fixture

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The on-disk shape of a ShelfPlayer fixture library.
 *
 * ### This is not an Audiobookshelf response
 *
 * PRODUCT_SPEC 22.4 forbids inventing server endpoints or response fields. These types therefore
 * describe a *ShelfPlayer-owned* format, not the Audiobookshelf wire format: nothing here should be
 * read as documentation of what a real server returns.
 *
 * Contract fixtures captured from a real server are a separate thing entirely. They arrive in
 * Phase 1 under `core/network/src/test/resources/contract/`, are byte-for-byte server responses, and
 * are recorded in `docs/api-compatibility.md`.
 */
@Serializable
data class FixtureLibraryDocument(
    @SerialName("schemaVersion") val schemaVersion: Int,
    @SerialName("server") val server: FixtureServer,
    @SerialName("profile") val profile: FixtureProfile,
    @SerialName("capabilities") val capabilities: List<String> = emptyList(),
    @SerialName("libraries") val libraries: List<FixtureLibrary> = emptyList(),
    @SerialName("authors") val authors: List<FixtureAuthor> = emptyList(),
    @SerialName("series") val series: List<FixtureSeries> = emptyList(),
    @SerialName("books") val books: List<FixtureBook> = emptyList(),
)

@Serializable
data class FixtureServer(
    @SerialName("id") val id: String,
    @SerialName("displayName") val displayName: String,
    @SerialName("baseUrl") val baseUrl: String,
    @SerialName("version") val version: String? = null,
)

@Serializable
data class FixtureProfile(
    @SerialName("id") val id: String,
    @SerialName("username") val username: String,
    @SerialName("displayName") val displayName: String,
    @SerialName("role") val role: String,
)

@Serializable
data class FixtureLibrary(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("kind") val kind: String,
    @SerialName("displayOrder") val displayOrder: Int = 0,
    /**
     * PRODUCT_SPEC PLAY-004 / ADR-0013 — the library's own finished rule, in the *server's* unit.
     *
     * Seconds, named as the wire names it, so the fixture is readable beside a real `GET /api/libraries`
     * response. Optional: a fixture library that omits it is a library with no rule, which is what most are.
     */
    @SerialName("markAsFinishedTimeRemaining") val markAsFinishedTimeRemaining: Long? = null,
)

@Serializable
data class FixtureAuthor(@SerialName("id") val id: String, @SerialName("name") val name: String)

@Serializable
data class FixtureSeries(@SerialName("id") val id: String, @SerialName("name") val name: String)

@Serializable
data class FixtureBook(
    @SerialName("id") val id: String,
    @SerialName("libraryId") val libraryId: String,
    @SerialName("title") val title: String,
    @SerialName("subtitle") val subtitle: String? = null,
    @SerialName("authorIds") val authorIds: List<String> = emptyList(),
    @SerialName("narrators") val narrators: List<String> = emptyList(),
    @SerialName("series") val series: List<FixtureSeriesMembership> = emptyList(),
    @SerialName("durationSeconds") val durationSeconds: Long = 0,
    @SerialName("description") val description: String? = null,
    @SerialName("genres") val genres: List<String> = emptyList(),
    @SerialName("tags") val tags: List<String> = emptyList(),
    @SerialName("publishedYear") val publishedYear: Int? = null,
    @SerialName("publisher") val publisher: String? = null,
    @SerialName("language") val language: String? = null,
    /** PRODUCT_SPEC LIB-002 — optional, so the bundled fixtures that predate them still parse. */
    @SerialName("isbn") val isbn: String? = null,
    @SerialName("asin") val asin: String? = null,
    @SerialName("explicit") val explicit: Boolean = false,
    @SerialName("abridged") val abridged: Boolean = false,
    @SerialName("coverPath") val coverPath: String? = null,
    @SerialName("sizeBytes") val sizeBytes: Long = 0,
    @SerialName("tracks") val tracks: List<FixtureTrack> = emptyList(),
    @SerialName("chapters") val chapters: List<FixtureChapter> = emptyList(),
    @SerialName("progress") val progress: FixtureProgress? = null,
    /**
     * PRODUCT_SPEC LIB-001 — the server's own "last changed" stamp for this item.
     *
     * Optional, like `isbn` and `asin`, so a fixture written before this field still parses. It exists
     * because the *absence* of a stamp disables the skip-unchanged path entirely — `isUpToDate` requires
     * a non-null value — and a fake library with no stamps therefore cannot exercise the single most
     * important behaviour of a real refresh. A library-emptying defect shipped through that blind spot.
     */
    @SerialName("updatedAtEpochSeconds") val updatedAtEpochSeconds: Long? = null,
)

@Serializable
data class FixtureSeriesMembership(
    @SerialName("seriesId") val seriesId: String,
    @SerialName("sequence") val sequence: String? = null,
    @SerialName("primary") val primary: Boolean = false,
)

@Serializable
data class FixtureTrack(
    @SerialName("index") val index: Int,
    @SerialName("fileId") val fileId: String,
    @SerialName("startOffsetSeconds") val startOffsetSeconds: Long,
    @SerialName("durationSeconds") val durationSeconds: Long,
    @SerialName("mimeType") val mimeType: String? = null,
    @SerialName("sizeBytes") val sizeBytes: Long = 0,
    @SerialName("excluded") val excluded: Boolean = false,
)

@Serializable
data class FixtureChapter(
    @SerialName("index") val index: Int,
    @SerialName("title") val title: String,
    @SerialName("startSeconds") val startSeconds: Long,
    @SerialName("endSeconds") val endSeconds: Long,
)

@Serializable
data class FixtureProgress(
    @SerialName("positionSeconds") val positionSeconds: Long,
    @SerialName("finished") val finished: Boolean = false,
    @SerialName("updatedAtEpochSeconds") val updatedAtEpochSeconds: Long,
)
