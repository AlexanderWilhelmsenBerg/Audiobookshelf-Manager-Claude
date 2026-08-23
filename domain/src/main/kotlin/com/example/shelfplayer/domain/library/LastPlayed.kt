package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.library.Book
import java.time.Instant

/**
 * PRODUCT_SPEC ROUTE-001 / 6.5.6 — the book a listener would expect to come back to.
 *
 * ### Why this is its own function
 *
 * It was `AutoLibrary.lastPlayed()`, resolving the active profile itself, which made it exactly the wrong
 * shape for 6.5.6: restoring the profile somebody has just switched **to** needs the answer for *that*
 * account, and a function that looks up "whoever is active" cannot give it. The rule is the same either way,
 * so it moves here and both callers share it rather than a second copy drifting from the first.
 *
 * ### A finished book is not something to resume
 *
 * ROUTE-001 is "resume what was playing". A book with no progress row has nothing to resume, and a finished
 * one has nothing left — offering either to a headset press or a car would start something the listener did
 * not leave running.
 *
 * Deliberately **not** the Continue shelf, even though the two answer nearly the same question. The shelf is
 * a browsing surface and may reasonably show more; sharing one list would mean a media button eventually
 * starting a book at random. That reasoning came from `AutoLibrary` and is kept here with the code it
 * belongs to.
 */
fun lastPlayedBook(books: List<Book>): Book? = books
    .filter { book -> book.progress?.isFinished == false }
    .maxByOrNull { book -> book.progress?.updatedAt ?: Instant.MIN }
