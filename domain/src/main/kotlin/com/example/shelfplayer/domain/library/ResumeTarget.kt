package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.playback.ListeningSession
import java.time.Instant
import kotlin.time.Duration

/** A resumable book together with the position and activity time that selected it. */
data class ResumeTarget(
    val book: Book,
    val position: Duration,
    val updatedAt: Instant,
)

/** The existing device-local answer, expressed as a target rather than only a book. */
fun localResumeTarget(books: List<Book>): ResumeTarget? {
    val book = lastPlayedBook(books) ?: return null
    val progress = book.progress ?: return null
    return ResumeTarget(book, progress.position, progress.updatedAt)
}

/**
 * Chooses what a listener most recently used across this device and Audiobookshelf.
 *
 * Local wins ties so an unsynced local write can never be displaced by equal/older server state. A server
 * session is eligible only when it contains real listening, its book is still accessible, and the cached
 * progress does not explicitly say the book is finished. The actual playback session will ask ABS for its
 * authoritative start position again; [ListeningSession.reachedAt] is mainly for a resume tile that must not
 * open a fake listening session merely to draw metadata.
 */
fun reconciledResumeTarget(books: List<Book>, server: ListeningSession?): ResumeTarget? {
    val local = localResumeTarget(books)
    val remote = server?.takeIf { it.listened > Duration.ZERO }?.let { session ->
        val book = books.firstOrNull { it.id == session.bookId } ?: return@let null
        if (book.progress?.isFinished == true) return@let null
        val position = when {
            book.duration > Duration.ZERO -> session.reachedAt.coerceIn(Duration.ZERO, book.duration)
            else -> session.reachedAt.coerceAtLeast(Duration.ZERO)
        }
        ResumeTarget(book, position, session.updatedAt)
    }

    return when {
        remote == null -> local
        local == null -> remote
        remote.updatedAt > local.updatedAt -> remote
        else -> local
    }
}
