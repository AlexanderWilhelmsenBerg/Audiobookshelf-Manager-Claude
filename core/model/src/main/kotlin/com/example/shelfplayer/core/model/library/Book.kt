package com.example.shelfplayer.core.model.library

import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.SeriesSequence
import com.example.shelfplayer.core.model.ServerId
import java.time.Instant
import kotlin.time.Duration

/**
 * PRODUCT_SPEC LIB-004 — an audiobook as the UI needs it.
 *
 * This is the shape Room hands to the UI. Network DTOs never reach this far (PRODUCT_SPEC 9.1).
 */
data class Book(
    val serverId: ServerId,
    val id: LibraryItemId,
    val libraryId: LibraryId,
    val title: String,
    val subtitle: String?,
    val authors: List<Author>,
    val narrators: List<String>,
    val seriesMemberships: List<SeriesMembership>,
    val duration: Duration,
    val description: String?,
    val genres: List<String>,
    val tags: List<String>,
    val publishedYear: Int?,
    val publisher: String?,
    val language: String?,
    val isExplicit: Boolean,
    val isAbridged: Boolean,
    val coverPath: String?,
    val trackCount: Int,
    val sizeBytes: Long,
    val remoteUpdatedAt: Instant?,
    val lastFetchedAt: Instant,
    val progress: MediaProgress?,
    val localAvailability: LocalAvailability,
)

/** PRODUCT_SPEC LIB-004 — local and remote availability are independently visible. */
enum class LocalAvailability {
    /** No local copy exists. */
    NotDownloaded,

    /** A download is queued or running; not playable yet (PRODUCT_SPEC DL-001). */
    Partial,

    /** Every required track, the cover and the offline manifest are committed. */
    Complete,
}

data class Author(val serverId: ServerId, val id: AuthorId, val name: String)

data class Series(val serverId: ServerId, val id: SeriesId, val name: String)

/**
 * PRODUCT_SPEC LIB-003 — a book may belong to several series, and each membership has its own
 * position. The UI shows every membership; the smart downloader picks one primary series.
 */
data class SeriesMembership(val series: Series, val sequence: SeriesSequence, val isPrimary: Boolean)
