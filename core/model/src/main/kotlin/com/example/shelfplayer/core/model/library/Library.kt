package com.example.shelfplayer.core.model.library

import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.ServerId
import java.time.Instant

/** PRODUCT_SPEC LIB-001 — a library the authenticated account can see. */
data class Library(
    val serverId: ServerId,
    val id: LibraryId,
    val name: String,
    val kind: LibraryKind,
    val displayOrder: Int,
    val bookCount: Int,
    val remoteUpdatedAt: Instant?,
    val lastFetchedAt: Instant,
)

/**
 * The media kind a library holds.
 *
 * [Unknown] exists because PRODUCT_SPEC SYNC-001 requires an unrecognized value to degrade rather
 * than crash: a server that grows a new library type must not break browsing of the existing ones.
 */
enum class LibraryKind {
    Book,
    Podcast,
    Unknown,
    ;

    companion object {
        fun fromWireValue(value: String?): LibraryKind = when (value?.lowercase()) {
            "book" -> Book
            "podcast" -> Podcast
            else -> Unknown
        }
    }
}
