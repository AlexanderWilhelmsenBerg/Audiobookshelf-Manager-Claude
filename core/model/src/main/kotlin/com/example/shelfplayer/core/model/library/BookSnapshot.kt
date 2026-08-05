package com.example.shelfplayer.core.model.library

/**
 * Everything a gateway returns about one book in a single unit.
 *
 * PRODUCT_SPEC 2.3 — "offline means genuinely offline": a book is only useful offline when its
 * tracks, chapters and metadata were stored together. Returning them as one value means a repository
 * cannot accidentally persist a book without the track offsets the player needs to resume it.
 */
data class BookSnapshot(val book: Book, val tracks: List<AudioTrack>, val chapters: List<Chapter>)
