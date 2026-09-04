package com.example.shelfplayer.domain.settings

import com.example.shelfplayer.core.model.settings.BackgroundTheme

/**
 * PRODUCT_SPEC SET-002 (Appearance) — every bundled background theme, in the order they are offered.
 *
 * ### Why this is a seam rather than a constant
 *
 * The themes are data on disk, not code: a pack is a manifest, a palette per theme and a picture per
 * theme, and adding one is dropping a directory in beside the others. That is the property worth keeping —
 * it is what "prepare for more themes" asked for — and it means the list can only be produced by something
 * that reads assets, which a screen must not do and a test must be able to replace.
 *
 * The implementation reads and parses **once** and caches, because the answer cannot change while the app
 * is running: these are bundled assets, not a download.
 */
interface BackgroundThemeCatalog {
    /** Every bundled theme. Empty only if the bundled assets are unreadable, which is a build fault. */
    suspend fun themes(): List<BackgroundTheme>

    /** One theme by its id, or `null` for an id no build recognises — the same rule every stored key follows. */
    suspend fun theme(id: String): BackgroundTheme?
}
