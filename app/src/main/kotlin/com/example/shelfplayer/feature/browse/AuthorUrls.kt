package com.example.shelfplayer.feature.browse

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.net.toUri
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Author

/**
 * LIB-001 / LIB-002 — resolves the documented Audiobookshelf author-image route at render time.
 *
 * The server address belongs to the server row, while an author carries its remote id and the synchronized
 * portrait facts but not the address. Keeping
 * the resolver in a CompositionLocal mirrors [CoverUrls] and avoids remapping every group when an
 * account changes. Coil receives the bare URL and supplies authentication through the shared image
 * client; no credential is ever placed in the query string.
 *
 * The URL exists only after the captured author-directory contract confirmed a non-null image path. The
 * private server filesystem path never reaches this layer; [Author.hasPortrait] is the flag and the real
 * [Author.remoteUpdatedAt] becomes the public endpoint's `ts` cache key.
 */
@Immutable
fun interface AuthorUrls {
    fun forAuthor(author: Author): String?

    companion object {
        val None = AuthorUrls { null }
    }
}

/** Builds `/api/authors/{id}/image`, but only for a portrait positively reported by synchronization. */
fun authorUrlsFor(baseUrls: Map<ServerId, String>): AuthorUrls = AuthorUrls { author ->
    if (!author.hasPortrait) return@AuthorUrls null
    val base = baseUrls[author.serverId]?.trimEnd('/') ?: return@AuthorUrls null
    val builder = base.toUri()
        .buildUpon()
        .appendPath("api")
        .appendPath("authors")
        .appendPath(author.id.value)
        .appendPath("image")
    author.remoteUpdatedAt?.let { updatedAt -> builder.appendQueryParameter("ts", updatedAt.toEpochMilli().toString()) }
    builder.build().toString()
}

val LocalAuthorUrls = staticCompositionLocalOf { AuthorUrls.None }
