package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.core.model.library.Book
import kotlin.time.Duration

/** LIB-002/003/004, PD-006: the same authorized author projection for both navigation entry points. */
data class AuthorShelf(
    val author: Author,
    val books: List<Book>,
    val series: List<SeriesShelf> = emptyList(),
    /** False during incomplete/unknown refreshes, even if every cached member is finished. */
    val catalogueComplete: Boolean = false,
) {
    val bookCount: Int get() = books.size
    val totalDuration: Duration get() = books.fold(Duration.ZERO) { running, book -> running + book.duration }
    val finishedCount: Int get() = books.count { it.progress?.isFinished == true }
    val standaloneBooks: List<Book> get() = books.filter { it.seriesMemberships.isEmpty() }
}

/**
 * Input must be the full profile-authorized catalogue, never a Home search/filter or author-only subset.
 * Every membership contributes a distinct series; its members include other authors' accessible books.
 * Missing/revoked authors produce no projection. Prefer confirmed portrait metadata over expanded names.
 */
fun authorShelfFor(books: List<Book>, authorId: AuthorId, catalogueComplete: Boolean = false): AuthorShelf? {
    val accessible = books.distinctBy { it.id }
    val matching = accessible.filter { book -> book.authors.any { it.id == authorId } }
    if (matching.isEmpty()) return null
    val author = matching.flatMap(Book::authors).filter { it.id == authorId }
        .maxByOrNull { if (it.hasPortrait) 1 else 0 } ?: return null
    val seriesIds = matching.flatMap { it.seriesMemberships }.map { it.series.id }.toSet()
    return AuthorShelf(
        author = author,
        books = matching.sortedWith(compareBy({ it.title.lowercase() }, { it.id.value })),
        series = groupIntoSeries(accessible).filter { it.series.id in seriesIds },
        catalogueComplete = catalogueComplete,
    )
}
