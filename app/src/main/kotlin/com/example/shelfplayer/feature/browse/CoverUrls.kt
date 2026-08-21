package com.example.shelfplayer.feature.browse

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Book

/**
 * PRODUCT_SPEC LIB-004 / LIB-001 — where a book's cover lives.
 *
 * `media.coverPath` is a path on the **server's** filesystem (`/audiobooks/Some Book/cover.jpg`), not
 * a URL, and PRODUCT_SPEC 3.4 rules out reaching a server's filesystem. It is only ever a *flag*: a
 * book whose `coverPath` is null has no cover, and one that has it is served at
 * `GET /api/items/{id}/cover`.
 *
 * ### Why this is a `CompositionLocal` rather than a field on [Book]
 *
 * The address belongs to the server row, not the book, and a book carries only a [ServerId]. Putting
 * a resolved URL on every book would mean re-mapping 490 rows whenever a profile switched, to change
 * a prefix. Resolving at render time costs a map lookup.
 *
 * The captured contract says this endpoint answers **200 without a credential** on the server the
 * capture ran against, so a bare URL would usually work. It is still fetched through the authenticated
 * client — see `CoverImageLoader`. A deployment behind forward auth would 401 on a bare URL, and
 * "usually works" is not a property worth building on when the alternative is free.
 */
@Immutable
fun interface CoverUrls {
    /** The absolute cover URL, or `null` when the book has none or its server address is unknown. */
    fun forBook(book: Book): String?

    companion object {
        /** Nothing resolves. What a preview gets, and what the app shows before the first server row. */
        val None = CoverUrls { null }
    }
}

/**
 * Builds cover URLs from a snapshot of the known servers.
 *
 * A plain map rather than a flow: it is read during composition, and a server's base address changes
 * about as often as the user adds an account. `ShelfPlayerApp` re-provides it when the set changes.
 */
fun coverUrlsFor(baseUrls: Map<ServerId, String>): CoverUrls = CoverUrls { book ->
    if (book.coverPath == null) return@CoverUrls null
    val base = baseUrls[book.serverId]?.trimEnd('/') ?: return@CoverUrls null
    // PRODUCT_SPEC MGR-002 — "cover cache invalidates after successful update".
    //
    // `?ts=` is the whole mechanism, and without it a changed cover is invisible forever. The image
    // loader is configured with `respectCacheHeaders(false)` and a 128 MB disk cache that nothing
    // evicts, so the URL *is* the cache key: same URL, same bytes, for the life of the install.
    //
    // The server bumps the item's `updatedAt` on every cover change and reads `ts` purely as a cache
    // buster, so a URL that carries it changes exactly when the image does. A book whose timestamp is
    // unknown gets the bare URL rather than a fabricated one — a wrong buster would be worse than none,
    // because it would evict on every sync.
    val version = book.remoteUpdatedAt?.toEpochMilli()
    if (version == null) {
        "$base/api/items/${book.id.value}/cover"
    } else {
        "$base/api/items/${book.id.value}/cover?ts=$version"
    }
}

val LocalCoverUrls = staticCompositionLocalOf { CoverUrls.None }
