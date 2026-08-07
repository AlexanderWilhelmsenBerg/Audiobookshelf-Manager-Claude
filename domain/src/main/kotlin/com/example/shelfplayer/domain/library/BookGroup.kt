package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.library.Book

/** PRODUCT_SPEC LIB-002 — the axes whose rows are not books and not series. */
enum class BookGroupKind { Author, Genre }

/**
 * One group the book list has been narrowed to, carrying its label so the screen can name it.
 *
 * Opening an author or a genre narrows the book list in place rather than pushing a screen. The two
 * would look identical to the user, and this way the sort chips, the filter chips and the search field
 * all keep working inside the narrowed list — a pushed screen would have to grow its own copies of
 * each, or do without them.
 */
data class BookFocus(val kind: BookGroupKind, val key: String, val label: String)

/**
 * PRODUCT_SPEC LIB-002 — one author, or one genre, and the books under it.
 *
 * Deliberately not [SeriesShelf]. A series has an order and a position per book; an author and a genre
 * have neither, and folding them into one type would mean carrying a sequence that is always absent
 * and inviting a screen to render it.
 *
 * @property key what identifies the group. An author id, or the genre's own text — genres are free
 *   strings on an Audiobookshelf server with no id behind them, which is also why they are matched
 *   case-insensitively below.
 */
data class BookGroup(val kind: BookGroupKind, val key: String, val label: String, val books: List<Book>) {
    val bookCount: Int get() = books.size
}

/**
 * Groups [books] by [kind], alphabetically by label.
 *
 * A book with three authors appears under all three, and one with four genres under all four: the
 * question "what else is by this author" has one right answer, and dropping every author but the first
 * would make it the wrong one for anything co-written.
 */
fun groupBooks(books: List<Book>, kind: BookGroupKind): List<BookGroup> {
    val grouped = LinkedHashMap<String, MutableList<Book>>()
    val labels = HashMap<String, String>()
    for (book in books) {
        for ((key, label) in book.keysFor(kind)) {
            grouped.getOrPut(key) { mutableListOf() }.add(book)
            labels.putIfAbsent(key, label)
        }
    }
    return grouped
        .map { (key, grouping) ->
            BookGroup(
                kind = kind,
                key = key,
                label = labels.getValue(key),
                books = grouping.sortedWith(compareBy({ it.title.lowercase() }, { it.id.value })),
            )
        }
        .sortedWith(compareBy({ it.label.lowercase() }, { it.key }))
}

/** PRODUCT_SPEC LIB-002 — a group matches on its own label or on any book inside it, as a series does. */
fun BookGroup.matchesQuery(query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return label.contains(needle, ignoreCase = true) || books.any { it.matchesQuery(needle) }
}

/** Whether this book belongs to the named group, used to narrow the book list to one of them. */
fun Book.inGroup(kind: BookGroupKind, key: String): Boolean = keysFor(kind).any { it.first == key }

/**
 * The group keys this book contributes, paired with the label to display for each.
 *
 * A genre keys on its lowercased text so that `Sci-Fi` and `sci-fi` — which one library will contain
 * both of, because the value is whatever a tag editor typed — are one group rather than two. The label
 * kept is the first spelling encountered, which makes the result depend on nothing but the sort.
 */
private fun Book.keysFor(kind: BookGroupKind): List<Pair<String, String>> = when (kind) {
    BookGroupKind.Author -> authors.map { it.id.value to it.name }
    BookGroupKind.Genre -> genres.filter(String::isNotBlank).map { it.lowercase() to it }
}
