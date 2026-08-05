package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.library.Book

/**
 * PRODUCT_SPEC LIB-002 — title, subtitle, author, narrator, series, genre and tags, when the data exists.
 *
 * Matching happens against cached rows so results appear immediately; enriching them with a server-side
 * search is the remainder of LIB-002.
 *
 * ISBN and ASIN are part of the same requirement and join this predicate when the sync that populates
 * them exists. Matching against a field nothing fills would be a test that proves nothing.
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
    return haystack.any { it.contains(needle, ignoreCase = true) }
}
