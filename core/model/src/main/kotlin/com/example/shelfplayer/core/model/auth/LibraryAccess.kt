package com.example.shelfplayer.core.model.auth

import com.example.shelfplayer.core.model.LibraryId

/**
 * PRODUCT_SPEC 5.2 — the server's library grant for one account, and the only place the rule lives.
 *
 * Extracted from [AuthSession] because two callers need the rule and only one of them may hold a token.
 * A sign-in produces it as part of a session; a library sync applies it while holding nothing but a
 * profile id. Duplicating `hasAllLibraryAccess || id in list` at the second call site is how the two
 * drift, and the direction they drift in is "an unauthorized library became visible".
 *
 * ### The empty-list trap
 *
 * `permissions.accessAllLibraries` with an **empty** `librariesAccessible` means *all libraries*, not
 * none. Verified on Audiobookshelf 2.36.0: an ordinary admin has the flag set and an empty list, so
 * reading the list alone hides every library from them. That is why [hasAllLibraryAccess] is checked
 * first and why it is not derived from the list being empty.
 */
data class LibraryAccess(val hasAllLibraryAccess: Boolean, val accessibleLibraryIds: List<LibraryId>) {

    /**
     * Whether this account may see [libraryId].
     *
     * Answered from the server's grant, never inferred from a role: an account can be an `admin` with
     * every library, or a `user` restricted to two.
     */
    fun allows(libraryId: LibraryId): Boolean = hasAllLibraryAccess || libraryId in accessibleLibraryIds

    companion object {
        /**
         * The grant an account has before the server has told us anything.
         *
         * Nothing, deliberately. A profile whose grant has not been recorded must not see a library
         * (PRODUCT_SPEC 5.2), for the same reason an unprobed capability is unsupported: the safe
         * default is the restrictive one.
         */
        val None: LibraryAccess = LibraryAccess(hasAllLibraryAccess = false, accessibleLibraryIds = emptyList())
    }
}
