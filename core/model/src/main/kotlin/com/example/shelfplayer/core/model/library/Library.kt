package com.example.shelfplayer.core.model.library

import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.ServerId
import java.time.Instant
import kotlin.time.Duration

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
    /**
     * PRODUCT_SPEC PLAY-004 / ADR-0013 — how close to the end **this library** calls finished.
     *
     * The server's `markAsFinishedTimeRemaining`, and where it is set the app uses it rather than the
     * listener's own setting: one rule per book, and it is the one the web interface shows.
     *
     * `null` for a library the server described without the setting, and for every row written before
     * database version 14. `null` means "no rule" rather than "zero seconds", so an un-migrated row leaves
     * the listener's setting standing rather than silently becoming a different rule.
     */
    val finishedWhenRemaining: Duration? = null,
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
