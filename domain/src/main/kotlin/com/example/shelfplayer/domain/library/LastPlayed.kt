package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.Book

/**
 * BW-PLAY-01 — resolves a device-local remembered identity against the books visible to one profile.
 *
 * The identity itself is the source of truth. Progress is consulted only to reject a finished book; its
 * timestamp never participates, so REST/realtime progress from another client cannot silently choose what
 * this phone resumes.
 */
fun rememberedBook(books: List<Book>, rememberedId: LibraryItemId?): Book? {
    val id = rememberedId ?: return null
    return books.firstOrNull { book ->
        book.id == id && book.progress?.isFinished == false
    }
}
