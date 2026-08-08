package com.example.shelfplayer.playback

import javax.inject.Qualifier

/**
 * PRODUCT_SPEC PLAY-001 — the HTTP stack audio and artwork are fetched over.
 *
 * Qualified rather than bound bare because `DataSource.Factory` is a Media3 type with several
 * implementations, and a future one — a cache-backed factory for downloads (Phase 3) — has to be
 * distinguishable from this one at the injection point rather than by whichever binding was declared
 * last.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MediaDataSource
