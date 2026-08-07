package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.library.Book

/**
 * PRODUCT_SPEC LIB-002 — title, subtitle, author, narrator, series, genre and tags, when the data exists.
 *
 * Matching happens against cached rows so results appear immediately; enriching them with a server-side
 * search is the remainder of LIB-002.
 *
 * This lives beside [sortBooks] rather than inside one use case because two screens search: the shelf of
 * every accessible book, and a single library. One predicate is what keeps them from drifting into two
 * different answers for the same query.
 */
fun Book.matchesQuery(query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    val haystack = buildList {
        add(title)
        subtitle?.let(::add)
        authors.forEach { add(it.name) }
        addAll(narrators)
        seriesMemberships.forEach { add(it.series.name) }
        addAll(tags)
        addAll(genres)
    }
    return haystack.any { it.contains(needle, ignoreCase = true) } || matchesIdentifier(needle)
}

/**
 * PRODUCT_SPEC LIB-002 — ISBN and ASIN.
 *
 * Matched separately from the free-text fields, and not by `contains`, for two reasons:
 *
 *  - **Punctuation.** The same ISBN is written `978-0-00-000000-0` on a book jacket and
 *    `9780000000000` in a metadata tag. Comparing the digits alone is what makes the number the user
 *    reads off the cover find the book. Nothing is normalized away from ASIN, which is alphanumeric by
 *    construction — stripping there would let `B00-ABC` match `B00ABC`, which are not the same product.
 *  - **Prefixes, not fragments.** `contains` on a 13-digit number turns a three-digit query into a
 *    match on every book in the library. A prefix is the shape of a partially typed identifier;
 *    a fragment from the middle of one is not something anyone types on purpose.
 */
private fun Book.matchesIdentifier(needle: String): Boolean {
    val asinMatch = asin?.startsWith(needle, ignoreCase = true) == true
    if (asinMatch) return true
    val digits = needle.filter(Char::isDigit)
    if (digits.isEmpty()) return false
    return isbn?.filter(Char::isDigit)?.startsWith(digits) == true
}
