package com.example.shelfplayer.di

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import com.example.shelfplayer.core.network.di.AuthenticatedClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * PRODUCT_SPEC LIB-001 / LIB-004 — the image pipeline covers are fetched through.
 *
 * ### Why the authenticated client
 *
 * The captured contract records `unauthenticatedStatus: 200` — the server the capture ran against
 * serves `/api/items/{id}/cover` to anyone holding the item id. Coil's default loader would therefore
 * work against it.
 *
 * It is given the app's authenticated client anyway. A deployment behind a reverse proxy with forward
 * auth, or a future Audiobookshelf release, will answer `401` to an anonymous request, and "works
 * against the one server we captured" is not a property worth building on when the alternative costs a
 * single binding. The same client also carries the user agent and the redacting log interceptor, so a
 * cover request is subject to exactly the rules every other request is — including PRODUCT_SPEC 14.5's
 * rule that no token appears in a URL, which is what an image library's usual `?token=` shortcut
 * would break.
 *
 * ### The disk cache
 *
 * PRODUCT_SPEC LIB-001 asks for covers to be available offline. This is the first half of that: Coil's
 * disk cache survives a process restart, so a browsed shelf keeps its covers on a train. It is not the
 * whole of it — a *downloaded* book must keep its cover whether or not the cache has been evicted, and
 * that belongs with downloads in Phase 4.
 */
@Module
@InstallIn(SingletonComponent::class)
object ImageModule {

    @Provides
    @Singleton
    fun providesImageLoader(
        @ApplicationContext context: Context,
        @AuthenticatedClient client: OkHttpClient,
    ): ImageLoader = ImageLoader.Builder(context)
        .okHttpClient(client)
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("covers"))
                .maxSizeBytes(COVER_CACHE_BYTES)
                .build()
        }
        // Covers are the same bytes for the life of an edit, and a shelf scrolls past dozens of them.
        // Respecting the server's cache headers is not enough on its own — Audiobookshelf does not
        // always send one — so the disk cache is sized deliberately rather than left to the default.
        .respectCacheHeaders(false)
        .build()

    /** 128 MB. Roughly a thousand covers at the size a phone renders them. */
    private const val COVER_CACHE_BYTES = 128L * 1024 * 1024
}
