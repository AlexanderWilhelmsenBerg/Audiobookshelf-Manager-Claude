package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.core.model.library.Book
import kotlin.time.Duration

/**
 * PRODUCT_SPEC §62 "author view" / LIB-002 — one author and everything of theirs this profile can see.
 *
 * ### Why this exists next to [BookGroup], which also groups by author
 *
 * [BookGroup] is what the **Authors browse axis** produces, and opening one there narrows the book list in
 * place so the sort chips, filter chips and search field keep working — see [BookFocus]. That is right for
 * somebody already holding those controls.
 *
 * This is the other entry point: arriving from a book's own page, where there are no controls to keep and
 * *"what else has this person written"* is the whole question. It is a screen, symmetric with the series
 * screen reached from the line above it. The two behaviours are deliberate and not an oversight; what would
 * be an oversight is two different *identities* for an author, which is why both key on [AuthorId] and both
 * come out of the same `authors` list on a [Book].
 *
 * It carries the [Author] itself rather than a label, because the screen draws a portrait and
 * `Author.hasPortrait` is the flag that says whether one may be requested at all (LIB-001).
 */
data class AuthorShelf(val author: Author, val books: List<Book>) {

    val bookCount: Int get() = books.size

    val totalDuration: Duration get() = books.fold(Duration.ZERO) { running, book -> running + book.duration }

    val finishedCount: Int get() = books.count { it.progress?.isFinished == true }
}

/**
 * PRODUCT_SPEC §62 — builds the shelf for [authorId], or `null` when this profile can see none of it.
 *
 * `null` rather than an empty shelf: an author with no visible books is not an author this profile knows
 * about, and a screen with a name and nothing under it reads as a failure. The caller renders "not
 * available", the same answer [SeriesShelf] gets in the same situation.
 *
 * ### The order
 *
 * By title, then id — the same order `groupBooks` gives the Authors axis, so a reader who opens the same
 * author from either entry point sees the same list in the same order. Series sequence is deliberately not
 * used: an author writes in several series and standalone besides, and ordering the whole shelf by one
 * series' numbering would interleave unrelated books by their coincidental positions.
 *
 * ### The [Author] that is kept
 *
 * The richest one found, not the first. Expanded book metadata carries only an author's id and name, while
 * the author listing adds the portrait facts — so the same person arrives as two values differing only in
 * whether a portrait may be requested, and taking the first would blank the portrait depending on which
 * book happened to sort first (LIB-001).
 */
fun authorShelfFor(books: List<Book>, authorId: AuthorId): AuthorShelf? {
    val matching = books.filter { book -> book.authors.any { it.id == authorId } }
    if (matching.isEmpty()) return null
    val author = matching
        .flatMap(Book::authors)
        .filter { it.id == authorId }
        .maxByOrNull { if (it.hasPortrait) 1 else 0 }
        ?: return null
    return AuthorShelf(
        author = author,
        books = matching.sortedWith(compareBy({ it.title.lowercase() }, { it.id.value })),
    )
}
