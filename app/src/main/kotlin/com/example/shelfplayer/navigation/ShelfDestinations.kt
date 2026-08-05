package com.example.shelfplayer.navigation

import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId

/**
 * PRODUCT_SPEC 9.1 — the routes of the app shell.
 *
 * PRODUCT_SPEC 15 requires deep links to validate server/profile/item access. No `<intent-filter>` deep
 * links are registered: a route that cannot be reached from outside the app cannot be used to bypass a
 * permission check. When they are registered, the check they need already exists —
 * `AbsLibraryApi.listBooks` refuses a library the active profile is not granted, whatever asked for it.
 */
object ShelfDestinations {
    const val SIGN_IN = "sign-in"
    const val HOME = "home"
    const val PROFILES = "profiles"
    const val SETTINGS = "settings"
    const val LIBRARY = "library/{libraryId}"
    const val BOOK = "book/{bookId}"

    const val ARG_LIBRARY_ID = "libraryId"
    const val ARG_BOOK_ID = "bookId"

    fun library(libraryId: LibraryId): String = "library/${libraryId.value}"

    fun book(bookId: LibraryItemId): String = "book/${bookId.value}"
}
