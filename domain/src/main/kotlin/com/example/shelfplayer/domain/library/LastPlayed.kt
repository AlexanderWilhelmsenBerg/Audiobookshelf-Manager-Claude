package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.Book

/**
 * BW-PLAY-01 — resolves a device-local remembered identity against the books visible to one profile.
 *
 * The identity itself is the source of truth. Progress is consulted only to reject a finished book. A missing
 * progress projection does not erase an identity created by actual local playback, and the progress timestamp
 * never participates, so REST/realtime progress from another client cannot silently choose what this phone resumes.
 */
fun rememberedBook(books: List<Book>, rememberedId: LibraryItemId?): Book? {
    val id = rememberedId ?: return null
    return books.firstOrNull { book ->
        book.id == id && book.progress?.isFinished != true
    }
}

/**
 * Issue #88 — the book a newly recreated media session may present as resumable.
 *
 * A valid device-local remembered identity remains authoritative. Only when that identity is absent,
 * inaccessible, or finished may server-synced progress choose a fallback, and that fallback is exactly the
 * existing Continue-listening order: unfinished books by newest progress timestamp.
 */
fun resumeCandidate(books: List<Book>, rememberedId: LibraryItemId?): Book? =
    rememberedBook(books, rememberedId)
        ?: homeShelvesOf(books, limit = 1).continueListening.firstOrNull()
