package com.example.shelfplayer.navigation

import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId

/**
 * PRODUCT_SPEC 9.1 — the routes of the Phase 0 shell.
 *
 * PRODUCT_SPEC 15 requires deep links to validate server/profile/item access. Phase 0 registers no
 * `<intent-filter>` deep links at all: a route that cannot be reached from outside the app cannot be
 * used to bypass a permission check, and writing that validation before there are permissions to
 * validate would produce an untestable stub.
 */
object ShelfDestinations {
    const val HOME = "home"
    const val LIBRARY = "library/{libraryId}"
    const val BOOK = "book/{bookId}"

    const val ARG_LIBRARY_ID = "libraryId"
    const val ARG_BOOK_ID = "bookId"

    fun library(libraryId: LibraryId): String = "library/${libraryId.value}"

    fun book(bookId: LibraryItemId): String = "book/${bookId.value}"
}
